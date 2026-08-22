package edu.weijunyong.satedgesim.server;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import org.cloudbus.cloudsim.vms.Vm;

import edu.weijunyong.satedgesim.Network.FileTransferProgress;
import edu.weijunyong.satedgesim.SimulationManager.SimulationManager;
import edu.weijunyong.satedgesim.TasksGenerator.Task;

/**
 * Applies an RL continuous resource profile to SatEdgeSim native execution paths.
 *
 * <p>The binding is deliberately explicit and task-scoped:
 * <ul>
 *   <li>CPU: selected VM MIPS is reduced while one or more native-bound tasks are active.</li>
 *   <li>Network: FileTransferProgress bandwidth allocation is multiplied by bandwidthShare.</li>
 *   <li>Power: wireless transmission energy is multiplied by txPowerRatio.</li>
 * </ul>
 *
 * <p>CloudSim VMs expose a VM-wide MIPS value rather than a per-cloudlet MIPS cap in
 * this project. For overlapping native-bound tasks on the same VM, the VM is held at
 * the most restrictive active cpuShare. This avoids over-claiming a per-task scheduler
 * that SatEdgeSim/CloudSim is not using, while still making cpuShare affect the native
 * VM scheduler rather than only a cost estimator.
 */
public final class RlNativeResourceBindingManager {
    public static final String CPU_BINDING_SCOPE = "vm_mips_scoped_min_active_share";
    public static final String NETWORK_BINDING_SCOPE = "shared_lan_domain_and_global_wan";
    public static final String TX_POWER_BINDING_SCOPE = "wireless_transmission_energy_ratio";

    private static final Map<Long, Binding> taskBindings = new LinkedHashMap<Long, Binding>();
    private static final Map<Long, VmState> vmStates = new LinkedHashMap<Long, VmState>();
    private static final List<Map<String, Object>> runtimeTrace = new ArrayList<Map<String, Object>>();
    private static long runtimeSampleCount = 0L;
    private static double maxCpuConservationViolation = 0.0;

    private RlNativeResourceBindingManager() {
    }

    /** Clears native bindings from the previous server session before a new world is built. */
    public static synchronized void resetForSimulation() {
        for (Binding binding : new ArrayList<Binding>(taskBindings.values())) {
            if (binding != null && !binding.released) releaseBinding(binding, 0.0);
        }
        taskBindings.clear();
        vmStates.clear();
        runtimeSampleCount = 0L;
        maxCpuConservationViolation = 0.0;
        runtimeTrace.clear();
    }

    public static synchronized BindingSnapshot bindTask(
            Task task,
            Vm vm,
            int vmIndex,
            RlResourceProfile profile,
            double simulationTime) {
        if (profile == null || !profile.nativeSchedulerBound()) {
            return BindingSnapshot.notRequested();
        }
        if (task == null) {
            throw new IllegalArgumentException("native_scheduler_bound requires a non-null task");
        }
        if (vm == null) {
            throw new IllegalArgumentException("native_scheduler_bound requires a selected VM");
        }
        long taskId = task.getId();
        long vmId = vm.getId();
        VmState state = vmStates.get(Long.valueOf(vmId));
        if (state == null || state.vm != vm) {
            state = new VmState(vm, vmId, readVmMips(vm));
            vmStates.put(Long.valueOf(vmId), state);
        }
        Binding existing = taskBindings.get(Long.valueOf(taskId));
        if (existing != null && !existing.released) {
            releaseBinding(existing, simulationTime);
        }
        Binding binding = new Binding();
        binding.taskId = taskId;
        binding.vmId = vmId;
        binding.vmIndex = vmIndex;
        binding.vm = vm;
        binding.vmState = state;
        binding.profile = profile;
        binding.cpuShare = profile.cpuShareClamped;
        binding.bandwidthShare = profile.bandwidthShareClamped;
        binding.txPowerRatio = profile.txPowerRatioClamped;
        binding.baseMips = state.baseMips;
        binding.boundAt = simulationTime;
        binding.lastObservedFinishedLength = Math.max(0.0, task.getFinishedLengthSoFar());
        binding.lastObservedAt = simulationTime;
        taskBindings.put(Long.valueOf(taskId), binding);
        state.activeBindings.put(Long.valueOf(taskId), binding);
        recomputeVmMips(state);
        binding.nativeBindingApplied = true;
        return binding.snapshot("bound");
    }

