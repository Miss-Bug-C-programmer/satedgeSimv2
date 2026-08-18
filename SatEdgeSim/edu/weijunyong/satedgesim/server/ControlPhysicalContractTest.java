package edu.weijunyong.satedgesim.server;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import edu.weijunyong.satedgesim.TasksGenerator.Task;

/** Small dependency-free regression test for the v2 contract primitives. */
public class ControlPhysicalContractTest {
    public static void main(String[] args) {
        Map<String, Object> capabilities = ControlPhysicalContract.capabilities(false, null);
        require("2.1".equals(capabilities.get("controlPhysicalContractVersion")), "contract version");
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
        require(Boolean.FALSE.equals(monitorMap.get("serviceBoundCertified")), "service bound must be uncertified by default");
        require(monitorMap.get("serviceRateLowerBound") == null, "missing lower bound must remain missing");
        require("not_certified".equals(monitorMap.get("serviceBoundSemantics")), "service semantics must be explicit");

        Task unfinished = new Task(1, 100L, 1L) {
            @Override
            public long getFinishedLengthSoFar() {
                return 25L;
            }
        };
        unfinished.setTime(2.0);
        unfinished.setMaxLatency(10.0);
        Task finished = new Task(2, 50L, 1L);
        finished.setTime(1.0);
        finished.setStatus(org.cloudbus.cloudsim.cloudlets.Cloudlet.Status.SUCCESS);
        Task future = new Task(3, 200L, 1L);
        future.setTime(20.0);
        CheapMonitorState aggregated = new CheapMonitorState();
        SatEdgeSimSession.populateArrivedWorkload(aggregated, Arrays.asList(unfinished, finished, future), 5.0);
        require(value(aggregated.queueSummary, "arrivedTaskCount") == 2.0, "future task excluded from arrival count");
        require(value(aggregated.queueSummary, "unfinishedTaskCount") == 1.0, "finished task excluded from queue");
        require(value(aggregated.remainingWorkload, "total") == 75.0, "remaining workload uses unfinished length");
        require(value(aggregated.deadlineSlack, "1") == 7.0, "deadline slack uses current time");
        require(value(aggregated.instrumentation, "futureTaskCountExcluded") == 1.0, "future task exclusion is instrumented");
        System.out.println("ControlPhysicalContractTest OK");
    }

    private static double value(Map<String, ? extends Object> map, String key) {
        return ((Number) map.get(key)).doubleValue();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
