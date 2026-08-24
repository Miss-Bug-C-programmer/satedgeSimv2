package edu.weijunyong.satedgesim.server;

import java.util.LinkedHashMap;
import java.util.Map;

/** A versioned selective delta ΔΠ_k over the active execution configuration. */
public final class ConfigurationPatch {
    public Long baseConfigurationVersion;
    public Long baseWorldVersion;
    /** World/control identity observed before planner computation. */
    public Long observedWorldVersion;
    public Long observedControlEpoch;
    /** World identity returned by canonical post-delay revalidation. */
    public Long revalidatedWorldVersion;
    public Long acquisitionEpoch;
    public Map<String, Object> requestedScope = new LinkedHashMap<String, Object>();
    public Map<String, Object> taskAssignmentChanges = new LinkedHashMap<String, Object>();
    public Map<String, Object> routeChanges = new LinkedHashMap<String, Object>();
    public Map<String, Object> resourceChanges = new LinkedHashMap<String, Object>();
    public Map<String, Object> cpuAllocationChanges = new LinkedHashMap<String, Object>();
    public Map<String, Object> bandwidthAllocationChanges = new LinkedHashMap<String, Object>();
    public Map<String, Object> priorityChanges = new LinkedHashMap<String, Object>();
    public Map<String, Object> persistentRuleChanges = new LinkedHashMap<String, Object>();
    /** Explicit preserve/resume/recompute semantics; can be a mode string or structured object. */
    public Object preserveResumeRecompute = new LinkedHashMap<String, Object>();
    public String originatingPlannerId;
    public String originatingInterventionId;
    public Map<String, Object> provenance = new LinkedHashMap<String, Object>();
    public Map<String, Object> planningDelayMetadata = new LinkedHashMap<String, Object>();
    public Map<String, Object> acquisitionMetadata = new LinkedHashMap<String, Object>();
    /** Server-issued physical advancement receipt required by strict intervention. */
    public String physicalAdvanceReceiptId;
    /** HTTP boundary fields; the server replaces these with verified state. */
    public String validationReceiptId;
    public String validationReceiptFailureReason;
    public boolean serverValidationReceiptVerified = false;
    public Long serverValidatedWorldVersion;
    public String serverValidatedWorldIdentityDigest;
    public boolean strict = true;
    /** In-process authority installed only after SatEdgeSim verifies the server receipt. */
    private ValidationReceipt serverValidationReceipt;

    void attachServerValidationReceipt(ValidationReceipt receipt) {
        this.serverValidationReceipt = receipt;
        if (receipt != null) {
            this.serverValidationReceiptVerified = true;
            this.serverValidatedWorldVersion = Long.valueOf(receipt.validatedWorldVersion);
            this.serverValidatedWorldIdentityDigest = receipt.worldIdentityDigest;
        }
    }

    boolean hasServerValidationReceipt() {
        return serverValidationReceipt != null
                && serverValidationReceipt.validationReceiptId != null
                && serverValidationReceipt.validationReceiptId.equals(validationReceiptId);
    }

    ValidationReceipt getServerValidationReceipt() {
        return serverValidationReceipt;
    }