    public static synchronized BindingSnapshot releaseTask(Task task, SimulationManager simulationManager) {
        if (task == null) {
            return BindingSnapshot.notRequested();
        }
        double simulationTime = simulationManager == null || simulationManager.getSimulation() == null
                ? 0.0
                : simulationManager.getSimulation().clock();
        Binding binding = taskBindings.get(Long.valueOf(task.getId()));
        if (binding == null) {
            return BindingSnapshot.notRequested();
        }
        return releaseBinding(binding, simulationTime);
    }

    /**
     * Rebinds an already executing task without changing its VM placement.
     * The same native binding registry is used, and active native transfers
     * are refreshed so a bandwidth/power patch affects physical progression.
     */
    public static synchronized BindingSnapshot rebindTask(
            Task task,
            Vm vm,
            int vmIndex,
            RlResourceProfile profile,
            double simulationTime,
            SimulationManager simulationManager) {
        BindingSnapshot snapshot = bindTask(task, vm, vmIndex, profile, simulationTime);
        refreshTransfersForTask(task, simulationManager);
        return snapshot;
    }

    public static synchronized BindingSnapshot snapshotForTask(Task task) {
        if (task == null) return BindingSnapshot.notRequested();
        Binding binding = taskBindings.get(Long.valueOf(task.getId()));
        return binding == null ? BindingSnapshot.notRequested() : binding.snapshot("observed");
    }

    private static void refreshTransfersForTask(Task task, SimulationManager simulationManager) {
        if (task == null || simulationManager == null || simulationManager.getNetworkModel() == null) return;
        for (FileTransferProgress transfer : simulationManager.getNetworkModel().getTransferProgressList()) {
            if (transfer != null && transfer.getTask() == task) attachToTransfer(transfer);
        }
    }

    public static synchronized RlResourceProfile profileForTask(Task task) {
        if (task == null) {
            return null;
        }
        Binding binding = taskBindings.get(Long.valueOf(task.getId()));
        return binding == null || binding.released ? null : binding.profile;
    }

    public static synchronized double bandwidthShareForTask(Task task) {
        RlResourceProfile profile = profileForTask(task);
        return profile == null || !profile.nativeSchedulerBound() ? 1.0 : profile.bandwidthShareClamped;
    }

    public static synchronized double txPowerRatioForTask(Task task) {
        RlResourceProfile profile = profileForTask(task);
        return profile == null || !profile.nativeSchedulerBound() ? 1.0 : profile.txPowerRatioClamped;
    }

    public static synchronized void attachToTransfer(FileTransferProgress transfer) {
        if (transfer == null || transfer.getTask() == null) {
            return;
        }
        RlResourceProfile profile = profileForTask(transfer.getTask());
        if (profile == null || !profile.nativeSchedulerBound()) {
            transfer.setNativeNetworkBound(false);
            transfer.setNativeTxPowerBound(false);
            transfer.setBandwidthShareClamped(1.0);
            transfer.setTxPowerRatioClamped(1.0);
            return;
        }
        transfer.setNativeNetworkBound(true);
        transfer.setNativeTxPowerBound(true);
        transfer.setBandwidthShareClamped(profile.bandwidthShareClamped);
        transfer.setTxPowerRatioClamped(profile.txPowerRatioClamped);
    }

