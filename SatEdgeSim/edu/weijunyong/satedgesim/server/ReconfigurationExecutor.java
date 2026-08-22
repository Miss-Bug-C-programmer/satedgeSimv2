package edu.weijunyong.satedgesim.server;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.cloudbus.cloudsim.vms.Vm;

import edu.weijunyong.satedgesim.Network.NetworkModel;
import edu.weijunyong.satedgesim.SimulationManager.SimulationManager;
import edu.weijunyong.satedgesim.TasksGenerator.Task;

/**
 * Applies selective deltas to the native execution state. It is deliberately
 * constructed around the existing simulation manager and task/VM registries;
 * it never rebuilds the global scheduler or creates a second control path.
 */
public final class ReconfigurationExecutor {
    public static final boolean SUPPORTS_TASK_TARGET_MIGRATION = false;
    public static final boolean SUPPORTS_ROUTE_ACTUATION = false;
    public static final boolean SUPPORTS_DYNAMIC_PRIORITY_ACTUATION = false;

    private final SimulationManager simulationManager;
    private final List<Task> tasks;
    private final List<Vm> vms;
    private final NetworkModel networkModel;
    private final double simulationTimeSec;
    private final long worldVersion;

    public ReconfigurationExecutor(SimulationManager simulationManager, double simulationTimeSec, long worldVersion) {
        this.simulationManager = simulationManager;
        this.simulationTimeSec = simulationTimeSec;
        this.worldVersion = worldVersion;
        this.tasks = simulationManager == null || simulationManager.getTasksList() == null
                ? new ArrayList<Task>() : simulationManager.getTasksList();
        this.vms = simulationManager == null || simulationManager.getServersManager() == null
                ? new ArrayList<Vm>() : simulationManager.getServersManager().getVmList();
        this.networkModel = simulationManager == null ? null : simulationManager.getNetworkModel();
    }

    /** Dependency-light constructor used by contract tests with real Task/Vm objects. */
    public ReconfigurationExecutor(List<Task> tasks, List<Vm> vms, double simulationTimeSec, long worldVersion) {
        this.simulationManager = null;
        this.simulationTimeSec = simulationTimeSec;
        this.worldVersion = worldVersion;
        this.tasks = tasks == null ? new ArrayList<Task>() : tasks;
        this.vms = vms == null ? new ArrayList<Vm>() : vms;
        this.networkModel = null;
    }

    public PatchApplicationResult apply(ExecutionConfiguration current, ConfigurationPatch patch, boolean strictMode) {
        return applyInternal(current, patch, strictMode, false);
    }

    public PatchApplicationResult validate(ExecutionConfiguration current, ConfigurationPatch patch, boolean strictMode) {
        return applyInternal(current, patch, strictMode, true);
    }

