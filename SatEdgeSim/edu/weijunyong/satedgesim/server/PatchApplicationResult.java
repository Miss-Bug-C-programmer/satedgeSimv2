package edu.weijunyong.satedgesim.server;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Runtime receipt for a selective configuration patch application. */
public final class PatchApplicationResult {
    public boolean accepted;
    public boolean changed;
    public boolean strictMode;
    public boolean scopeInvariantSatisfied = true;
    public boolean staleBaseRejected = false;
    public String receiptType = "configuration_patch_application";
    /** APPLY, REJECT_STALE, PARTIAL_REJECT or REPLAN_REQUIRED. */
    public String decisionStatus = "REPLAN_REQUIRED";
    public String evidenceId;
    public String interventionId;
    public long configurationVersionBefore = 0L;
    public long resultingConfigurationVersion = 0L;
    public long worldVersion = 0L;
    public Long observedWorldVersion;
    public Long observedControlEpoch;
    public Long revalidatedWorldVersion;
    public double simulationTimeSec = 0.0;
    public Map<String, Object> requestedPatch = new LinkedHashMap<String, Object>();
    public Map<String, Object> requestedScope = new LinkedHashMap<String, Object>();
    public Map<String, Object> appliedPatch = new LinkedHashMap<String, Object>();
    public List<Map<String, Object>> rejectedChanges = new ArrayList<Map<String, Object>>();
    public Map<String, Object> realizedScope = new LinkedHashMap<String, Object>();
    public List<String> actualChangedEntities = new ArrayList<String>();
    public Map<String, Object> realizedReconfigurationVolume = new LinkedHashMap<String, Object>();
    public Map<String, Object> beforeConfiguration = new LinkedHashMap<String, Object>();
    public Map<String, Object> afterConfiguration = new LinkedHashMap<String, Object>();
    public String rejectionReason;
    public String operationClass = "SELECTIVE_INTERVENTION";
    public boolean configurationChanged = false;
    public boolean nativeExecutionChanged = false;
    public boolean nativeResourceActuationObserved = false;
    public boolean futureDispatchRuleChanged = false;
    public boolean ruleEffectiveAtRuntime = false;
    public boolean validatedAfterPhysicalAdvance = false;
    public String validationReceiptId;
    public String physicalAdvanceReceiptId;
    public Map<String, Object> requestedMaterialChanges = new LinkedHashMap<String, Object>();
    public Map<String, Object> configurationAppliedChanges = new LinkedHashMap<String, Object>();
    public Map<String, Object> nativeAppliedChanges = new LinkedHashMap<String, Object>();
    public Map<String, Object> deferredChanges = new LinkedHashMap<String, Object>();
    public Map<String, Object> realizedConfigurationScope = new LinkedHashMap<String, Object>();
    public Map<String, Object> realizedNativeScope = new LinkedHashMap<String, Object>();

    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("receiptType", receiptType);
        result.put("accepted", accepted);
        result.put("changed", changed);
        result.put("strictMode", strictMode);
        result.put("scopeInvariantSatisfied", scopeInvariantSatisfied);
        result.put("staleBaseRejected", staleBaseRejected);
        result.put("decisionStatus", decisionStatus);
        result.put("evidenceId", evidenceId);
        result.put("interventionId", interventionId);
        result.put("configurationVersionBefore", configurationVersionBefore);
        result.put("resultingConfigurationVersion", resultingConfigurationVersion);
        result.put("worldVersion", worldVersion);
        result.put("observedWorldVersion", observedWorldVersion);
        result.put("observedControlEpoch", observedControlEpoch);
        result.put("revalidatedWorldVersion", revalidatedWorldVersion);
        result.put("simulationTimeSec", simulationTimeSec);
        result.put("requestedPatch", requestedPatch);
        result.put("requestedScope", requestedScope);
        result.put("requested", entitySummary(requestedScope));
        result.put("requestedTasks", requestedScope.get("task_ids"));
        result.put("requestedNodes", requestedScope.get("node_ids"));
        result.put("requestedLinks", requestedScope.get("link_ids"));
        result.put("requestedRoutes", requestedScope.get("route_ids"));
        result.put("requestedResources", requestedScope.get("resource_keys"));
        result.put("appliedPatch", appliedPatch);
        result.put("applied", entitySummary(realizedScope));
        result.put("actuallyChangedTasks", realizedScope.get("task_ids"));
        result.put("actuallyChangedNodes", realizedScope.get("node_ids"));
        result.put("actuallyChangedLinks", realizedScope.get("link_ids"));
        result.put("actuallyChangedRoutes", realizedScope.get("route_ids"));
        result.put("actuallyChangedResources", realizedScope.get("resource_keys"));
        result.put("rejectedChanges", rejectedChanges);
        result.put("rejected", rejectedChanges);
        result.put("realizedScope", realizedScope);
        result.put("actualChangedEntities", actualChangedEntities);
        result.put("actual_changed_entities", actualChangedEntities);
        result.put("realizedReconfigurationVolume", realizedReconfigurationVolume);
        result.put("beforeConfiguration", beforeConfiguration);
        result.put("afterConfiguration", afterConfiguration);
        result.put("rejectionReason", rejectionReason);
        result.put("operationClass", operationClass);
        result.put("configurationChanged", configurationChanged);
        result.put("nativeExecutionChanged", nativeExecutionChanged);
        result.put("nativeResourceActuationObserved", nativeResourceActuationObserved);
        result.put("futureDispatchRuleChanged", futureDispatchRuleChanged);
        result.put("ruleEffectiveAtRuntime", ruleEffectiveAtRuntime);
        result.put("validatedAfterPhysicalAdvance", validatedAfterPhysicalAdvance);
        result.put("validationReceiptId", validationReceiptId);
        result.put("physicalAdvanceReceiptId", physicalAdvanceReceiptId);
        result.put("requestedMaterialChanges", requestedMaterialChanges);
        result.put("configurationAppliedChanges", configurationAppliedChanges);
        result.put("nativeAppliedChanges", nativeAppliedChanges);
        result.put("deferredChanges", deferredChanges);
        result.put("realizedConfigurationScope", realizedConfigurationScope);
        result.put("realizedNativeScope", realizedNativeScope);
        return result;
    }

    private static Map<String, Object> entitySummary(Map<String, Object> scope) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (scope == null) return result;
        result.put("tasks", scope.get("task_ids"));
        result.put("nodes", scope.get("node_ids"));
        result.put("links", scope.get("link_ids"));
        result.put("routes", scope.get("route_ids"));
        result.put("resources", scope.get("resource_keys"));
        return result;
    }
}
