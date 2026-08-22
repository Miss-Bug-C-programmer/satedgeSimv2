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
        result.put("supportsContactPlan", true);
        result.put("supportsTopologySnapshot", true);
        result.put("supportsConfigurationApply", true);
        result.put("supportsConfigurationPatch", true);
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
                && Boolean.TRUE.equals(instrumentation.get("nativeContactInterruptionObserved"));
        result.put("supportsMidTransferContactEnforcement", sessionReady && nativeContactObserved);
        result.put("nativeContactInterruptionCapabilityRequiresObservedEvent", true);
        result.put("supportsCpuConservationEvidence", instrumentation != null
                && instrumentation.get("cpuConservation") instanceof Map);
        result.put("supportsBandwidthConservationEvidence", instrumentation != null
                && instrumentation.get("bandwidthConservation") instanceof Map);
        result.put("supportsPerLinkBandwidthAllocation", false);
        result.put("bandwidthBindingScope", "shared_lan_domain_and_global_wan");
        result.put("futureStochasticTruthExposed", false);
        result.put("topologySource", "TopologyOracle");
        result.put("monitorSource", "CheapMonitorState");
        result.put("plannerStateSource", "RlStateBuilder.pending_decision_cache");
        result.put("physicalDecisionDelaySemanticsVersion", "cloudsim_pause_at_control_epoch_v1");
        result.put("configurationSemanticsVersion", "v3-execution-configuration-patch");
        result.put("configurationPatchSemanticsVersion", "base-version-scoped-native-application-v1");
        result.put("scopeDimensions", Arrays.asList("task_ids", "source_ids", "node_ids", "link_ids", "route_ids", "resource_keys"));
        result.put("budgetDimensions", Arrays.asList("max_candidate_count", "max_planner_evaluations", "max_coordination_bytes", "max_compute_budget", "time_budget_ms"));
        result.put("persistentRuleDimensions", Arrays.asList("source", "application", "traffic", "flow", "default", "node", "route", "resource", "task_override"));
        result.put("persistentConfigurationBindingDimensions", Arrays.asList("target_vm", "cpuShare", "bandwidthShare", "txPowerRatio"));
        result.put("resourceAllocationEvidenceDimensions", Arrays.asList("entity", "requested", "effective", "capacity", "contention", "timestamp"));
        result.put("executionConfigurationDimensions", Arrays.asList("assignments", "routes", "cpuAllocations", "bandwidthAllocations", "priorities", "associatedPersistentRules", "provenance"));
        result.put("configurationPatchDimensions", Arrays.asList("taskAssignmentChanges", "routeChanges", "resourceChanges", "priorityChanges", "preserveResumeRecompute", "baseConfigurationVersion", "baseWorldVersion", "observedWorldVersion", "observedControlEpoch", "revalidatedWorldVersion", "planningDelayMetadata", "acquisitionMetadata"));
        result.put("instrumentation", instrumentation == null ? new LinkedHashMap<String, Object>() : instrumentation);
        return result;
    }
}
