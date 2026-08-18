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
        result.put("supportsPersistentConfigurationExecution", true);
        result.put("supportsPersistentNativeResourceActuation", true);
        result.put("supportsPersistentRouteActuation", false);
        result.put("supportsConfigurationDispatch", true);
        result.put("supportsPhysicalDecisionDelay", true);
        result.put("supportsAdvanceWorld", true);
        result.put("supportsConfigurationValidation", true);
        result.put("supportsMidTransferContactEnforcement", false);
        result.put("futureStochasticTruthExposed", false);
        result.put("topologySource", "TopologyOracle");
        result.put("monitorSource", "CheapMonitorState");
        result.put("plannerStateSource", "RlStateBuilder.pending_decision_cache");
        result.put("physicalDecisionDelaySemanticsVersion", "cloudsim_pause_at_v1");
        result.put("configurationSemanticsVersion", "v2-persistent-reusable-rules");
        result.put("scopeDimensions", Arrays.asList("task_ids", "source_ids", "node_ids", "link_ids", "route_ids", "resource_keys"));
        result.put("budgetDimensions", Arrays.asList("max_candidate_count", "max_planner_evaluations", "max_coordination_bytes", "max_compute_budget", "time_budget_ms"));
        result.put("persistentRuleDimensions", Arrays.asList("source", "application", "traffic", "flow", "default", "node", "route", "resource", "task_override"));
        result.put("persistentConfigurationBindingDimensions", Arrays.asList("target_vm", "cpuShare", "bandwidthShare", "txPowerRatio"));
        result.put("instrumentation", instrumentation == null ? new LinkedHashMap<String, Object>() : instrumentation);
        return result;
    }
}
