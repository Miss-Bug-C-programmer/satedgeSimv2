package edu.weijunyong.satedgesim.server;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.cloudbus.cloudsim.vms.Vm;
import org.cloudbus.cloudsim.vms.VmSimple;

import edu.weijunyong.satedgesim.TasksGenerator.Task;

/** Native binding contract test for the persistent fast path (not a live simulation). */
public class PersistentNativeBindingContractTest {
    public static void main(String[] args) {
        RlDecisionBridge bridge = new RlDecisionBridge("persistent-binding-contract");
        PersistentExecutionConfiguration configuration = new PersistentExecutionConfiguration();
        configuration.configId = "cfg-native";
        configuration.version = 3L;
        Map<String, Object> assignment = new LinkedHashMap<String, Object>();
        assignment.put("targetVmIndex", 0);
        assignment.put("bindingMode", "native_scheduler_bound");
        assignment.put("cpuShare", 0.5);
        assignment.put("bandwidthShare", 0.5);
        assignment.put("txPowerRatio", 0.5);
        configuration.assignments.put("42", assignment);
        bridge.setPersistentConfiguration(configuration);

        Vm vm = new VmSimple(1000.0, 1L);
        List<Vm> vms = new ArrayList<Vm>();
        vms.add(vm);
        Task task = new Task(42, 100L, 1L);
        task.setVm(vm);
        int selected = bridge.resolvePersistentVm(
                null, new String[0], task, vms,
                new RlDecisionBridge.FeasibilityChecker() {
                    @Override
                    public boolean isFeasible(String[] architecture, Task candidate, Vm candidateVm) {
                        return true;
                    }
                });
        require(selected == 0, "persistent rule must select target VM");
        Map<String, Object> stats = bridge.getDecisionPlaneStats();
        Map<?, ?> dispatch = (Map<?, ?>) stats.get("lastPersistentDispatch");
        require(Boolean.TRUE.equals(dispatch.get("nativeBindingRequested")), "native binding must be requested");
        require(Boolean.TRUE.equals(dispatch.get("nativeBindingApplied")), "native binding must be applied");
        require("native_scheduler_bound".equals(dispatch.get("bindingMode")), "binding mode must be native");
        Map<?, ?> receipt = (Map<?, ?>) dispatch.get("executionReceipt");
        require(Boolean.TRUE.equals(receipt.get("nativeBindingApplied")), "receipt must expose native binding");
        require(Boolean.TRUE.equals(receipt.get("native_scheduler_bound")), "receipt must expose native mode");

        // Abstract/tier rules must not enumerate and evaluate the whole VM
        // list before the scoped planner path.  They fall back to the
        // pending-decision path instead.
        RlDecisionBridge abstractBridge = new RlDecisionBridge("persistent-identity-only");
        PersistentExecutionConfiguration abstractConfiguration = new PersistentExecutionConfiguration();
        abstractConfiguration.configId = "cfg-abstract";
        abstractConfiguration.version = 1L;
        Map<String, Object> abstractAssignment = new LinkedHashMap<String, Object>();
        abstractAssignment.put("abstractAction", 2);
        abstractConfiguration.assignments.put("42", abstractAssignment);
        abstractBridge.setPersistentConfiguration(abstractConfiguration);
        final int[] feasibilityCalls = new int[] {0};
        List<Vm> multipleVms = new ArrayList<Vm>();
        multipleVms.add(new VmSimple(1001.0, 1L));
        multipleVms.add(new VmSimple(1002.0, 1L));
        int abstractSelected = abstractBridge.resolvePersistentVm(
                null, new String[0], task, multipleVms,
                new RlDecisionBridge.FeasibilityChecker() {
                    @Override
                    public boolean isFeasible(String[] architecture, Task candidate, Vm candidateVm) {
                        feasibilityCalls[0]++;
                        return true;
                    }
                });
        require(abstractSelected == -1, "abstract persistent rule must defer without a concrete target");
        require(feasibilityCalls[0] == 0, "abstract persistent rule must not scan VM candidates");
        Map<?, ?> abstractDispatch = (Map<?, ?>) abstractBridge.getDecisionPlaneStats().get("lastPersistentDispatch");
        require("persistent_rule_requires_explicit_target_identity".equals(abstractDispatch.get("reason")),
                "identity-only rejection reason must be explicit");

        RlDecisionBridge partialBridge = new RlDecisionBridge("persistent-partial-resource");
        PersistentExecutionConfiguration partialConfiguration = new PersistentExecutionConfiguration();
        partialConfiguration.configId = "cfg-partial";
        partialConfiguration.version = 1L;
        Map<String, Object> partialAssignment = new LinkedHashMap<String, Object>();
        partialAssignment.put("targetVmIndex", 0);
        partialAssignment.put("bindingMode", "native_scheduler_bound");
        partialAssignment.put("cpuShare", 0.5);
        partialConfiguration.assignments.put("42", partialAssignment);
        partialBridge.setPersistentConfiguration(partialConfiguration);
        int partialSelected = partialBridge.resolvePersistentVm(
                null, new String[0], task, vms,
                new RlDecisionBridge.FeasibilityChecker() {
                    @Override
                    public boolean isFeasible(String[] architecture, Task candidate, Vm candidateVm) {
                        return true;
                    }
                });
        require(partialSelected == -1, "partial native persistent profile must be rejected");
        Map<?, ?> partialDispatch = (Map<?, ?>) partialBridge.getDecisionPlaneStats().get("lastPersistentDispatch");
        require("partial_native_resource_profile_requires_explicit_dimensions".equals(partialDispatch.get("reason")),
                "partial native persistent profile rejection reason must be explicit");

        RlResourceProfile invalidProfile = RlResourceProfile.fromAction(
                actionWithCpu(Double.NaN), RlResourceBindingMode.native_scheduler_bound);
        boolean invalidRejected = false;
        try {
            RlNativeResourceBindingManager.bindTask(task, vm, 0, invalidProfile, 0.0);
        } catch (IllegalArgumentException expected) {
            invalidRejected = true;
        }
        require(invalidRejected, "native binding must reject non-finite resource values");
        System.out.println("PersistentNativeBindingContractTest OK");
    }

    private static RlAction actionWithCpu(double cpuShare) {
        RlAction action = new RlAction();
        action.cpuShare = cpuShare;
        action.bandwidthShare = 1.0;
        action.txPowerRatio = 1.0;
        return action;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