    /**
     * Observes service delivered by the native CloudSim Cloudlet scheduler.
     * This is deliberately a delta of Cloudlet finished length over simulation
     * time; it is not a second allocator.  The VM MIPS value remains the
     * physical capacity consumed by CloudSim's scheduler.
     */
    public static synchronized void observeRuntimeProgress(List<Task> tasks, double simulationTime) {
        Map<Long, Task> byId = new HashMap<Long, Task>();
        if (tasks != null) {
            for (Task task : tasks) {
                if (task != null) byId.put(Long.valueOf(task.getId()), task);
            }
        }
        for (Binding binding : taskBindings.values()) {
            if (binding == null || binding.released) continue;
            Task task = byId.get(Long.valueOf(binding.taskId));
            if (task == null) continue;
            double finished = Math.max(0.0, task.getFinishedLengthSoFar());
            double elapsed = simulationTime - binding.lastObservedAt;
            if (elapsed > 1.0e-9) {
                double delta = Math.max(0.0, finished - binding.lastObservedFinishedLength);
                binding.effectiveMips = delta / elapsed;
                binding.effectiveCpuShare = binding.baseMips <= 0.0 ? 0.0 : binding.effectiveMips / binding.baseMips;
                binding.observedAt = simulationTime;
                runtimeSampleCount += 1L;
            }
            binding.lastObservedFinishedLength = finished;
            binding.lastObservedAt = simulationTime;
            binding.capacityMips = binding.vmState == null ? binding.appliedMips : binding.vmState.currentAppliedMips;
            binding.contentionCount = binding.vmState == null ? 0 : binding.vmState.activeBindings.size();
            binding.contentionContext = binding.vmState == null ? "unknown" :
                    "vm:" + binding.vmState.vmId + ":active_tasks=" + binding.vmState.activeBindings.size();
            if (elapsed > 1.0e-9) {
                runtimeTrace.add(binding.snapshot("runtime_observed").toMap());
                while (runtimeTrace.size() > 4096) runtimeTrace.remove(0);
            }
        }
        for (VmState state : vmStates.values()) {
            double effectiveSum = 0.0;
            for (Binding binding : state.activeBindings.values()) {
                if (binding != null && !binding.released) effectiveSum += Math.max(0.0, binding.effectiveMips);
            }
            maxCpuConservationViolation = Math.max(maxCpuConservationViolation,
                    Math.max(0.0, effectiveSum - state.currentAppliedMips));
        }
    }

