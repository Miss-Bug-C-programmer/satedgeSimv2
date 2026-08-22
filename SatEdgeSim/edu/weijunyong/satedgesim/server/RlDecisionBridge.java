package edu.weijunyong.satedgesim.server;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.cloudbus.cloudsim.vms.Vm;

import edu.weijunyong.satedgesim.DataCentersManager.DataCenter;
import edu.weijunyong.satedgesim.ScenarioManager.simulationParameters;
import edu.weijunyong.satedgesim.SimulationManager.SimulationManager;
import edu.weijunyong.satedgesim.TasksGenerator.Task;
import edu.weijunyong.satedgesim.TasksOrchestration.Orchestrator;

/**
 * Blocking bridge between the SatEdgeSim orchestration point and an external RL
 * controller. The CloudSim simulation thread calls requestDecision(...), blocks,
 * and is released by the HTTP /step endpoint.
 */
public class RlDecisionBridge {
    private final Object lock = new Object();
    private final String sessionId;

    private boolean closed = false;
    private boolean finished = false;
    private long decisionSequence = 0L;
    private RlState currentState;
    private RlAction pendingAction;
    private Resolution pendingResolution;
    private List<Vm> currentVmList;
    private SimulationManager currentSimulationManager;
    private ExecutionReceipt lastExecutionReceipt;
    private RlCompletionReceipt lastCompletionReceipt;
    private final Map<Long, ExecutionReceipt> receiptCache = new LinkedHashMap<Long, ExecutionReceipt>();
    private final Map<Long, RlDecisionResult> decisionResultByTaskId = new LinkedHashMap<Long, RlDecisionResult>();
    private final Map<Long, RlCompletionReceipt> completionReceiptByTaskId = new LinkedHashMap<Long, RlCompletionReceipt>();
    private String lastMessage = "session created";
    private Map<String, Object> metrics = new LinkedHashMap<String, Object>();
    private Map<String, Object> lastDecision = new LinkedHashMap<String, Object>();
    private long numApplyActionCalls = 0L;
    private long numAccepted = 0L;
    private long numRejected = 0L;
    private long numTimeoutSuspected = 0L;
    private double totalServerProcessingMs = 0.0;
    private double maxServerProcessingMs = 0.0;
    private final Map<String, Long> fallbackReasonCounts = new LinkedHashMap<String, Long>();
    private long fullStateBuilderInvocations = 0L;
    private long candidateEvaluations = 0L;
    private ExecutionConfiguration persistentConfiguration;
    private long persistentDispatchCount = 0L;
    private Map<String, Object> lastPersistentDispatch = new LinkedHashMap<String, Object>();
    private Task currentTask;
    private String[] currentArchitecture;
    private List<List<Integer>> currentOrchestrationHistory;
    private FeasibilityChecker currentChecker;
    private long scopedPlannerBuildInvocations = 0L;
    private long scopedPlannerCandidateEvaluations = 0L;

    public RlDecisionBridge(String sessionId) {
        this.sessionId = sessionId;
    }

    public void setPersistentConfiguration(ExecutionConfiguration configuration) {
        synchronized (lock) {
            this.persistentConfiguration = configuration;
            lock.notifyAll();
        }
    }

    /** Resolve a reusable rule before requesting a new RL decision. */
    public int resolvePersistentVm(
            SimulationManager simulationManager,
            String[] architecture,
            Task task,
            List<Vm> vmList,
            FeasibilityChecker checker) {
        synchronized (lock) {
            if (persistentConfiguration == null || task == null || vmList == null) {
                return -1;
            }
            double simulationTime = simulationManager == null || simulationManager.getSimulation() == null
                    ? 0.0 : simulationManager.getSimulation().clock();
            if (persistentConfiguration.isExpired(simulationTime)) {
                lastPersistentDispatch = persistentDispatchFailure(task, -1, "persistent_configuration_expired");
                return -1;
            }
            Map<String, Object> taskContext = new LinkedHashMap<String, Object>();
            taskContext.put("taskId", task.getId());
            taskContext.put("applicationId", task.getApplicationID());
            if (task.getEdgeDevice() != null) {
                taskContext.put("sourceId", task.getEdgeDevice().getDeviceID());
                taskContext.put("sourceType", String.valueOf(task.getEdgeDevice().getType()));
            }
            Object materialized = persistentConfiguration.materialize(taskContext);
            if (!(materialized instanceof Map)) {
                return -1;
            }
            Map<?, ?> rule = (Map<?, ?>) materialized;
            int selected = number(rule.get("targetVmIndex"), number(rule.get("vmIndex"), -1));
            long selectedVmId = numberLong(rule.get("selectedVmId"), numberLong(rule.get("targetVmId"), numberLong(rule.get("vmId"), -1L)));
            if (selected < 0 && selectedVmId >= 0) {
                for (int i = 0; i < vmList.size(); i++) {
                    if (vmList.get(i).getId() == selectedVmId) {
                        selected = i;
                        break;
                    }
                }
            }
            if (selected < 0) {
                String tier = rule.get("logicalTier") == null ? null : String.valueOf(rule.get("logicalTier"));
                int abstractAction = number(rule.get("abstractAction"), -1);
                for (int i = 0; i < vmList.size(); i++) {
                    if (!checker.isFeasible(architecture, task, vmList.get(i))) continue;
                    Orchestrator.FeasibilityInfo info = Orchestrator.evaluateOffloading(
                            simulationManager, task, vmList.get(i), new String[0], null, i);
                    if ((abstractAction >= 0 && info.abstractAction == abstractAction)
                            || (tier != null && tier.equalsIgnoreCase(info.logicalTier))) {
                        selected = i;
                        break;
                    }
                }
            }
            if (selected < 0 || selected >= vmList.size() || !checker.isFeasible(architecture, task, vmList.get(selected))) {
                lastPersistentDispatch = new LinkedHashMap<String, Object>();
                lastPersistentDispatch.put("accepted", false);
                lastPersistentDispatch.put("reason", "persistent_rule_target_unavailable");
                return -1;
            }
            RlAction persistentAction = persistentActionFromRule(rule, task, selected, vmList.get(selected));
            RlResourceBindingMode bindingMode = persistentBindingMode(rule);
            RlResourceProfile resourceProfile = RlResourceProfile.fromAction(persistentAction, bindingMode);
            RlNativeResourceBindingManager.BindingSnapshot nativeBinding = RlNativeResourceBindingManager.BindingSnapshot.notRequested();
            if (resourceProfile.nativeSchedulerBound()) {
                try {
                    nativeBinding = RlNativeResourceBindingManager.bindTask(
                            task, vmList.get(selected), selected, resourceProfile, simulationTime);
                } catch (RuntimeException error) {
                    lastPersistentDispatch = persistentDispatchFailure(
                            task, selected, "persistent_native_binding_failed:" + error.getClass().getSimpleName());
                    return -1;
                }
                if (nativeBinding == null || !nativeBinding.nativeBindingApplied) {
                    lastPersistentDispatch = persistentDispatchFailure(
                            task, selected, "persistent_native_binding_not_applied");
                    return -1;
                }
            }
            persistentDispatchCount += 1L;
            lastPersistentDispatch = new LinkedHashMap<String, Object>();
            lastPersistentDispatch.put("accepted", true);
            lastPersistentDispatch.put("configId", persistentConfiguration.configId);
            lastPersistentDispatch.put("configVersion", persistentConfiguration.version);
            lastPersistentDispatch.put("taskId", task.getId());
            lastPersistentDispatch.put("selectedVmIndex", selected);
            lastPersistentDispatch.put("selectedVmId", vmList.get(selected).getId());
            lastPersistentDispatch.put("dispatchSource", "persistent_execution_rule");
            lastPersistentDispatch.put("bindingMode", resourceProfile.bindingMode.toString());
            lastPersistentDispatch.put("nativeBindingRequested", resourceProfile.nativeSchedulerBound());
            lastPersistentDispatch.put("nativeBindingApplied", nativeBinding.nativeBindingApplied);
            lastPersistentDispatch.put("resourceProfile", resourceProfile.toMap());
            lastPersistentDispatch.put("nativeBinding", nativeBinding.requested ? nativeBinding.toMap() : null);
            long persistentDecisionId = nextDecisionId();
            ExecutionReceipt schedulingReceipt = persistentSchedulingReceipt(
                    persistentDecisionId, task, selected, vmList.get(selected), resourceProfile, nativeBinding, simulationManager);
            lastPersistentDispatch.put("executionReceipt", schedulingReceipt.toMap());
            lastExecutionReceipt = schedulingReceipt;
            cacheReceipt(schedulingReceipt);
            lastDecision = schedulingReceipt.toMap();
            RlDecisionResult decisionResult = new RlDecisionResult();
            decisionResult.taskId = task.getId();
            decisionResult.vmIndex = selected;
            decisionResult.resourceProfile = resourceProfile;
            decisionResult.nativeBinding = nativeBinding;
            decisionResult.schedulingReceipt = schedulingReceipt;
            decisionResult.decisionTimestamp = schedulingReceipt.simulationTime;
            decisionResultByTaskId.put(Long.valueOf(task.getId()), decisionResult);
            return selected;
        }
    }

