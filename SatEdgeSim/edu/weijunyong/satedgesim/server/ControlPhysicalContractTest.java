package edu.weijunyong.satedgesim.server;

import java.util.LinkedHashMap;
import java.util.Map;

/** Small dependency-free regression test for the v2 contract primitives. */
public class ControlPhysicalContractTest {
    public static void main(String[] args) {
        Map<String, Object> capabilities = ControlPhysicalContract.capabilities(false, null);
        require("2.0".equals(capabilities.get("controlPhysicalContractVersion")), "contract version");
        require(Boolean.TRUE.equals(capabilities.get("supportsCheapMonitor")), "cheap monitor capability");
        require(Boolean.TRUE.equals(capabilities.get("supportsScopedPlannerState")), "scoped planner capability");
        require(Boolean.FALSE.equals(capabilities.get("futureStochasticTruthExposed")), "future truth must be hidden");

        PersistentExecutionConfiguration configuration = new PersistentExecutionConfiguration();
        configuration.configId = "cfg";
        configuration.version = 1L;
        Map<String, Object> rule = new LinkedHashMap<String, Object>();
        rule.put("selector", new LinkedHashMap<String, Object>() {{ put("source_id", "s1"); }});
        rule.put("assignment", new LinkedHashMap<String, Object>() {{ put("targetVmId", 7L); }});
        configuration.reusableRules.put("source-s1", rule);
        Map<String, Object> task = new LinkedHashMap<String, Object>();
        task.put("taskId", 42L);
        task.put("sourceId", "s1");
        Object materialized = configuration.materialize(task);
        require(materialized instanceof Map, "reusable rule must materialize");
        require("7".equals(String.valueOf(((Map<?, ?>) materialized).get("targetVmId"))), "target binding");

        CheapMonitorState monitor = new CheapMonitorState();
        monitor.instrumentation.put("candidateEvaluations", 0L);
        monitor.instrumentation.put("fullStateBuilderInvoked", false);
        Map<String, Object> monitorMap = monitor.toMap();
        require("cheap_monitor".equals(monitorMap.get("payloadKind")), "monitor payload kind");
        require(Boolean.FALSE.equals(monitorMap.get("containsFutureStochasticState")), "monitor future truth");
        System.out.println("ControlPhysicalContractTest OK");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