    public static synchronized Map<String, Object> runtimeConservationEvidence() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> entries = new ArrayList<Map<String, Object>>();
        for (Binding binding : taskBindings.values()) {
            if (binding != null && !binding.released) entries.add(binding.snapshot("runtime_observed").toMap());
        }
        out.put("resource", "cpu");
        out.put("observed", runtimeSampleCount > 0L);
        out.put("sampleCount", runtimeSampleCount);
        out.put("conservationSatisfied", runtimeSampleCount > 0L && maxCpuConservationViolation <= 1.0e-6);
        out.put("maxEffectiveOverCapacityMips", maxCpuConservationViolation);
        out.put("executionConsumer", "CloudSim_native_vm_cloudlet_scheduler");
        out.put("entries", entries);
        out.put("trace", new ArrayList<Map<String, Object>>(runtimeTrace));
        return out;
    }

    public static synchronized Map<String, Object> debugSnapshot() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("active_task_bindings", taskBindings.size());
        Map<String, Object> vms = new LinkedHashMap<String, Object>();
        for (Map.Entry<Long, VmState> entry : vmStates.entrySet()) {
            Map<String, Object> vm = new LinkedHashMap<String, Object>();
            VmState state = entry.getValue();
            vm.put("base_mips", state.baseMips);
            vm.put("current_applied_mips", state.currentAppliedMips);
            vm.put("active_bindings", state.activeBindings.size());
            double effectiveSum = 0.0;
            for (Binding binding : state.activeBindings.values()) {
                if (binding != null && !binding.released) effectiveSum += binding.effectiveMips;
            }
            vm.put("effective_mips_sum", effectiveSum);
            vm.put("conservation_satisfied", effectiveSum <= state.currentAppliedMips + 1.0e-6);
            vms.put(String.valueOf(entry.getKey()), vm);
        }
        out.put("vm_states", vms);
        return out;
    }

    private static BindingSnapshot releaseBinding(Binding binding, double simulationTime) {
        if (binding == null || binding.released) {
            return BindingSnapshot.notRequested();
        }
        binding.released = true;
        binding.releasedAt = simulationTime;
        if (binding.vmState != null) {
            binding.vmState.activeBindings.remove(Long.valueOf(binding.taskId));
            recomputeVmMips(binding.vmState);
            binding.restoredMips = binding.vmState.currentAppliedMips;
        }
        taskBindings.remove(Long.valueOf(binding.taskId));
        return binding.snapshot("released");
    }

    private static void recomputeVmMips(VmState state) {
        if (state == null || state.vm == null) {
            return;
        }
        double minShare = 1.0;
        for (Binding binding : state.activeBindings.values()) {
            if (binding != null && !binding.released) {
                minShare = Math.min(minShare, Math.max(0.0, binding.cpuShare));
            }
        }
        double targetMips = Math.max(1.0, state.baseMips * minShare);
        applyVmMips(state.vm, targetMips);
        state.currentAppliedMips = targetMips;
        for (Binding binding : state.activeBindings.values()) {
            if (binding != null && !binding.released) {
                binding.appliedMips = targetMips;
                binding.capacityMips = targetMips;
                binding.contentionCount = state.activeBindings.size();
                binding.contentionContext = "vm:" + state.vmId + ":active_tasks=" + state.activeBindings.size();
            }
        }
    }

    private static double readVmMips(Vm vm) {
        return Math.max(1.0, vm == null ? 1.0 : vm.getMips());
    }

    private static void applyVmMips(Vm vm, double mips) {
        if (vm == null) {
            throw new IllegalArgumentException("cannot apply native CPU binding to null VM");
        }
        double target = Math.max(1.0, mips);
        if (invokeSetter(vm, "setMips", double.class, Double.valueOf(target))) {
            return;
        }
        if (invokeSetter(vm, "setMips", long.class, Long.valueOf(Math.round(target)))) {
            return;
        }
        try {
            Method getProcessor = vm.getClass().getMethod("getProcessor");
            Object processor = getProcessor.invoke(vm);
            if (processor != null && invokeSetter(processor, "setMips", double.class, Double.valueOf(target))) {
                return;
            }
            if (processor != null && invokeSetter(processor, "setMips", long.class, Long.valueOf(Math.round(target)))) {
                return;
            }
            if (processor != null && invokeSetter(processor, "setCapacity", double.class, Double.valueOf(target))) {
                return;
            }
            if (processor != null && invokeSetter(processor, "setCapacity", long.class, Long.valueOf(Math.round(target)))) {
                return;
            }
        } catch (NoSuchMethodException e) {
            // Fall through to the explicit error below.
        } catch (Exception e) {
            throw new IllegalStateException("failed to apply native CPU MIPS binding through VM processor", e);
        }
        throw new IllegalStateException(
                "CloudSim VM does not expose setMips or processor capacity setters; cannot apply native CPU binding");
    }

    private static boolean invokeSetter(Object target, String methodName, Class<?> parameterType, Object value) {
        if (target == null) {
            return false;
        }
        try {
            Method method = target.getClass().getMethod(methodName, parameterType);
            method.invoke(target, value);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        } catch (Exception e) {
            throw new IllegalStateException("failed to invoke " + methodName + " for native CPU binding", e);
        }
    }

    private static final class VmState {
        final Vm vm;
        final long vmId;
        final double baseMips;
        double currentAppliedMips;
        final Map<Long, Binding> activeBindings = new LinkedHashMap<Long, Binding>();

        VmState(Vm vm, long vmId, double baseMips) {
            this.vm = vm;
            this.vmId = vmId;
            this.baseMips = baseMips;
            this.currentAppliedMips = baseMips;
        }
    }

    private static final class Binding {
        long taskId = -1L;
        long vmId = -1L;
        int vmIndex = -1;
        Vm vm;
        VmState vmState;
        RlResourceProfile profile;
        double cpuShare = 1.0;
        double bandwidthShare = 1.0;
        double txPowerRatio = 1.0;
        double baseMips = 1.0;
        double appliedMips = 1.0;
        double restoredMips = 1.0;
        double boundAt = 0.0;
        double releasedAt = 0.0;
        double effectiveMips = 0.0;
        double effectiveCpuShare = 0.0;
        double capacityMips = 1.0;
        double lastObservedFinishedLength = 0.0;
        double lastObservedAt = 0.0;
        double observedAt = Double.NaN;
        int contentionCount = 0;
        String contentionContext = "unobserved";
        boolean nativeBindingApplied = false;
        boolean released = false;

        BindingSnapshot snapshot(String stage) {
            BindingSnapshot out = new BindingSnapshot();
            out.requested = true;
            out.nativeBindingApplied = nativeBindingApplied;
            out.taskId = taskId;
            out.vmId = vmId;
            out.vmIndex = vmIndex;
            out.cpuShare = cpuShare;
            out.bandwidthShare = bandwidthShare;
            out.txPowerRatio = txPowerRatio;
            out.baseMips = baseMips;
            out.appliedMips = appliedMips;
            out.restoredMips = restoredMips;
            out.boundAt = boundAt;
            out.releasedAt = releasedAt;
            out.released = released;
            out.effectiveMips = effectiveMips;
            out.effectiveCpuShare = effectiveCpuShare;
            out.capacityMips = capacityMips;
            out.observedAt = Double.isFinite(observedAt) ? observedAt : -1.0;
            out.contentionCount = contentionCount;
            out.contentionContext = contentionContext;
            out.stage = stage;
            return out;
        }
    }

    public static final class BindingSnapshot {
        public boolean requested = false;
        public boolean nativeBindingApplied = false;
        public long taskId = -1L;
        public long vmId = -1L;
        public int vmIndex = -1;
        public double cpuShare = 1.0;
        public double bandwidthShare = 1.0;
        public double txPowerRatio = 1.0;
        public double baseMips = 0.0;
        public double appliedMips = 0.0;
        public double restoredMips = 0.0;
        public double boundAt = 0.0;
        public double releasedAt = 0.0;
        public double effectiveMips = 0.0;
        public double effectiveCpuShare = 0.0;
        public double capacityMips = 0.0;
        public double observedAt = Double.NaN;
        public int contentionCount = 0;
        public String contentionContext = "unobserved";
        public boolean released = false;
        public String stage = "not_requested";

        static BindingSnapshot notRequested() {
            return new BindingSnapshot();
        }

        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("requested", requested);
            out.put("native_binding_applied", nativeBindingApplied);
            out.put("task_id", taskId);
            out.put("vm_id", vmId);
            out.put("vm_index", vmIndex);
            out.put("cpu_share", cpuShare);
            out.put("bandwidth_share", bandwidthShare);
            out.put("tx_power_ratio", txPowerRatio);
            out.put("base_mips", baseMips);
            out.put("applied_mips", appliedMips);
            out.put("restored_mips", restoredMips);
            out.put("bound_at", boundAt);
            out.put("released_at", releasedAt);
            out.put("released", released);
            out.put("requested_cpu_share", cpuShare);
            out.put("effective_cpu_mips", effectiveMips);
            out.put("effective_cpu_share", effectiveCpuShare);
            out.put("cpu_capacity_mips", capacityMips);
            out.put("contention_count", contentionCount);
            out.put("contention_context", contentionContext);
            out.put("effective_observed_at", observedAt);
            out.put("stage", stage);
            out.put("cpu_binding_scope", CPU_BINDING_SCOPE);
            out.put("network_binding_scope", NETWORK_BINDING_SCOPE);
            out.put("tx_power_binding_scope", TX_POWER_BINDING_SCOPE);
            return out;
        }
    }
}