    private static Map<String, Object> persistentDispatchFailure(Task task, int selected, String reason) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("accepted", false);
        result.put("taskId", task == null ? -1L : task.getId());
        result.put("selectedVmIndex", selected);
        result.put("nativeBindingRequested", true);
        result.put("nativeBindingApplied", false);
        result.put("reason", reason);
        return result;
    }

    private static RlAction persistentActionFromRule(Map<?, ?> rule, Task task, int selected, Vm vm) {
        RlAction action = new RlAction();
        action.taskId = task == null ? -1L : task.getId();
        action.targetVmIndex = selected;
        action.targetVmId = vm == null ? -1L : vm.getId();
        action.selectedVmId = action.targetVmId;
        action.cpuShare = numberDouble(rule.get("cpuShare"), numberDouble(rule.get("cpu_share"), 1.0));
        action.bandwidthShare = numberDouble(rule.get("bandwidthShare"), numberDouble(rule.get("bandwidth_share"), 1.0));
        action.txPowerRatio = numberDouble(rule.get("txPowerRatio"), numberDouble(rule.get("tx_power_ratio"), 1.0));
        Object mode = rule.get("bindingMode");
        if (mode == null) mode = rule.get("continuous_resource_binding_mode");
        if (mode == null) mode = rule.get("continuousResourceBindingMode");
        if (mode != null) action.extra.put("bindingMode", String.valueOf(mode));
        action.policyUpperActionName = "persistent_rule";
        action.abstractActionName = "persistent_rule";
        return action;
    }

    private static RlResourceBindingMode persistentBindingMode(Map<?, ?> rule) {
        Object raw = rule.get("bindingMode");
        if (raw == null) raw = rule.get("continuous_resource_binding_mode");
        if (raw == null) raw = rule.get("continuousResourceBindingMode");
        if (raw == null) return RlResourceBindingMode.candidate_only;
        String value = String.valueOf(raw).trim().toLowerCase();
        if ("native_scheduler_bound".equals(value)) return RlResourceBindingMode.native_scheduler_bound;
        if ("resource_aware_estimator_bound".equals(value)) return RlResourceBindingMode.resource_aware_estimator_bound;
        return RlResourceBindingMode.candidate_only;
    }

    private static double numberDouble(Object value, double fallback) {
        return value instanceof Number ? ((Number) value).doubleValue() : fallback;
    }

    private static ExecutionReceipt persistentSchedulingReceipt(
            long decisionId, Task task, int selected, Vm vm, RlResourceProfile profile,
            RlNativeResourceBindingManager.BindingSnapshot nativeBinding,
            SimulationManager simulationManager) {
        ExecutionReceipt receipt = new ExecutionReceipt();
        receipt.receiptStage = "scheduling";
        receipt.accepted = true;
        receipt.actionAccepted = true;
        receipt.executionScheduled = true;
        receipt.taskCompleted = null;
        receipt.taskSucceeded = null;
        receipt.decisionId = decisionId;
        receipt.taskId = task == null ? -1L : task.getId();
        receipt.simulationTime = simulationManager == null || simulationManager.getSimulation() == null
                ? 0.0 : simulationManager.getSimulation().clock();
        receipt.selectedVmIndex = selected;
        receipt.selectedVmId = vm == null ? -1L : vm.getId();
        receipt.executedVmIndex = selected;
        receipt.executedVmId = receipt.selectedVmId;
        receipt.resourceProfile = profile;
        receipt.continuousResourceBindingMode = profile.bindingMode.toString();
        receipt.continuousResourceApplied = profile.continuousApplied;
        receipt.nativeSchedulerBound = profile.nativeSchedulerBound();
        receipt.estimatorBound = profile.estimatorBound();
        receipt.nativeBindingApplied = nativeBinding != null && nativeBinding.nativeBindingApplied;
        receipt.nativeCpuMipsBound = receipt.nativeBindingApplied;
        receipt.nativeNetworkBandwidthBound = receipt.nativeBindingApplied;
        receipt.nativeTxPowerBound = receipt.nativeBindingApplied;
        receipt.nativeBinding = nativeBinding != null && nativeBinding.requested ? nativeBinding.toMap() : null;
        if (nativeBinding != null && nativeBinding.requested) {
            receipt.nativeBaseMips = nativeBinding.baseMips;
            receipt.nativeAppliedMips = nativeBinding.appliedMips;
            receipt.nativeCpuShare = nativeBinding.cpuShare;
            receipt.nativeBandwidthShare = nativeBinding.bandwidthShare;
            receipt.nativeTxPowerRatio = nativeBinding.txPowerRatio;
        }
        receipt.failureReason = "pending_task_completion";
        receipt.success = null;
        receipt.message = "persistent reusable rule selected and scheduled";
        return receipt;
    }

    public int requestDecision(
            SimulationManager simulationManager,
            String[] architecture,
            Task task,
            List<Vm> vmList,
            List<List<Integer>> orchestrationHistory,
            FeasibilityChecker checker) {
        synchronized (lock) {
            if (closed) {
                return fallbackVm(architecture, task, vmList, checker);
            }
            while (!closed && currentState != null && pendingAction == null) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return fallbackVm(architecture, task, vmList, checker);
                }
            }
            if (closed) {
                return fallbackVm(architecture, task, vmList, checker);
            }
            currentTask = task;
            currentArchitecture = architecture;
            currentOrchestrationHistory = orchestrationHistory;
            currentChecker = checker;
            currentState = RlStateBuilder.build(
                    sessionId,
                    "WAITING_FOR_ACTION",
                    nextDecisionId(),
                    simulationManager,
                    architecture,
                    task,
                    vmList,
                    orchestrationHistory,
                    checker,
                    metrics,
                    "waiting for /apply_action");
            fullStateBuilderInvocations += 1L;
            candidateEvaluations += vmList == null ? 0L : vmList.size();
            currentVmList = vmList;
            currentSimulationManager = simulationManager;
            pendingAction = null;
            pendingResolution = null;
            System.out.println("[RlDecisionBridge] waiting decisionId=" + currentState.decisionId + " taskId=" + currentState.taskId);
            lock.notifyAll();

            while (!closed && pendingResolution == null) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            if (closed || pendingResolution == null) {
                return fallbackVm(architecture, task, vmList, checker);
            }

            Resolution resolution = pendingResolution;
            Double energyBefore = readEnergyCounter(simulationManager);
            int selected = resolution.targetVmIndex;
            if (!isFeasible(selected, architecture, task, vmList, checker)) {
                selected = -1;
                resolution.fallbackReason = resolution.fallbackReason == null || "".equals(resolution.fallbackReason)
                        ? "infeasible_selected_vm"
                        : resolution.fallbackReason;
                lastMessage = "rejected action targetVmIndex=" + resolution.targetVmIndex + " reason=" + resolution.fallbackReason;
            } else {
                resolution.fallbackReason = resolution.fallbackReason == null || "".equals(resolution.fallbackReason) ? "none" : resolution.fallbackReason;
                lastMessage = "accepted action targetVmIndex=" + selected + " logicalTier=" + resolution.selectedLogicalTier;
            }
            if (selected >= 0 && resolution.resourceProfile != null && resolution.resourceProfile.nativeSchedulerBound()) {
                double simTime = simulationManager == null || simulationManager.getSimulation() == null
                        ? 0.0
                        : simulationManager.getSimulation().clock();
                resolution.nativeBinding = RlNativeResourceBindingManager.bindTask(
                        task,
                        vmList.get(selected),
                        selected,
                        resolution.resourceProfile,
                        simTime);
            }
            lastExecutionReceipt = resolution.toAcceptedReceipt(currentState, energyBefore, readEnergyCounter(simulationManager));
            cacheReceipt(lastExecutionReceipt);
            cacheDecisionResult(resolution, lastExecutionReceipt, simulationManager);
            lastDecision = lastExecutionReceipt.toMap();
            System.out.println("[RlDecisionBridge] resolved decisionId=" + lastExecutionReceipt.decisionId
                    + " taskId=" + lastExecutionReceipt.taskId
                    + " policy=" + lastExecutionReceipt.policyUpperAction
                    + " executed=" + lastExecutionReceipt.executedAbstractAction
                    + " fallback=" + lastExecutionReceipt.fallbackReason);

            currentState = null;
            pendingAction = null;
            pendingResolution = null;
            currentVmList = null;
            currentSimulationManager = null;
            currentTask = null;
            currentArchitecture = null;
            currentOrchestrationHistory = null;
            currentChecker = null;
            lock.notifyAll();
            return selected;
        }
    }

    public RlState getState() {
        synchronized (lock) {
            if (currentState != null) {
                currentState.message = lastMessage;
                currentState.metrics = metrics;
                currentState.lastDecision = lastDecision;
                return currentState;
            }
            RlState state = new RlState();
            state.sessionId = sessionId;
            state.status = finished ? "FINISHED" : (closed ? "CLOSED" : "RUNNING");
            state.message = lastMessage;
            state.metrics = metrics;
            state.lastDecision = lastDecision;
            return state;
        }
    }

    public ExecutionReceipt submitAction(RlAction action) {
        synchronized (lock) {
            numApplyActionCalls += 1L;
            if (closed) {
                return rejectedReceipt(currentState, action, "session_closed", "session is closed");
            }
            if (currentState == null) {
                return rejectedReceipt(null, action, "no_pending_decision", "no pending decision; call /get_state until status=WAITING_FOR_ACTION");
            }
            long expectedDecisionId = currentState.decisionId >= 0 ? currentState.decisionId : currentState.requestId;
            long submittedDecisionId = action.decisionId >= 0 ? action.decisionId : action.requestId;
            if (submittedDecisionId >= 0 && submittedDecisionId != expectedDecisionId) {
                return rejectedReceipt(currentState, action, "stale_decision_id",
                        "decisionId mismatch: expected " + expectedDecisionId + " but got " + submittedDecisionId);
            }
            if (currentState.taskId >= 0 && action.taskId >= 0 && action.taskId != currentState.taskId) {
                return rejectedReceipt(currentState, action, "task_id_mismatch",
                        "taskId mismatch: expected " + currentState.taskId + " but got " + action.taskId);
            }
            Resolution resolution = resolveAction(action, currentState, currentVmList);
            if (!resolution.accepted) {
                System.out.println("[RlDecisionBridge] rejected decisionId=" + expectedDecisionId
                        + " taskId=" + currentState.taskId
                        + " reason=" + resolution.fallbackReason);
                return resolution.toRejectedReceipt(currentState, readEnergyCounter(currentSimulationManager));
            }
            System.out.println("[RlDecisionBridge] submit decisionId=" + expectedDecisionId
                    + " taskId=" + currentState.taskId
                    + " policy=" + resolution.policyIntendedAction
                    + " targetVmIndex=" + resolution.targetVmIndex);
            this.pendingAction = action;
            this.pendingResolution = resolution;
            lock.notifyAll();
            while (!closed && cachedReceipt(expectedDecisionId) == null) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return rejectedReceipt(currentState, action, "interrupted", "submitAction interrupted");
                }
            }
            ExecutionReceipt completedReceipt = cachedReceipt(expectedDecisionId);
            if (completedReceipt != null) {
                return completedReceipt;
            }
            return rejectedReceipt(currentState, action, "receipt_unavailable", "execution receipt unavailable");
        }
    }

    public boolean waitForDecisionOrFinish(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (lock) {
            while (!closed && !finished && currentState == null && System.currentTimeMillis() < deadline) {
                try {
                    lock.wait(Math.max(1L, deadline - System.currentTimeMillis()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return currentState != null || finished || closed;
        }
    }

    public void updateMetrics(Map<String, Object> metrics) {
        synchronized (lock) {
            this.metrics = metrics;
            lock.notifyAll();
        }
    }

    public void markFinished(Map<String, Object> metrics) {
        synchronized (lock) {
            this.metrics = metrics;
            this.finished = true;
            this.currentState = null;
            this.pendingResolution = null;
            this.lastMessage = "simulation finished";
            lock.notifyAll();
        }
    }

    public void markFailed(Throwable t) {
        synchronized (lock) {
            this.finished = true;
            this.currentState = null;
            this.pendingResolution = null;
            this.lastMessage = "simulation failed: " + t.getMessage();
            lock.notifyAll();
        }
    }

    public void close() {
        synchronized (lock) {
            closed = true;
            pendingResolution = null;
            lastMessage = "session closed";
            lock.notifyAll();
        }
    }

    public boolean isClosed() {
        synchronized (lock) {
            return closed;
        }
    }

    public boolean isFinished() {
        synchronized (lock) {
            return finished;
        }
    }

    public RlState getCurrentStateSnapshot() {
        synchronized (lock) {
            return currentState;
        }
    }

    /**
     * Scalar-only snapshot for the cheap monitor.  It intentionally does not
     * expose or traverse candidateVms/datacenters.
     */
    public Map<String, Object> getCurrentDecisionScalars() {
        synchronized (lock) {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("status", currentState == null ? (finished ? "FINISHED" : (closed ? "CLOSED" : "RUNNING")) : currentState.status);
            out.put("decisionId", currentState == null ? -1L : currentState.decisionId);
            out.put("taskId", currentState == null ? -1L : currentState.taskId);
            out.put("sourceDeviceId", currentState == null ? -1 : currentState.sourceDeviceId);
            out.put("simulationTimeSec", currentState == null ? 0.0 : currentState.simulationTime);
            return out;
        }
    }

    public Map<String, Object> getDecisionPlaneStats() {
        synchronized (lock) {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("fullStateBuilderInvocations", fullStateBuilderInvocations);
            out.put("candidateEvaluations", candidateEvaluations);
            out.put("scopedPlannerBuildInvocations", scopedPlannerBuildInvocations);
            out.put("scopedPlannerCandidateEvaluations", scopedPlannerCandidateEvaluations);
            out.put("cheapMonitorFullStateBuilderInvoked", false);
            out.put("cheapMonitorCandidateEvaluations", 0L);
            out.put("containsFutureStochasticState", false);
            out.put("lastDecisionId", currentState == null ? -1L : currentState.decisionId);
            out.put("lastTaskId", currentState == null ? -1L : currentState.taskId);
            out.put("persistentDispatchCount", persistentDispatchCount);
            out.put("lastPersistentDispatch", new LinkedHashMap<String, Object>(lastPersistentDispatch));
            return out;
        }
    }

    /** Acquire a planner projection before serialising the REST response. */
    public RlState buildScopedPlannerState(Map<String, Object> scope, int maxCandidates) {
        synchronized (lock) {
            if (currentState == null || currentTask == null || currentVmList == null || currentSimulationManager == null) {
                return getState();
            }
            List<Vm> selected = new ArrayList<Vm>();
            List<Integer> originalIndices = new ArrayList<Integer>();
            int limit = maxCandidates < 0 ? Integer.MAX_VALUE : maxCandidates;
            for (int i = 0; i < currentVmList.size() && selected.size() < limit; i++) {
                Vm vm = currentVmList.get(i);
                if (!scopeMatches(scope, currentTask, vm)) continue;
                selected.add(vm);
                originalIndices.add(i);
            }
            RlState scoped = RlStateBuilder.buildScoped(
                    sessionId,
                    "WAITING_FOR_ACTION",
                    currentState.decisionId,
                    currentSimulationManager,
                    currentArchitecture,
                    currentTask,
                    selected,
                    currentOrchestrationHistory,
                    currentChecker,
                    metrics,
                    "scoped planner acquisition");
            for (int i = 0; i < scoped.candidateVms.size() && i < originalIndices.size(); i++) {
                scoped.candidateVms.get(i).vmIndex = originalIndices.get(i);
            }
            scopedPlannerBuildInvocations += 1L;
            scopedPlannerCandidateEvaluations += selected.size();
            return scoped;
        }
    }

    private static boolean scopeMatches(Map<String, Object> scope, Task task, Vm vm) {
        if (scope == null || scope.isEmpty()) return true;
        if (has(scope, "task_ids", "taskIds")) {
            if (contains(scope, "task_ids", "taskIds", task.getId())) return true;
            return false;
        }
        if (has(scope, "source_ids", "sourceIds")) {
            int sourceId = task.getEdgeDevice() == null ? -1 : task.getEdgeDevice().getDeviceID();
            if (contains(scope, "source_ids", "sourceIds", sourceId)) return true;
            return false;
        }
        if (has(scope, "node_ids", "nodeIds") || has(scope, "resource_keys", "resourceKeys")) {
            long vmId = vm.getId();
            long deviceId = -1L;
            if (vm.getHost() != null && vm.getHost().getDatacenter() instanceof DataCenter) {
                deviceId = ((DataCenter) vm.getHost().getDatacenter()).getDeviceID();
            }
            return contains(scope, "node_ids", "nodeIds", vmId)
                    || contains(scope, "node_ids", "nodeIds", deviceId)
                    || contains(scope, "resource_keys", "resourceKeys", vmId);
        }
        return false;
    }

    private static boolean has(Map<String, Object> map, String first, String second) {
        return map.containsKey(first) || map.containsKey(second);
    }

    private static boolean contains(Map<String, Object> map, String first, String second, Object value) {
        Object raw = map.containsKey(first) ? map.get(first) : map.get(second);
        if (!(raw instanceof List)) return false;
        for (Object item : (List<?>) raw) {
            if (String.valueOf(item).equals(String.valueOf(value))) return true;
        }
        return false;
    }

    public int getCurrentCandidateCount() {
        synchronized (lock) {
            return currentVmList == null ? 0 : currentVmList.size();
        }
    }

    public ExecutionReceipt getLastExecutionReceipt() {
        synchronized (lock) {
            return lastExecutionReceipt;
        }
    }

    public RlCompletionReceipt getLastCompletionReceipt() {
        synchronized (lock) {
            return lastCompletionReceipt;
        }
    }

    public List<Map<String, Object>> getCompletionReceiptMaps() {
        synchronized (lock) {
            List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
            for (RlCompletionReceipt receipt : completionReceiptByTaskId.values()) {
                out.add(receipt.toMap());
            }
            return out;
        }
    }

    public Map<String, Object> getCurrentDecisionDebug() {
        synchronized (lock) {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            if (currentState == null) {
                out.put("status", "NO_PENDING_DECISION");
                return out;
            }
            int[] counts = new int[] {0, 0, 0, 0};
            for (RlState.VmView vm : currentState.candidateVms) {
                if (vm != null && vm.isFeasible && vm.abstractAction >= 0 && vm.abstractAction < counts.length) {
                    counts[vm.abstractAction] += 1;
                }
            }
            out.put("decisionId", currentState.decisionId);
            out.put("taskId", currentState.taskId);
            out.put("sourceLeoId", currentState.sourceLeoId);
            out.put("abstractActionMask", currentState.abstractActionMask);
            out.put("abstractActionMaskVisible", currentState.abstractActionMaskVisible);
            out.put("abstractActionMaskMobilitySafe", currentState.abstractActionMaskMobilitySafe);
            out.put("abstractActionMaskCompletionSafe", currentState.abstractActionMaskCompletionSafe);
            out.put("actionMaskMode", currentState.actionMaskMode);
            out.put("minLinkSurvivalMarginSec", currentState.minLinkSurvivalMarginSec);
            out.put("localCandidateCount", counts[Orchestrator.ACTION_LOCAL]);
            out.put("neighborCandidateCount", counts[Orchestrator.ACTION_NEIGHBOR]);
            out.put("geoCandidateCount", counts[Orchestrator.ACTION_GEO]);
            out.put("groundCandidateCount", counts[Orchestrator.ACTION_GROUND]);
            return out;
        }
    }

    public Map<String, Object> getReceiptStats() {
        synchronized (lock) {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("numApplyActionCalls", numApplyActionCalls);
            out.put("numAccepted", numAccepted);
            out.put("numRejected", numRejected);
            out.put("numTimeoutSuspected", numTimeoutSuspected);
            out.put("meanServerProcessingMs", numApplyActionCalls == 0 ? 0.0 : totalServerProcessingMs / Math.max(1L, numAccepted + numRejected));
            out.put("maxServerProcessingMs", maxServerProcessingMs);
            out.put("fallbackReasonDistribution", new LinkedHashMap<String, Long>(fallbackReasonCounts));
            out.put("completionReceiptAvailable", lastCompletionReceipt != null);
            if (lastCompletionReceipt != null) {
                out.put("lastCompletionReceipt", lastCompletionReceipt.toMap());
            }
            out.put("completionReceipts", getCompletionReceiptMaps());
            return out;
        }
    }

    public RlCompletionReceipt recordCompletion(Task task, SimulationManager simulationManager) {
        synchronized (lock) {
            RlCompletionReceipt receipt = new RlCompletionReceipt();
            receipt.receiptStage = "completion";
            receipt.taskId = task == null ? -1L : task.getId();
            RlDecisionResult decision = decisionResultByTaskId.get(Long.valueOf(receipt.taskId));
            receipt.decisionId = decision != null && decision.schedulingReceipt != null ? decision.schedulingReceipt.decisionId : -1L;
            receipt.taskScheduled = decision != null && decision.schedulingReceipt != null && decision.schedulingReceipt.executionScheduled;
            receipt.actionAccepted = decision != null && decision.schedulingReceipt != null && decision.schedulingReceipt.actionAccepted;
            receipt.executionScheduled = receipt.taskScheduled;
            receipt.taskCompleted = true;
            receipt.taskSucceeded = task != null && task.getStatus() != null && "SUCCESS".equalsIgnoreCase(task.getStatus().name());
            double simClock = task == null || task.getSimulation() == null ? 0.0 : task.getSimulation().clock();
            double finish = task == null ? 0.0 : task.getTaskFinishTime();
            if (finish <= 0.0 && simClock > 0.0) {
                finish = simClock;
            }
            double start = task == null ? 0.0 : task.getTime();
            receipt.finalDelay = Math.max(0.0, finish - start);
            Double finalEnergyWh = readEnergyCounter(simulationManager);
            receipt.finalEnergy = finalEnergyWh;
            receipt.simlogFinalEnergyWh = finalEnergyWh;
            receipt.estimatorExpectedEnergyJ = decision != null && decision.schedulingReceipt != null
                    ? Double.valueOf(decision.schedulingReceipt.estimatorExpectedEnergyJ)
                    : null;
            receipt.energySource = "simlog_final_wh";
            receipt.energyUnit = "Wh";
            receipt.energySourceAvailable = finalEnergyWh != null;
            receipt.energyUnavailableReason = finalEnergyWh == null ? energyUnavailableReason(simulationManager) : "none";
            receipt.failureReason = receipt.taskSucceeded ? "none" : (task == null || task.getFailureReason() == null ? "unknown_failure" : task.getFailureReason().toString());
            receipt.contactInterrupted = task != null && task.isContactInterrupted();
            receipt.contactInterruptionTime = receipt.contactInterrupted
                    ? Double.valueOf(task.getContactInterruptionTime()) : null;
            receipt.remainingBytes = receipt.contactInterrupted
                    ? Double.valueOf(task.getContactRemainingBytes()) : null;
            receipt.contactFailureReason = task == null ? "none" : task.getContactFailureReason();
            receipt.completionTimestamp = simClock;
            receipt.simulationTime = simClock;
            receipt.bindingMode = decision == null || decision.resourceProfile == null
                    ? RlResourceBindingMode.candidate_only
                    : decision.resourceProfile.bindingMode;
            RlNativeResourceBindingManager.BindingSnapshot releaseSnapshot = RlNativeResourceBindingManager.releaseTask(task, simulationManager);
            receipt.nativeBindingReleased = releaseSnapshot != null && releaseSnapshot.released;
            receipt.nativeBindingRelease = releaseSnapshot == null ? null : releaseSnapshot.toMap();
            lastCompletionReceipt = receipt;
            completionReceiptByTaskId.put(Long.valueOf(receipt.taskId), receipt);
            while (completionReceiptByTaskId.size() > 128) {
                Long eldest = completionReceiptByTaskId.keySet().iterator().next();
                completionReceiptByTaskId.remove(eldest);
            }
            Map<String, Object> updated = new LinkedHashMap<String, Object>();
            if (lastDecision != null) {
                updated.putAll(lastDecision);
            }
            updated.put("completionReceipt", receipt.toMap());
            lastDecision = updated;
            lock.notifyAll();
            return receipt;
        }
    }

    public void recordDeliveredReceipt(ExecutionReceipt receipt) {
        synchronized (lock) {
            if (receipt == null) {
                return;
            }
            if (receipt.accepted) {
                numAccepted += 1L;
            } else {
                numRejected += 1L;
            }
            totalServerProcessingMs += Math.max(0.0, receipt.serverProcessingMs);
            maxServerProcessingMs = Math.max(maxServerProcessingMs, receipt.serverProcessingMs);
            incrementFallbackReason(receipt.fallbackReason);
        }
    }

    public void recordTimeoutSuspected() {
        synchronized (lock) {
            numTimeoutSuspected += 1L;
        }
    }

    private long nextDecisionId() {
        decisionSequence += 1L;
        return decisionSequence;
    }

    private Resolution resolveAction(RlAction action, RlState state, List<Vm> vmList) {
        Resolution resolution = new Resolution();
        resolution.decisionId = state == null ? -1L : state.decisionId;
        resolution.taskId = state == null ? -1L : state.taskId;
        resolution.accepted = false;
        resolution.policyIntendedAction = policyUpperAction(action);
        resolution.policyIntendedActionName = policyUpperActionName(action);
        resolution.resourceProfile = RlResourceProfile.fromAction(action, requestedBindingMode(action));
        if (action.targetVmIndex >= 0) {
            resolution.targetVmIndex = action.targetVmIndex;
            populateResolvedCandidate(resolution, state, action.targetVmIndex);
            applyResourceEstimate(resolution, state);
            return validateResolvedCandidate(resolution, action, state);
        }
        long selectedVmId = action.selectedVmId >= 0 ? action.selectedVmId : action.targetVmId;
        if (selectedVmId >= 0) {
            for (int i = 0; i < vmList.size(); i++) {
                if (vmList.get(i).getId() == selectedVmId) {
                    resolution.targetVmIndex = i;
                    populateResolvedCandidate(resolution, state, i);
                    applyResourceEstimate(resolution, state);
                    return validateResolvedCandidate(resolution, action, state);
                }
            }
            resolution.fallbackReason = "unknown_target_vm_id";
            return resolution;
        }
        int intendedAction = policyUpperAction(action);
        if (intendedAction >= 0 && state != null) {
            int bestIndex = -1;
            double bestDelay = Double.POSITIVE_INFINITY;
            for (int i = 0; i < state.candidateVms.size(); i++) {
                RlState.VmView vm = state.candidateVms.get(i);
                if (!vm.isFeasible || vm.abstractAction != intendedAction) {
                    continue;
                }
                double estimatedDelay = adjustedCandidateDelay(vm, resolution.resourceProfile);
                if (bestIndex < 0 || estimatedDelay < bestDelay) {
                    bestDelay = estimatedDelay;
                    bestIndex = i;
                }
            }
            resolution.targetVmIndex = bestIndex;
            if (bestIndex >= 0) {
                populateResolvedCandidate(resolution, state, bestIndex);
                applyResourceEstimate(resolution, state);
                resolution.accepted = true;
            } else {
                resolution.fallbackReason = actionVisible(state, intendedAction) ? "no_feasible_candidate_for_abstract_action" : "action_not_visible";
            }
            return resolution;
        }
        resolution.targetVmIndex = -1;
        if (resolution.fallbackReason == null || "".equals(resolution.fallbackReason)) {
            resolution.fallbackReason = "no_action_target";
        }
        return resolution;
    }

    private boolean isFeasible(int index, String[] architecture, Task task, List<Vm> vmList, FeasibilityChecker checker) {
        return index >= 0 && index < vmList.size() && checker.isFeasible(architecture, task, vmList.get(index));
    }

    private int fallbackVm(String[] architecture, Task task, List<Vm> vmList, FeasibilityChecker checker) {
        for (int i = 0; i < vmList.size(); i++) {
            if (checker.isFeasible(architecture, task, vmList.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static int number(Object value, int fallback) {
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    private static long numberLong(Object value, long fallback) {
        return value instanceof Number ? ((Number) value).longValue() : fallback;
    }

    public interface FeasibilityChecker {
        boolean isFeasible(String[] architecture, Task task, Vm vm);
    }

    private void populateResolvedCandidate(Resolution resolution, RlState state, int candidateIndex) {
        if (state == null || candidateIndex < 0 || candidateIndex >= state.candidateVms.size()) {
            return;
        }
        RlState.VmView vm = state.candidateVms.get(candidateIndex);
        resolution.selectedVmId = vm.vmId;
        resolution.selectedLogicalTier = vm.logicalTier;
        resolution.selectedAbstractAction = vm.abstractAction;
        resolution.selectedDelay = vm.estimatedTotalDelaySec;
        resolution.selectedQueueLength = vm.estimatedQueueLength;
        resolution.selectedRateMbps = vm.estimatedTransmissionRateMbps;
        resolution.selectedComputeCapacity = vm.estimatedComputeCapacity;
        resolution.selectedPropagationDelaySec = vm.propagationDelaySec;
        resolution.selectedLocalToSource = vm.isLocalToSource;
        resolution.linkAvailableNow = vm.linkAvailableNow;
        resolution.estimatedLinkLifetimeSec = vm.estimatedLinkLifetimeSec;
        resolution.estimatedTaskTransmissionTimeSec = vm.estimatedTaskTransmissionTimeSec;
        resolution.estimatedTaskComputeTimeSec = vm.estimatedTaskComputeTimeSec;
        resolution.estimatedTaskCompletionTimeSec = vm.estimatedTaskCompletionTimeSec;
        resolution.linkSurvivalMarginSec = vm.linkSurvivalMarginSec;
        resolution.linkSurvivalMarginToCompletionSec = vm.linkSurvivalMarginToCompletionSec;
        resolution.handoverRequired = vm.handoverRequired;
        resolution.handoverAvailable = vm.handoverAvailable;
        resolution.mobilityRisk = vm.mobilityRisk;
        resolution.mobilityRiskSource = vm.mobilityRiskSource;
        resolution.selectedVmIndex = candidateIndex;
    }

    private static class Resolution {
        boolean accepted = false;
        long decisionId = -1L;
        long taskId = -1L;
        int targetVmIndex = -1;
        int policyIntendedAction = -1;
        String policyIntendedActionName = "";
        int selectedVmIndex = -1;
        long selectedVmId = -1L;
        String selectedLogicalTier = "";
        int selectedAbstractAction = -1;
        double selectedDelay = 0.0;
        int selectedQueueLength = 0;
        double selectedRateMbps = 0.0;
        double selectedComputeCapacity = 0.0;
        double selectedPropagationDelaySec = 0.0;
        boolean selectedLocalToSource = false;
        RlResourceProfile resourceProfile = RlResourceProfile.fromAction(null, RlResourceBindingMode.candidate_only);
        RlResourceAwareEstimator.Estimate resourceEstimate = new RlResourceAwareEstimator.Estimate();
        RlNativeResourceBindingManager.BindingSnapshot nativeBinding = RlNativeResourceBindingManager.BindingSnapshot.notRequested();
        boolean linkAvailableNow = false;
        double estimatedLinkLifetimeSec = 0.0;
        double estimatedTaskTransmissionTimeSec = 0.0;
        double estimatedTaskComputeTimeSec = 0.0;
        double estimatedTaskCompletionTimeSec = 0.0;
        double linkSurvivalMarginSec = 0.0;
        double linkSurvivalMarginToCompletionSec = 0.0;
        boolean handoverRequired = false;
        boolean handoverAvailable = false;
        double mobilityRisk = 1.0;
        String mobilityRiskSource = "unavailable";
        String fallbackReason = "";

        ExecutionReceipt toAcceptedReceipt(RlState state, Double energyBefore, Double energyAfter) {
            ExecutionReceipt receipt = baseReceipt(state, energyBefore, energyAfter);
            receipt.accepted = true;
            receipt.actionAccepted = true;
            receipt.executionScheduled = true;
            receipt.taskCompleted = null;
            receipt.taskSucceeded = null;
            receipt.selectedVmIndex = selectedVmIndex >= 0 ? selectedVmIndex : targetVmIndex;
            receipt.selectedVmId = selectedVmId;
            receipt.selectedVmLogicalTier = selectedLogicalTier;
            receipt.selectedVmAbstractAction = selectedAbstractAction;
            receipt.executedVmIndex = receipt.selectedVmIndex;
            receipt.executedVmId = selectedVmId;
            receipt.executedLogicalTier = selectedLogicalTier;
            receipt.executedAbstractAction = selectedAbstractAction;
            receipt.intentExecutionMatch = selectedAbstractAction >= 0 && policyIntendedAction == selectedAbstractAction;
            receipt.fallbackReason = fallbackReason == null || "".equals(fallbackReason) ? "none" : fallbackReason;
            receipt.delay = Math.max(0.0, selectedDelay);
            receipt.deadline = state == null || state.task == null ? 0.0 : Math.max(0.0, state.task.maxLatency);
            receipt.queueLength = Math.max(0, selectedQueueLength);
            receipt.estimatedTotalDelaySec = Math.max(0.0, selectedDelay);
            receipt.estimatedQueueLength = Math.max(0.0, selectedQueueLength);
            receipt.estimatedTransmissionRateMbps = Math.max(0.0, selectedRateMbps);
            receipt.estimatedComputeCapacity = Math.max(0.0, selectedComputeCapacity);
            receipt.linkAvailableNow = linkAvailableNow;
            receipt.estimatedLinkLifetimeSec = Math.max(0.0, estimatedLinkLifetimeSec);
            receipt.estimatedTaskTransmissionTimeSec = Math.max(0.0, estimatedTaskTransmissionTimeSec);
            receipt.estimatedTaskComputeTimeSec = Math.max(0.0, estimatedTaskComputeTimeSec);
            receipt.estimatedTaskCompletionTimeSec = Math.max(0.0, estimatedTaskCompletionTimeSec);
            receipt.linkSurvivalMarginSec = linkSurvivalMarginSec;
            receipt.linkSurvivalMarginToCompletionSec = linkSurvivalMarginToCompletionSec;
            receipt.handoverRequired = handoverRequired;
            receipt.handoverAvailable = handoverAvailable;
            receipt.mobilityRisk = Math.max(0.0, Math.min(1.0, mobilityRisk));
            receipt.mobilityRiskSource = mobilityRiskSource == null ? "unavailable" : mobilityRiskSource;
            applyResourceFields(receipt);
            receipt.success = null;
            receipt.failureReason = "pending_task_completion";
            receipt.unknownFailure = false;
            receipt.message = "action accepted and scheduled; task completion is asynchronous";
            return receipt;
        }

        ExecutionReceipt toRejectedReceipt(RlState state, Double energyCounter) {
            ExecutionReceipt receipt = baseReceipt(state, energyCounter, energyCounter);
            receipt.accepted = false;
            receipt.actionAccepted = false;
            receipt.executionScheduled = false;
            receipt.taskCompleted = null;
            receipt.taskSucceeded = null;
            receipt.selectedVmIndex = selectedVmIndex >= 0 ? selectedVmIndex : targetVmIndex;
            receipt.selectedVmId = selectedVmId;
            receipt.selectedVmLogicalTier = selectedLogicalTier;
            receipt.selectedVmAbstractAction = selectedAbstractAction;
            receipt.executedVmIndex = -1;
            receipt.executedVmId = -1L;
            receipt.executedLogicalTier = "";
            receipt.executedAbstractAction = -1;
            receipt.intentExecutionMatch = false;
            receipt.fallbackReason = fallbackReason == null || "".equals(fallbackReason) ? "none" : fallbackReason;
            receipt.delay = 0.0;
            receipt.linkAvailableNow = false;
            receipt.estimatedLinkLifetimeSec = 0.0;
            receipt.estimatedTaskTransmissionTimeSec = 0.0;
            receipt.estimatedTaskComputeTimeSec = 0.0;
            receipt.estimatedTaskCompletionTimeSec = 0.0;
            receipt.linkSurvivalMarginSec = -1.0;
            receipt.linkSurvivalMarginToCompletionSec = -1.0;
            receipt.handoverRequired = false;
            receipt.handoverAvailable = false;
            receipt.mobilityRisk = 1.0;
            receipt.mobilityRiskSource = "unavailable";
            applyResourceFields(receipt);
            receipt.success = null;
            classifyFailureFromFallback(receipt, receipt.fallbackReason);
            receipt.message = receipt.fallbackReason;
            return receipt;
        }

        private ExecutionReceipt baseReceipt(RlState state, Double energyBefore, Double energyAfter) {
            ExecutionReceipt receipt = new ExecutionReceipt();
            receipt.decisionId = decisionId >= 0 ? decisionId : (state == null ? -1L : state.decisionId);
            receipt.taskId = taskId >= 0 ? taskId : (state == null ? -1L : state.taskId);
            receipt.policyUpperAction = policyIntendedAction;
            receipt.policyUpperActionName = policyIntendedActionName;
            receipt.energyRawCounterBefore = energyBefore;
            receipt.energyRawCounterAfter = energyAfter;
            receipt.receiptEnergyBeforeWh = energyBefore;
            receipt.receiptEnergyAfterWh = energyAfter;
            if (energyBefore != null && energyAfter != null) {
                receipt.energyDelta = Double.valueOf(energyAfter.doubleValue() - energyBefore.doubleValue());
                receipt.receiptEnergyDeltaWh = receipt.energyDelta;
                receipt.energySourceAvailable = true;
                receipt.energyUnavailableReason = "none";
            } else {
                receipt.energyDelta = null;
                receipt.receiptEnergyDeltaWh = null;
                receipt.energySourceAvailable = false;
                receipt.energyUnavailableReason = "energy_counter_not_read";
            }
            receipt.energySource = "receipt_delta_wh";
            receipt.energyUnit = "Wh";
            receipt.receiptStage = "scheduling";
            receipt.simulationTime = state == null ? 0.0 : state.simulationTime;
            receipt.scenarioProfile = state == null ? "default" : state.scenarioProfile;
            receipt.taskSourceMode = state == null ? "current" : state.taskSourceMode;
            receipt.successProfile = simulationParameters.RL_SUCCESS_PROFILE == null ? "default" : simulationParameters.RL_SUCCESS_PROFILE;
            receipt.deadline = state == null || state.task == null ? 0.0 : Math.max(0.0, state.task.maxLatency);
            return receipt;
        }

        private void applyResourceFields(ExecutionReceipt receipt) {
            receipt.resourceProfile = resourceProfile;
            receipt.continuousResourceBindingMode = resourceProfile == null ? "candidate_only" : resourceProfile.bindingMode.toString();
            receipt.continuousResourceApplied = resourceProfile != null && resourceProfile.continuousApplied;
            receipt.nativeSchedulerBound = resourceProfile != null && resourceProfile.nativeSchedulerBound();
            receipt.estimatorBound = resourceProfile != null && resourceProfile.estimatorBound();
            receipt.nativeBindingApplied = nativeBinding != null && nativeBinding.nativeBindingApplied;
            receipt.nativeCpuMipsBound = receipt.nativeBindingApplied;
            receipt.nativeNetworkBandwidthBound = receipt.nativeBindingApplied;
            receipt.nativeTxPowerBound = receipt.nativeBindingApplied;
            receipt.nativeBaseMips = nativeBinding == null ? 0.0 : nativeBinding.baseMips;
            receipt.nativeAppliedMips = nativeBinding == null ? 0.0 : nativeBinding.appliedMips;
            receipt.nativeCpuShare = nativeBinding == null ? 1.0 : nativeBinding.cpuShare;
            receipt.nativeBandwidthShare = nativeBinding == null ? 1.0 : nativeBinding.bandwidthShare;
            receipt.nativeTxPowerRatio = nativeBinding == null ? 1.0 : nativeBinding.txPowerRatio;
            receipt.nativeBinding = nativeBinding == null ? null : nativeBinding.toMap();
            receipt.fullHybridClosedLoopClaimAllowed = receipt.nativeSchedulerBound && receipt.nativeBindingApplied;
            if (resourceEstimate != null) {
                receipt.estimatorExpectedDelaySec = Math.max(0.0, resourceEstimate.expectedDelaySec);
                receipt.estimatorExpectedEnergyJ = Math.max(0.0, resourceEstimate.expectedEnergyJ);
                receipt.estimatorEffectiveMips = Math.max(0.0, resourceEstimate.effectiveMips);
                receipt.estimatorEffectiveBandwidthMbps = Math.max(0.0, resourceEstimate.effectiveBandwidthMbps);
                receipt.estimatorTxPowerW = Math.max(0.0, resourceEstimate.txPowerW);
            }
        }
    }

    private static void classifyFailureFromFallback(ExecutionReceipt receipt, String fallbackReason) {
        String reason = fallbackReason == null ? "" : fallbackReason.trim().toLowerCase();
        receipt.failureReason = reason.isEmpty() ? "unknown_failure" : reason;
        receipt.deadlineMiss = false;
        receipt.queueOverflow = false;
        receipt.vmUnavailable = false;
        receipt.linkUnavailable = false;
        receipt.taskDropped = false;
        receipt.invalidAction = false;
        receipt.simulationFailure = false;
        receipt.latencyExceeded = false;
        receipt.resourceExceeded = false;
        receipt.unknownFailure = false;
        if ("stale_decision_id".equals(reason) || "task_id_mismatch".equals(reason) || "action_not_visible".equals(reason)
                || "invalid_selected_candidate".equals(reason) || "unknown_target_vm_id".equals(reason)
                || "no_action_target".equals(reason)) {
            receipt.invalidAction = true;
            return;
        }
        if ("infeasible_selected_vm".equals(reason) || "no_feasible_candidate_for_abstract_action".equals(reason)) {
            receipt.vmUnavailable = true;
            receipt.resourceExceeded = true;
            return;
        }
        if ("session_closed".equals(reason) || "interrupted".equals(reason) || "receipt_unavailable".equals(reason)) {
            receipt.simulationFailure = true;
            return;
        }
        if ("no_pending_decision".equals(reason)) {
            receipt.taskDropped = true;
            return;
        }
        receipt.unknownFailure = true;
    }

    private Resolution validateResolvedCandidate(Resolution resolution, RlAction action, RlState state) {
        if (resolution.selectedAbstractAction < 0) {
            resolution.fallbackReason = "invalid_selected_candidate";
            return resolution;
        }
        int intendedAction = policyUpperAction(action);
        if (intendedAction >= 0 && !actionVisible(state, intendedAction)) {
            resolution.fallbackReason = "action_not_visible";
            return resolution;
        }
        if (intendedAction >= 0 && resolution.selectedAbstractAction != intendedAction) {
            resolution.fallbackReason = "action_not_visible";
            return resolution;
        }
        resolution.accepted = true;
        resolution.fallbackReason = "none";
        return resolution;
    }

    private int policyUpperAction(RlAction action) {
        if (action == null) {
            return -1;
        }
        if (action.policyUpperAction >= 0) {
            return action.policyUpperAction;
        }
        return action.abstractAction;
    }

    private String policyUpperActionName(RlAction action) {
        int actionIndex = policyUpperAction(action);
        if (action != null && action.policyUpperActionName != null && !"".equals(action.policyUpperActionName)) {
            return action.policyUpperActionName;
        }
        if (action != null && action.abstractActionName != null && !"".equals(action.abstractActionName)) {
            return action.abstractActionName;
        }
        return Orchestrator.abstractActionName(actionIndex);
    }

    private RlResourceBindingMode requestedBindingMode(RlAction action) {
        String raw = null;
        if (action != null && action.extra != null) {
            Object value = action.extra.get("continuous_resource_binding_mode");
            if (value == null) {
                value = action.extra.get("bindingMode");
            }
            if (value != null) {
                raw = String.valueOf(value);
            }
        }
        if (raw == null || "".equals(raw.trim())) {
            return RlResourceBindingMode.resource_aware_estimator_bound;
        }
        String normalized = raw.trim().toLowerCase();
        if ("candidate_only".equals(normalized)) {
            return RlResourceBindingMode.candidate_only;
        }
        if ("native_scheduler_bound".equals(normalized)) {
            return RlResourceBindingMode.native_scheduler_bound;
        }
        return RlResourceBindingMode.resource_aware_estimator_bound;
    }

    private double adjustedCandidateDelay(RlState.VmView vm, RlResourceProfile profile) {
        RlResourceAwareEstimator.Estimate estimate = RlResourceAwareEstimator.estimate(
                Math.max(1.0, vm.estimatedComputeCapacity),
                Math.max(1.0e-6, vm.estimatedTransmissionRateMbps),
                Math.max(0.0, vm.estimatedTransmissionDelaySec),
                Math.max(0.0, vm.estimatedComputeDelaySec),
                Math.max(0.0, vm.estimatedQueueDelaySec),
                Math.max(0.0, vm.propagationDelaySec),
                vm.isLocalToSource,
                profile);
        return Math.max(0.0, estimate.expectedDelaySec);
    }

    private void applyResourceEstimate(Resolution resolution, RlState state) {
        if (resolution == null) {
            return;
        }
        RlState.VmView vm = null;
        if (state != null && resolution.selectedVmIndex >= 0 && resolution.selectedVmIndex < state.candidateVms.size()) {
            vm = state.candidateVms.get(resolution.selectedVmIndex);
        }
        double nativeMips = Math.max(1.0, resolution.selectedComputeCapacity);
        double nativeRate = Math.max(1.0e-6, resolution.selectedRateMbps);
        double txDelay = Math.max(0.0, resolution.estimatedTaskTransmissionTimeSec);
        double computeDelay = Math.max(0.0, resolution.estimatedTaskComputeTimeSec);
        double queueDelay = Math.max(0.0, resolution.selectedDelay - txDelay - computeDelay - resolution.selectedPropagationDelaySec);
        if (vm != null) {
            nativeMips = Math.max(1.0, vm.estimatedComputeCapacity);
            nativeRate = Math.max(1.0e-6, vm.estimatedTransmissionRateMbps);
            txDelay = Math.max(0.0, vm.estimatedTransmissionDelaySec);
            computeDelay = Math.max(0.0, vm.estimatedComputeDelaySec);
            queueDelay = Math.max(0.0, vm.estimatedQueueDelaySec);
            resolution.selectedLocalToSource = vm.isLocalToSource;
            resolution.selectedPropagationDelaySec = Math.max(0.0, vm.propagationDelaySec);
        }
        resolution.resourceEstimate = RlResourceAwareEstimator.estimate(
                nativeMips,
                nativeRate,
                txDelay,
                computeDelay,
                queueDelay,
                resolution.selectedPropagationDelaySec,
                resolution.selectedLocalToSource,
                resolution.resourceProfile);
        if (resolution.resourceProfile != null && resolution.resourceProfile.estimatorBound()) {
            resolution.selectedDelay = resolution.resourceEstimate.expectedDelaySec;
            resolution.estimatedTaskTransmissionTimeSec = resolution.resourceEstimate.expectedTxDelaySec;
            resolution.estimatedTaskComputeTimeSec = resolution.resourceEstimate.expectedComputeDelaySec;
            resolution.estimatedTaskCompletionTimeSec = resolution.resourceEstimate.expectedDelaySec;
        }
    }

    private void cacheDecisionResult(Resolution resolution, ExecutionReceipt receipt, SimulationManager simulationManager) {
        if (resolution == null || receipt == null) {
            return;
        }
        RlDecisionResult result = new RlDecisionResult();
        result.taskId = receipt.taskId;
        result.vmIndex = receipt.executedVmIndex;
        result.abstractAction = receipt.executedAbstractAction;
        result.resolvedTier = receipt.executedLogicalTier;
        result.intendedTier = receipt.policyUpperActionName;
        result.fallbackUsed = receipt.fallbackReason != null && !"none".equals(receipt.fallbackReason);
        result.resourceProfile = resolution.resourceProfile;
        result.nativeBinding = resolution.nativeBinding;
        result.schedulingReceipt = receipt;
        result.decisionTimestamp = simulationManager == null || simulationManager.getSimulation() == null
                ? 0.0
                : simulationManager.getSimulation().clock();
        decisionResultByTaskId.put(Long.valueOf(result.taskId), result);
        while (decisionResultByTaskId.size() > 128) {
            Long eldest = decisionResultByTaskId.keySet().iterator().next();
            decisionResultByTaskId.remove(eldest);
        }
    }

    private boolean actionVisible(RlState state, int action) {
        if (state == null || action < 0 || action >= state.abstractActionMask.size()) {
            return false;
        }
        return state.abstractActionMask.get(action).intValue() != 0;
    }

    private ExecutionReceipt rejectedReceipt(RlState state, RlAction action, String fallbackReason, String message) {
        Resolution resolution = new Resolution();
        resolution.decisionId = state == null ? -1L : state.decisionId;
        resolution.taskId = state == null ? -1L : state.taskId;
        resolution.policyIntendedAction = policyUpperAction(action);
        resolution.policyIntendedActionName = policyUpperActionName(action);
        resolution.fallbackReason = fallbackReason;
        ExecutionReceipt receipt = resolution.toRejectedReceipt(state, readEnergyCounter(currentSimulationManager));
        receipt.message = message;
        return receipt;
    }

    private void cacheReceipt(ExecutionReceipt receipt) {
        if (receipt == null) {
            return;
        }
        receiptCache.put(Long.valueOf(receipt.decisionId), receipt);
        while (receiptCache.size() > 128) {
            Long eldest = receiptCache.keySet().iterator().next();
            receiptCache.remove(eldest);
        }
    }

    private ExecutionReceipt cachedReceipt(long decisionId) {
        return receiptCache.get(Long.valueOf(decisionId));
    }

    private void incrementFallbackReason(String fallbackReason) {
        String key = fallbackReason == null || "".equals(fallbackReason) ? "none" : fallbackReason;
        Long current = fallbackReasonCounts.get(key);
        fallbackReasonCounts.put(key, Long.valueOf(current == null ? 1L : current.longValue() + 1L));
    }

    private Double readEnergyCounter(SimulationManager simulationManager) {
        if (simulationManager == null || simulationManager.getServersManager() == null) {
            return null;
        }
        double total = 0.0;
        boolean observed = false;
        List<? extends DataCenter> datacenters = simulationManager.getServersManager().getDatacenterList();
        if (datacenters == null) {
            return null;
        }
        for (DataCenter dc : datacenters) {
            if (dc != null && dc.getEnergyModel() != null) {
                total += dc.getEnergyModel().getTotalEnergyConsumption();
                observed = true;
            }
        }
        return observed ? Double.valueOf(total) : null;
    }

    private String energyUnavailableReason(SimulationManager simulationManager) {
        if (simulationManager == null) {
            return "simulation_manager_unavailable";
        }
        if (simulationManager.getServersManager() == null) {
            return "servers_manager_unavailable";
        }
        List<? extends DataCenter> datacenters = simulationManager.getServersManager().getDatacenterList();
        if (datacenters == null || datacenters.isEmpty()) {
            return "datacenter_list_unavailable";
        }
        return "energy_model_unavailable";
    }
}
