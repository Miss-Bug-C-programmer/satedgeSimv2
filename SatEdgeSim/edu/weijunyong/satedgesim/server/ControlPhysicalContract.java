package edu.weijunyong.satedgesim.server;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stable capability declaration for the TriSatFlow/SatEdgeSim boundary. */
public final class ControlPhysicalContract {
    public static final String VERSION = "2.1";
    public static final String SERVER_VERSION = "satedgesim-v2";

    private ControlPhysicalContract() {
    }

    public static Map<String, Object> capabilities(boolean sessionReady, Map<String, Object> instrumentation) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("serverVersion", SERVER_VERSION);
        result.put("controlPhysicalContractVersion", VERSION);
        result.put("authoritativePhysicalBackend", true);
        result.put("sessionReady", sessionReady);
        result.put("supportsCheapMonitor", true);
        result.put("supportsScopedPlannerState", true);
        result.put("supportsBudgetAwarePlannerState", true);
        result.put("implementedInCodeScopedPlannerState", true);
        result.put("implementedInCodeBudgetAwarePlannerState", true);
        result.put("supportsContactPlan", true);
        result.put("supportsTopologySnapshot", true);
        result.put("supportsConfigurationApply", true);
        result.put("supportsConfigurationPatch", true);
        result.put("configurationApplySemantics", Arrays.asList(
                "BOOTSTRAP_CONFIGURATION", "IDEMPOTENT_REAPPLY", "COMPATIBILITY_FULL_APPLY_NON_PUBLICATION"));
        result.put("publicationInterventionPath", "server_validated_configuration_patch_atomic_native_transaction");
        result.put("requiresServerValidationReceiptForStrictPatch", true);
        result.put("supportsServerValidationReceipt", true);
        result.put("supportsAtomicPatchTransaction", true);
        result.put("supportsNativeRollbackSnapshot", true);
        result.put("supportsDeferredUnassignedResourceIntent", true);
        result.put("supportsPersistentRuleRuntimeEffectEvidence", true);
        result.put("supportsPersistentConfigurationExecution", true);
        result.put("supportsPersistentNativeResourceActuation", true);
        result.put("supportsPersistentRouteActuation", false);
        result.put("supportsTaskTargetMigration", false);
        result.put("supportsDynamicPriorityActuation", false);
        result.put("supportsActualAppliedInterventionEvidence", true);
        result.put("supportsProtocolEvents", true);
        result.put("supportsDynamicValidationReport", true);
        result.put("strictPhysicalClaimsDefault", true);
        result.put("supportsConfigurationDispatch", true);
        result.put("supportsPhysicalDecisionDelay", true);
        result.put("supportsAdvanceWorld", true);
        result.put("supportsControlMonitoringEpoch", true);
        result.put("supportsControlEpochResume", true);
        result.put("supportsConfigurationValidation", true);
        boolean nativeContactObserved = instrumentation != null
                && Boolean.TRUE.equals(instrumentation.get("nativeContactInterruptionObserved"))
                && Boolean.TRUE.equals(instrumentation.get("nativeContactInterruptionEvidenceConsistent"));
        result.put("supportsMidTransferContactEnforcement", sessionReady && nativeContactObserved);
        result.put("nativeContactInterruptionCapabilityRequiresObservedEvent", true);
        Map<String, Object> cpu = nestedMap(instrumentation, "cpuConservation");
        Map<String, Object> bandwidth = nestedMap(instrumentation, "bandwidthConservation");
        boolean cpuObserved = Boolean.TRUE.equals(cpu.get("observed"));
        boolean cpuConserved = Boolean.TRUE.equals(cpu.get("conservationSatisfied"));
        boolean bandwidthObserved = Boolean.TRUE.equals(bandwidth.get("observed"));
        boolean bandwidthConserved = Boolean.TRUE.equals(bandwidth.get("conservationSatisfied"));
        result.put("supportsCpuConservationEvidence", cpuObserved && cpuConserved);
        result.put("supportsBandwidthConservationEvidence", bandwidthObserved && bandwidthConserved);
        result.put("runtimeObservedCpuConservationEvidence", cpuObserved);
        result.put("runtimeObservedBandwidthConservationEvidence", bandwidthObserved);
        result.put("publicationEligibleForCpuConservation", cpuObserved && cpuConserved);
        result.put("publicationEligibleForBandwidthConservation", bandwidthObserved && bandwidthConserved);
        result.put("supportsPerLinkBandwidthAllocation", false);
        result.put("bandwidthBindingScope", "shared_lan_domain_and_global_wan");
        result.put("futureStochasticTruthExposed", false);
        result.put("topologySource", "TopologyOracle");
        result.put("monitorSource", "CheapMonitorState");
        result.put("plannerStateSource", "PendingDecisionContext -> RlStateBuilder.selected_candidates");
        result.put("physicalDecisionDelaySemanticsVersion", "cloudsim_pause_at_control_epoch_v1");
        result.put("configurationSemanticsVersion", "v3-execution-configuration-patch");
        result.put("configurationPatchSemanticsVersion", "base-version-scoped-native-application-v1");
        result.put("scopeDimensions", Arrays.asList("task_ids", "source_ids", "node_ids", "link_ids", "route_ids", "resource_keys"));
        result.put("supportedAcquisitionBudgetDimensions", Arrays.asList("max_candidate_count"));
        result.put("supportedPlannerBudgetDimensions", Arrays.asList("max_planner_evaluations", "max_compute_budget", "time_budget_ms"));
        result.put("supportedCoordinationBudgetDimensions", Arrays.asList("max_coordination_bytes"));
        result.put("budgetDimensions", Arrays.asList("max_candidate_count"));
        result.put("persistentRuleDimensions", Arrays.asList("source", "application", "traffic", "flow", "default", "node", "route", "resource", "task_override"));
        result.put("persistentConfigurationBindingDimensions", Arrays.asList("target_vm", "cpuShare", "bandwidthShare", "txPowerRatio"));
        result.put("resourceAllocationEvidenceDimensions", Arrays.asList("entity", "requested", "effective", "capacity", "contention", "timestamp"));
        result.put("executionConfigurationDimensions", Arrays.asList("assignments", "routes", "cpuAllocations", "bandwidthAllocations", "priorities", "associatedPersistentRules", "provenance"));
        result.put("configurationPatchDimensions", Arrays.asList("taskAssignmentChanges", "routeChanges", "resourceChanges", "priorityChanges", "preserveResumeRecompute", "baseConfigurationVersion", "baseWorldVersion", "observedWorldVersion", "observedControlEpoch", "revalidatedWorldVersion", "planningDelayMetadata", "acquisitionMetadata"));
        result.put("instrumentation", instrumentation == null ? new LinkedHashMap<String, Object>() : instrumentation);
        Map<String, Object> acquisition = nestedMap(instrumentation, "acquisition");
        boolean noLegacyContamination = !Boolean.TRUE.equals(acquisition.get("legacyFullStateAccessObserved"));
        boolean causal = Boolean.TRUE.equals(acquisition.get("scopeBudgetCausalityProven"));
        boolean scopedObserved = "native_scoped_candidate_acquisition".equals(acquisition.get("mode"))
                && acquisition.get("decisionId") != null
                && number(acquisition.get("candidateEvaluatedCount"), -1L) > 0L;
        result.put("runtimeObservedScopedPlannerState", scopedObserved);
        result.put("legacyFullStateAccessObserved", Boolean.TRUE.equals(acquisition.get("legacyFullStateAccessObserved")));
        result.put("publicationEligibleForScopedPlannerState", scopedObserved && causal && noLegacyContamination);
        result.put("publicationEligibleForBudgetAwarePlannerState", scopedObserved && causal && noLegacyContamination
                && Boolean.TRUE.equals(acquisition.get("budgetRestrictionAppliedBeforeEvaluation")));
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nestedMap(Map<String, Object> parent, String key) {
        if (parent == null || !(parent.get(key) instanceof Map)) return new LinkedHashMap<String, Object>();
        return (Map<String, Object>) parent.get(key);
    }

    private static long number(Object value, long fallback) {
        return value instanceof Number ? ((Number) value).longValue() : fallback;
    }
}