    private PatchApplicationResult applyInternal(ExecutionConfiguration current, ConfigurationPatch patch,
            boolean strictMode, boolean dryRun) {
        if (patch == null) patch = new ConfigurationPatch();
        PatchApplicationResult result = new PatchApplicationResult();
        result.strictMode = strictMode;
        result.requestedPatch = patch.toMap();
        result.requestedScope = patch.requestedScope == null
                ? new LinkedHashMap<String, Object>() : ExecutionConfiguration.deepCopyMap(patch.requestedScope);
        result.simulationTimeSec = simulationTimeSec;
        result.worldVersion = worldVersion;
        result.interventionId = patch.originatingInterventionId;
        result.observedWorldVersion = patch.observedWorldVersion;
        result.observedControlEpoch = patch.observedControlEpoch;
        result.revalidatedWorldVersion = patch.revalidatedWorldVersion;
        if (current == null) {
            return reject(result, "no_active_execution_configuration", false, null);
        }
        if (strictMode && patch.baseConfigurationVersion == null) {
            return reject(result, "missing_base_configuration_version", false, null);
        }
        result.configurationVersionBefore = current.version;
        result.resultingConfigurationVersion = current.version;
        result.beforeConfiguration = current.toMap();
        result.afterConfiguration = current.toMap();
        if (patch.baseConfigurationVersion != null && patch.baseConfigurationVersion.longValue() != current.version) {
            result.staleBaseRejected = true;
            return reject(result, "stale_configuration_version", true, null);
        }
        if (strictMode && patch.hasMaterialChanges() && patch.baseWorldVersion == null) {
            return reject(result, "missing_base_world_version", true, null);
        }
        if (patch.observedWorldVersion != null && patch.observedWorldVersion.longValue() != worldVersion
                && (patch.revalidatedWorldVersion == null
                        || patch.revalidatedWorldVersion.longValue() != worldVersion)) {
            result.staleBaseRejected = true;
            return reject(result, "stale_observed_world_version", true, null);
        }
        if (patch.baseWorldVersion != null
                && patch.baseWorldVersion.longValue() != worldVersion
                && (patch.revalidatedWorldVersion == null
                        || patch.revalidatedWorldVersion.longValue() != worldVersion)) {
            result.staleBaseRejected = true;
            return reject(result, "stale_world_version", true, null);
        }
        if (patch.acquisitionEpoch != null && patch.acquisitionEpoch.longValue() < 0L) {
            return reject(result, "invalid_acquisition_epoch", false, null);
        }
        if (!patch.hasMaterialChanges()) {
            result.accepted = true;
            result.changed = false;
            result.decisionStatus = "APPLY";
            result.appliedPatch.put("preserveResumeRecompute", patch.preserveResumeRecompute);
            result.realizedReconfigurationVolume.put("changedEntityCount", 0);
            return result;
        }
        if (patch.requestedScope == null || patch.requestedScope.isEmpty()) {
            result.scopeInvariantSatisfied = false;
            return reject(result, "missing_requested_scope", false, null);
        }

        ExecutionConfiguration candidate = current.copy();
        List<AssignmentOperation> assignments = new ArrayList<AssignmentOperation>();
        Map<String, Map<String, Object>> resourceOperations = new LinkedHashMap<String, Map<String, Object>>();
        boolean hadRejected = false;

        for (Map.Entry<String, Object> entry : patch.taskAssignmentChanges.entrySet()) {
            String key = taskKey(entry.getKey());
            Task task = findTask(key);
            if (!scopeAllowsTask(patch.requestedScope, task, entry.getKey())) {
                hadRejected = true;
                rejectChange(result, "task_assignment", entry.getKey(), "out_of_scope", task);
                continue;
            }
            Map<String, Object> requested = asMap(entry.getValue());
            if (requested == null) {
                hadRejected = true;
                rejectChange(result, "task_assignment", entry.getKey(), "assignment_must_be_object", task);
                continue;
            }
            if (sameValue(current.assignments.get(entry.getKey()), entry.getValue())) continue;
            if (task == null) {
                hadRejected = true;
                rejectChange(result, "task_assignment", entry.getKey(), "task_not_found", null);
                continue;
            }
            TaskExecutionPhase phase = TaskLifecycle.phase(task, networkModel);
            if (phase == TaskExecutionPhase.COMPLETED) {
                hadRejected = true;
                rejectChange(result, "task_assignment", entry.getKey(), "completed_task_immutable", task);
                continue;
            }
            Vm target = targetVm(requested);
            if (target == null) {
                hadRejected = true;
                rejectChange(result, "task_assignment", entry.getKey(), "target_vm_unavailable", task);
                continue;
            }
            long currentVm = task.getVm() == null || task.getVm() == Vm.NULL ? -1L : task.getVm().getId();
            boolean sameTarget = task.getVm() == target
                    || (currentVm >= 0L && target.getId() >= 0L && currentVm == target.getId());
            if (!sameTarget && phase != TaskExecutionPhase.QUEUED) {
                hadRejected = true;
                rejectChange(result, "task_assignment", entry.getKey(),
                        SUPPORTS_TASK_TARGET_MIGRATION ? "migration_requires_runtime_transfer" : "unsupported_task_target_migration_" + phase.name().toLowerCase(), task);
                continue;
            }
            if (!sameTarget && !scopeAllowsAssignmentTarget(patch.requestedScope, task, target)) {
                hadRejected = true;
                rejectChange(result, "task_assignment", entry.getKey(), "out_of_scope_target_node", task);
                continue;
            }
            assignments.add(new AssignmentOperation(task, target, entry.getKey(), requested));
            candidate.assignments.put(entry.getKey(), ExecutionConfiguration.deepCopy(requested));
            acceptedChange(result, "taskAssignmentChanges", entry.getKey(), requested, task);
            addRealizedScope(result.realizedScope, "node_ids", target.getId());
        }

        collectResourceOperations(resourceOperations, patch.resourceChanges, null);
        collectResourceOperations(resourceOperations, patch.cpuAllocationChanges, "cpuShare");
        collectResourceOperations(resourceOperations, patch.bandwidthAllocationChanges, "bandwidthShare");
        for (Map.Entry<String, Map<String, Object>> entry : resourceOperations.entrySet()) {
            String key = entry.getKey();
            Task task = findTask(taskKey(key));
            if (!scopeAllowsTask(patch.requestedScope, task, key)) {
                hadRejected = true;
                rejectChange(result, "resource", key, "out_of_scope", task);
                continue;
            }
            if (sameValue(current.resourceAllocations.get(key), entry.getValue())
                    && !patch.cpuAllocationChanges.containsKey(key)
                    && !patch.bandwidthAllocationChanges.containsKey(key)) continue;
            if (task == null) {
                hadRejected = true;
                rejectChange(result, "resource", key, "task_not_found", null);
                continue;
            }
            TaskExecutionPhase phase = TaskLifecycle.phase(task, networkModel);
            if (phase == TaskExecutionPhase.COMPLETED) {
                hadRejected = true;
                rejectChange(result, "resource", key, "completed_task_immutable", task);
                continue;
            }
            Map<String, Object> merged = asMap(current.resourceAllocations.get(key));
            if (merged == null) merged = new LinkedHashMap<String, Object>();
            if (entry.getValue().containsKey("resourceValue")) {
                hadRejected = true;
                rejectChange(result, "resource", key, "resource_change_must_be_object", task);
                continue;
            }
            merged.putAll(entry.getValue());
            validateResourceMap(merged, result, key, task);
            if (hasRejectedFor(result, "resource", key)) {
                hadRejected = true;
                continue;
            }
            candidate.resourceAllocations.put(key, merged);
            if (merged.containsKey("cpuShare")) candidate.cpuAllocations.put(key, merged.get("cpuShare"));
            if (merged.containsKey("bandwidthShare")) candidate.bandwidthAllocations.put(key, merged.get("bandwidthShare"));
            acceptedChange(result, "resourceChanges", key, merged, task);
        }

        for (Map.Entry<String, Object> entry : patch.routeChanges.entrySet()) {
            if (sameValue(current.routes.get(entry.getKey()), entry.getValue())) continue;
            if (!scopeAllowsRoute(patch.requestedScope, entry.getKey())) {
                hadRejected = true;
                rejectChange(result, "route", entry.getKey(), "out_of_scope", null);
            } else {
                hadRejected = true;
                rejectChange(result, "route", entry.getKey(), SUPPORTS_ROUTE_ACTUATION ? "route_apply_failed" : "unsupported_route_actuation", null);
            }
        }
        for (Map.Entry<String, Object> entry : patch.priorityChanges.entrySet()) {
            if (sameValue(current.priorities.get(entry.getKey()), entry.getValue())) continue;
            Task task = findTask(taskKey(entry.getKey()));
            if (!scopeAllowsTask(patch.requestedScope, task, entry.getKey())) {
                hadRejected = true;
                rejectChange(result, "priority", entry.getKey(), "out_of_scope", task);
            } else {
                hadRejected = true;
                rejectChange(result, "priority", entry.getKey(), "unsupported_dynamic_priority_actuation", task);
            }
        }
        for (Map.Entry<String, Object> entry : patch.persistentRuleChanges.entrySet()) {
            if (!scopeAllowsRule(patch.requestedScope, entry.getKey())) {
                hadRejected = true;
                rejectChange(result, "persistent_rule", entry.getKey(), "out_of_scope", null);
                continue;
            }
            if (sameValue(current.reusableRules.get(entry.getKey()), entry.getValue())) continue;
            candidate.reusableRules.put(entry.getKey(), ExecutionConfiguration.deepCopy(entry.getValue()));
            acceptedChange(result, "persistentRuleChanges", entry.getKey(), entry.getValue(), null);
        }

        if (strictMode && hadRejected) {
            result.scopeInvariantSatisfied = !hasOutOfScope(result);
            discardTentativeApplication(result);
            result.decisionStatus = "PARTIAL_REJECT";
            return reject(result, "strict_patch_rejected", false, null);
        }
        if (result.appliedPatch.isEmpty()) {
            result.decisionStatus = hadRejected ? "PARTIAL_REJECT" : "REPLAN_REQUIRED";
            return reject(result, hadRejected ? "no_change_accepted" : "patch_not_material", false, null);
        }

        if (!dryRun) {
            try {
                for (AssignmentOperation operation : assignments) operation.task.setVm(operation.target);
                applyNativeResources(resourceOperations, result);
            } catch (RuntimeException error) {
                for (AssignmentOperation operation : assignments) operation.task.setVm(operation.previous);
                discardTentativeApplication(result);
                return reject(result, "native_resource_application_failed:" + error.getClass().getSimpleName(), false, null);
            }
        }
        candidate.version = current.version + 1L;
        if (!Double.isFinite(candidate.creationSimTimeSec)) candidate.creationSimTimeSec = simulationTimeSec;
        candidate.lastUpdateSimTimeSec = simulationTimeSec;
        candidate.worldVersion = worldVersion;
        if (Double.isFinite(candidate.configuredLifetimeSec)) {
            candidate.expiresAtSimTimeSec = simulationTimeSec + candidate.configuredLifetimeSec;
        }
        candidate.provenance.putAll(patch.provenance);
        if (patch.originatingPlannerId != null) candidate.provenance.put("originatingPlannerId", patch.originatingPlannerId);
        if (patch.originatingInterventionId != null) candidate.provenance.put("originatingInterventionId", patch.originatingInterventionId);
        result.accepted = true;
        result.changed = true;
        result.decisionStatus = hadRejected ? "PARTIAL_REJECT" : "APPLY";
        result.resultingConfigurationVersion = candidate.version;
        result.afterConfiguration = candidate.toMap();
        result.appliedPatch.put("preserveResumeRecompute", patch.preserveResumeRecompute);
        result.realizedReconfigurationVolume.put("changedEntityCount", result.actualChangedEntities.size());
        result.realizedReconfigurationVolume.put("assignmentChanges", count(result.appliedPatch, "taskAssignmentChanges"));
        result.realizedReconfigurationVolume.put("resourceChanges", count(result.appliedPatch, "resourceChanges"));
        result.realizedReconfigurationVolume.put("routeChanges", 0);
        result.realizedReconfigurationVolume.put("priorityChanges", 0);
        result.realizedReconfigurationVolume.put("persistentRuleChanges", count(result.appliedPatch, "persistentRuleChanges"));
        result.realizedReconfigurationVolume.put("bytesStateTransferred", 0L);
        result.realizedReconfigurationVolume.put("taskTargetMigrationSupported", SUPPORTS_TASK_TARGET_MIGRATION);
        result.realizedReconfigurationVolume.put("nativeResourceActuation",
                result.realizedReconfigurationVolume.get("nativeBindingSnapshots") instanceof Map);
        result.realizedReconfigurationVolume.put("resourceConfigurationUpdated",
                count(result.appliedPatch, "resourceChanges") > 0);
        result.realizedReconfigurationVolume.put("dryRun", dryRun);
        result.scopeInvariantSatisfied = !hasOutOfScope(result);
        return result;
    }