    @SuppressWarnings("unchecked")
    public static ConfigurationPatch fromRequest(Map<String, Object> request) {
        ConfigurationPatch result = new ConfigurationPatch();
        Map<String, Object> source = request == null ? new LinkedHashMap<String, Object>() : request;
        Object nested = source.get("patch");
        if (!(nested instanceof Map)) nested = source.get("configurationPatch");
        if (nested instanceof Map) {
            @SuppressWarnings("unchecked") Map<String, Object> nestedMap = (Map<String, Object>) nested;
            source = nestedMap;
        }
        result.baseConfigurationVersion = longValue(source, "base_configuration_version", "baseConfigurationVersion");
        if (result.baseConfigurationVersion == null) result.baseConfigurationVersion = longValue(source, "baseVersion", null);
        result.baseWorldVersion = longValue(source, "base_world_version", "baseWorldVersion");
        result.observedWorldVersion = longValue(source, "observed_world_version", "observedWorldVersion");
        result.observedControlEpoch = longValue(source, "observed_control_epoch", "observedControlEpoch");
        result.revalidatedWorldVersion = longValue(source, "revalidated_world_version", "revalidatedWorldVersion");
        result.acquisitionEpoch = longValue(source, "acquisition_epoch", "acquisitionEpoch");
        copyMap(source, result.requestedScope, "requested_scope", "requestedScope");
        if (result.requestedScope.isEmpty()) copyMap(source, result.requestedScope, "scope", null);
        copyMap(source, result.taskAssignmentChanges, "task_assignment_changes", "taskAssignmentChanges");
        if (result.taskAssignmentChanges.isEmpty()) copyMap(source, result.taskAssignmentChanges, "assignment_changes", "assignmentChanges");
        copyMap(source, result.routeChanges, "route_changes", "routeChanges");
        copyMap(source, result.resourceChanges, "resource_changes", "resourceChanges");
        copyMap(source, result.cpuAllocationChanges, "cpu_allocation_changes", "cpuAllocationChanges");
        if (result.cpuAllocationChanges.isEmpty()) copyMap(source, result.cpuAllocationChanges, "cpu_changes", "cpuChanges");
        copyMap(source, result.bandwidthAllocationChanges, "bandwidth_allocation_changes", "bandwidthAllocationChanges");
        if (result.bandwidthAllocationChanges.isEmpty()) copyMap(source, result.bandwidthAllocationChanges, "bandwidth_changes", "bandwidthChanges");
        copyMap(source, result.priorityChanges, "priority_changes", "priorityChanges");
        copyMap(source, result.persistentRuleChanges, "persistent_rule_changes", "persistentRuleChanges");
        Object semantics = source.get("preserve_resume_recompute");
        if (semantics == null) semantics = source.get("preserveResumeRecompute");
        if (semantics != null) {
            result.preserveResumeRecompute = semantics instanceof Map
                    ? new LinkedHashMap<String, Object>((Map<String, Object>) semantics) : semantics;
        } else {
            Map<String, Object> modes = new LinkedHashMap<String, Object>();
            for (String key : new String[] {"preserve", "resume", "recompute"}) if (source.containsKey(key)) modes.put(key, source.get(key));
            result.preserveResumeRecompute = modes;
        }
        result.originatingPlannerId = stringValue(source, "originating_planner_id", "originatingPlannerId");
        result.originatingInterventionId = stringValue(source, "originating_intervention_id", "originatingInterventionId");
        if (result.originatingInterventionId == null) {
            result.originatingInterventionId = stringValue(source, "intervention_id", "interventionId");
        }
        copyMap(source, result.provenance, "provenance", null);
        copyMap(source, result.planningDelayMetadata, "planning_delay_metadata", "planningDelayMetadata");
        copyMap(source, result.acquisitionMetadata, "acquisition_metadata", "acquisitionMetadata");
        result.physicalAdvanceReceiptId = stringValue(source, "physical_advance_receipt_id", "physicalAdvanceReceiptId");
        result.validationReceiptId = stringValue(source, "validation_receipt_id", "validationReceiptId");
        if (result.validationReceiptId == null) {
            result.validationReceiptId = stringValue(source, "validation_receipt_token", "validationReceiptToken");
        }
        Object strictValue = source.get("strict");
        if (strictValue == null) strictValue = source.get("strictMode");
        if (strictValue instanceof Boolean) result.strict = ((Boolean) strictValue).booleanValue();
        return result;
    }

    public boolean hasMaterialChanges() {
        return !taskAssignmentChanges.isEmpty() || !routeChanges.isEmpty() || !resourceChanges.isEmpty()
                || !cpuAllocationChanges.isEmpty() || !bandwidthAllocationChanges.isEmpty()
                || !priorityChanges.isEmpty() || !persistentRuleChanges.isEmpty();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("baseConfigurationVersion", baseConfigurationVersion);
        result.put("baseWorldVersion", baseWorldVersion);
        result.put("observedWorldVersion", observedWorldVersion);
        result.put("observedControlEpoch", observedControlEpoch);
        result.put("revalidatedWorldVersion", revalidatedWorldVersion);
        result.put("acquisitionEpoch", acquisitionEpoch);
        result.put("requestedScope", requestedScope);
        result.put("taskAssignmentChanges", taskAssignmentChanges);
        result.put("routeChanges", routeChanges);
        result.put("resourceChanges", resourceChanges);
        result.put("cpuAllocationChanges", cpuAllocationChanges);
        result.put("bandwidthAllocationChanges", bandwidthAllocationChanges);
        result.put("priorityChanges", priorityChanges);
        result.put("persistentRuleChanges", persistentRuleChanges);
        result.put("preserveResumeRecompute", preserveResumeRecompute);
        result.put("originatingPlannerId", originatingPlannerId);
        result.put("originatingInterventionId", originatingInterventionId);
        result.put("provenance", provenance);
        result.put("planningDelayMetadata", planningDelayMetadata);
        result.put("acquisitionMetadata", acquisitionMetadata);
        result.put("physicalAdvanceReceiptId", physicalAdvanceReceiptId);
        result.put("validationReceiptId", validationReceiptId);
        result.put("strict", strict);
        return result;
    }

    private static Long longValue(Map<String, Object> source, String first, String second) {
        Object value = source.get(first);
        if (value == null && second != null) value = source.get(second);
        return value instanceof Number ? Long.valueOf(((Number) value).longValue()) : null;
    }

    @SuppressWarnings("unchecked")
    private static void copyMap(Map<String, Object> source, Map<String, Object> target, String first, String second) {
        if (first == null) return;
        Object value = source.get(first);
        if (value == null && second != null) value = source.get(second);
        if (value instanceof Map) target.putAll((Map<String, Object>) value);
    }

    private static String stringValue(Map<String, Object> source, String first, String second) {
        Object value = source.get(first);
        if (value == null && second != null) value = source.get(second);
        return value == null ? null : String.valueOf(value);
    }
}