    private void applyNativeResources(Map<String, Map<String, Object>> resourceOperations, PatchApplicationResult result) {
        Map<String, Object> bindingReceipts = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Map<String, Object>> entry : resourceOperations.entrySet()) {
            Task task = findTask(taskKey(entry.getKey()));
            if (task == null || task.getVm() == null || task.getVm() == Vm.NULL) continue;
            int vmIndex = vms.indexOf(task.getVm());
            RlAction action = new RlAction();
            Map<String, Object> allocation = entry.getValue();
            action.cpuShare = number(allocation.get("cpuShare"), 1.0);
            action.bandwidthShare = number(allocation.get("bandwidthShare"), 1.0);
            action.txPowerRatio = number(allocation.get("txPowerRatio"), 1.0);
            RlResourceProfile profile = RlResourceProfile.fromAction(action, RlResourceBindingMode.native_scheduler_bound);
            RlNativeResourceBindingManager.BindingSnapshot snapshot = RlNativeResourceBindingManager.rebindTask(
                    task, task.getVm(), vmIndex, profile, simulationTimeSec, simulationManager);
            bindingReceipts.put(entry.getKey(), snapshot.toMap());
        }
        if (!bindingReceipts.isEmpty()) result.realizedReconfigurationVolume.put("nativeBindingSnapshots", bindingReceipts);
    }

    private void collectResourceOperations(Map<String, Map<String, Object>> target,
            Map<String, Object> source, String forcedField) {
        if (source == null) return;
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            Map<String, Object> values = target.get(entry.getKey());
            if (values == null) {
                values = new LinkedHashMap<String, Object>();
                target.put(entry.getKey(), values);
            }
            if (forcedField != null) {
                Object value = entry.getValue();
                if (value instanceof Map && ((Map<?, ?>) value).containsKey(forcedField)) value = ((Map<?, ?>) value).get(forcedField);
                values.put(forcedField, value);
            } else if (entry.getValue() instanceof Map) {
                values.putAll(asMap(entry.getValue()));
            } else {
                values.put("resourceValue", entry.getValue());
            }
        }
    }

    private void validateResourceMap(Map<String, Object> values, PatchApplicationResult result, String key, Task task) {
        for (String field : new String[] {"cpuShare", "bandwidthShare", "txPowerRatio"}) {
            Object raw = values.get(field);
            if (raw == null) continue;
            double value = number(raw, Double.NaN);
            if (!Double.isFinite(value) || value <= 0.0 || value > 1.0) {
                rejectChange(result, "resource", key, "invalid_resource_value:" + field, task);
            }
        }
    }

    private Task findTask(String rawKey) {
        long id;
        try { id = Long.parseLong(rawKey); } catch (NumberFormatException error) { return null; }
        for (Task task : tasks) if (task != null && task.getId() == id) return task;
        return null;
    }

    private Vm targetVm(Map<String, Object> assignment) {
        long id = longNumber(assignment.get("targetVmId"), longNumber(assignment.get("selectedVmId"), longNumber(assignment.get("vmId"), -1L)));
        if (id >= 0L) for (Vm vm : vms) if (vm != null && vm.getId() == id) return vm;
        int index = (int) longNumber(assignment.get("targetVmIndex"), longNumber(assignment.get("vmIndex"), -1L));
        return index >= 0 && index < vms.size() ? vms.get(index) : null;
    }

    private boolean scopeAllowsTask(Map<String, Object> scope, Task task, String key) {
        if (Boolean.TRUE.equals(scope.get("all"))) return true;
        if (contains(scope, "task_ids", "taskIds", key) || (task != null && contains(scope, "task_ids", "taskIds", task.getId()))) return true;
        if (task != null && task.getEdgeDevice() != null && contains(scope, "source_ids", "sourceIds", task.getEdgeDevice().getDeviceID())) return true;
        if (task != null && task.getVm() != null && task.getVm() != Vm.NULL) {
            if (contains(scope, "node_ids", "nodeIds", task.getVm().getId()) || contains(scope, "resource_keys", "resourceKeys", task.getVm().getId())) return true;
        }
        return false;
    }

    /**
     * A node-scoped assignment must authorize the destination node as well as
     * the currently assigned task. Task/source scopes are explicit dependency
     * scopes and therefore authorize the task's selected destination.
     */
    private boolean scopeAllowsAssignmentTarget(Map<String, Object> scope, Task task, Vm target) {
        if (Boolean.TRUE.equals(scope.get("all"))) return true;
        if (contains(scope, "task_ids", "taskIds", task.getId())
                || (task.getEdgeDevice() != null
                        && contains(scope, "source_ids", "sourceIds", task.getEdgeDevice().getDeviceID()))) {
            return true;
        }
        if (contains(scope, "node_ids", "nodeIds", target.getId())
                || contains(scope, "resource_keys", "resourceKeys", target.getId())) {
            return true;
        }
        if (target.getHost() != null && target.getHost().getDatacenter() instanceof edu.weijunyong.satedgesim.DataCentersManager.DataCenter) {
            int deviceId = ((edu.weijunyong.satedgesim.DataCentersManager.DataCenter) target.getHost().getDatacenter()).getDeviceID();
            return contains(scope, "node_ids", "nodeIds", deviceId);
        }
        return false;
    }

    private boolean scopeAllowsRule(Map<String, Object> scope, String key) {
        if (Boolean.TRUE.equals(scope.get("all"))) return true;
        return contains(scope, "task_ids", "taskIds", key) || contains(scope, "route_ids", "routeIds", key)
                || contains(scope, "resource_keys", "resourceKeys", key);
    }

    private boolean scopeAllowsRoute(Map<String, Object> scope, String key) {
        if (Boolean.TRUE.equals(scope.get("all"))) return true;
        return contains(scope, "route_ids", "routeIds", key) || contains(scope, "link_ids", "linkIds", key)
                || contains(scope, "resource_keys", "resourceKeys", key);
    }

    private static boolean contains(Map<String, Object> scope, String first, String second, Object value) {
        Object raw = scope.get(first);
        if (raw == null && second != null) raw = scope.get(second);
        if (!(raw instanceof List)) return false;
        for (Object item : (List<?>) raw) if (sameIdentifier(item, value)) return true;
        return false;
    }

    private static boolean sameIdentifier(Object left, Object right) {
        if (left == null || right == null) return left == right;
        if (left instanceof Number && right instanceof Number) {
            return Double.compare(((Number) left).doubleValue(), ((Number) right).doubleValue()) == 0;
        }
        String a = String.valueOf(left);
        String b = String.valueOf(right);
        if (a.equals(b)) return true;
        try {
            return Double.compare(Double.parseDouble(a), Double.parseDouble(b)) == 0;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static String taskKey(String key) {
        if (key == null) return "";
        return key.startsWith("task:") ? key.substring("task:".length()) : key;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object raw) {
        return raw instanceof Map ? new LinkedHashMap<String, Object>((Map<String, Object>) raw) : null;
    }

    private static boolean sameValue(Object left, Object right) {
        return left == null ? right == null : left.equals(right);
    }

    private static double number(Object value, double fallback) { return value instanceof Number ? ((Number) value).doubleValue() : fallback; }
    private static long longNumber(Object value, long fallback) { return value instanceof Number ? ((Number) value).longValue() : fallback; }

    private void acceptedChange(PatchApplicationResult result, String category, String key, Object value, Task task) {
        Map<String, Object> map = asMap(result.appliedPatch.get(category));
        if (map == null) map = new LinkedHashMap<String, Object>();
        map.put(key, ExecutionConfiguration.deepCopy(value));
        result.appliedPatch.put(category, map);
        String entity = "task:" + key + ":" + category;
        result.actualChangedEntities.add(entity);
        addRealizedScope(result.realizedScope, "task_ids", key);
        if (task != null && task.getVm() != null && task.getVm() != Vm.NULL) addRealizedScope(result.realizedScope, "node_ids", task.getVm().getId());
        addRealizedScope(result.realizedScope, "resource_keys", category + ":" + key);
    }

    private void rejectChange(PatchApplicationResult result, String category, String key, String reason, Task task) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("changeType", category);
        item.put("entityId", key);
        item.put("reason", reason);
        item.put("phase", task == null ? null : TaskLifecycle.phase(task, networkModel).name());
        result.rejectedChanges.add(item);
        if (reason.startsWith("out_of_scope")) result.scopeInvariantSatisfied = false;
    }

    private boolean hasRejectedFor(PatchApplicationResult result, String category, String key) {
        for (Map<String, Object> item : result.rejectedChanges) {
            if (category.equals(item.get("changeType")) && key.equals(item.get("entityId"))) return true;
        }
        return false;
    }

    private static void addRealizedScope(Map<String, Object> scope, String key, Object value) {
        @SuppressWarnings("unchecked") List<Object> values = scope.get(key) instanceof List
                ? (List<Object>) scope.get(key) : new ArrayList<Object>();
        if (!values.contains(value)) values.add(value);
        scope.put(key, values);
    }

    private static int count(Map<String, Object> map, String key) {
        Object raw = map.get(key);
        return raw instanceof Map ? ((Map<?, ?>) raw).size() : 0;
    }

    private static boolean hasOutOfScope(PatchApplicationResult result) {
        for (Map<String, Object> item : result.rejectedChanges) {
            Object reason = item.get("reason");
            if (reason != null && String.valueOf(reason).startsWith("out_of_scope")) return true;
        }
        return false;
    }

    private static void discardTentativeApplication(PatchApplicationResult result) {
        result.appliedPatch.clear();
        result.actualChangedEntities.clear();
        result.realizedScope.clear();
        result.realizedReconfigurationVolume.clear();
        result.afterConfiguration = result.beforeConfiguration;
    }

    private static PatchApplicationResult reject(PatchApplicationResult result, String reason, boolean stale, Object unused) {
        result.accepted = false;
        result.changed = false;
        result.rejectionReason = reason;
        result.staleBaseRejected = result.staleBaseRejected || stale;
        if (stale) result.decisionStatus = "REJECT_STALE";
        else if (result.decisionStatus == null || "REPLAN_REQUIRED".equals(result.decisionStatus)) result.decisionStatus = "REPLAN_REQUIRED";
        return result;
    }

    private static final class AssignmentOperation {
        final Task task;
        final Vm target;
        final Vm previous;
        @SuppressWarnings("unused") final String key;
        @SuppressWarnings("unused") final Map<String, Object> assignment;

        AssignmentOperation(Task task, Vm target, String key, Map<String, Object> assignment) {
            this.task = task;
            this.target = target;
            this.previous = task.getVm();
            this.key = key;
            this.assignment = assignment;
        }
    }
}
