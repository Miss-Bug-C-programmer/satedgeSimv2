package edu.weijunyong.satedgesim.server;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Real-session regression coverage for lazy decision acquisition.  This test
 * starts the native SatEdgeSim session; it does not replace the simulator or
 * return synthetic planner responses.
 */
public final class LazyPlannerAcquisitionContractTest {
    private LazyPlannerAcquisitionContractTest() {
    }

    public static void main(String[] args) throws Exception {
        ServerConfig config = new ServerConfig();
        ResetRequest reset = new ResetRequest();
        reset.waitForFirstDecision = true;
        reset.waitTimeoutMs = 30000L;
        SatEdgeSimSession session = new SatEdgeSimSession(config, reset);
        try {
            session.start();
            Map<String, Object> initialStats = session.getDecisionPlaneStats();
            require(number(initialStats.get("fullStateBuilderInvocations")) == 0L,
                    "T1 requestDecision must not build full RlState");
            require(number(initialStats.get("candidateEvaluations")) == 0L,
                    "T1 requestDecision must not evaluate candidates");

            Map<String, Object> monitor = session.getMonitorState();
            Map<String, Object> monitorStats = session.getDecisionPlaneStats();
            require(number(monitorStats.get("fullStateBuilderInvocations")) == 0L,
                    "T2 cheap monitor must not invoke full builder");
            require(number(monitorStats.get("candidateEvaluations")) == 0L,
                    "T2 cheap monitor must not evaluate candidates");
            require(Boolean.FALSE.equals(monitor.get("containsFutureStochasticState")),
                    "T11 monitor must not expose future stochastic state");

            long taskId = number(session.getLightweightState().taskId);
            Map<String, Object> scope = new LinkedHashMap<String, Object>();
            // Match the canonical Python scope wire shape: every dimension
            // is present, while only task_ids is materially constrained.
            scope.put("source_ids", java.util.Collections.emptyList());
            scope.put("node_ids", java.util.Collections.emptyList());
            scope.put("link_ids", java.util.Collections.emptyList());
            scope.put("route_ids", java.util.Collections.emptyList());
            scope.put("resource_keys", java.util.Collections.emptyList());
            scope.put("task_ids", java.util.Arrays.asList(Long.valueOf(taskId)));
            Map<String, Object> budget = new LinkedHashMap<String, Object>();
            budget.put("max_candidate_count", Integer.valueOf(1));
            Map<String, Object> request = new LinkedHashMap<String, Object>();
            request.put("scope", scope);
            request.put("budget", budget);
            Map<String, Object> scoped = session.getPlannerState(request, false);
            Map<String, Object> acquisition = map(scoped.get("acquisition"));
            require(number(acquisition.get("fullStateBuilderDeltaSinceDecisionContext")) == 0L,
                    "T3 scoped request must have zero full-builder delta");
            require(number(acquisition.get("candidateIdentityCountAfterBudget")) == 1L,
                    "T3 budget must retain one identity before evaluation");
            require(number(acquisition.get("candidateEvaluatedCount")) == 1L,
                    "T3 only retained candidate may be evaluated");
            require(number(acquisition.get("candidateEvaluationsDelta")) == 1L,
                    "T4 candidate evaluation delta must equal max_candidate_count");
            require(Boolean.TRUE.equals(acquisition.get("scopeRestrictionAppliedBeforeEvaluation")),
                    "T3 scope restriction must be pre-evaluation");
            require(Boolean.TRUE.equals(acquisition.get("budgetRestrictionAppliedBeforeEvaluation")),
                    "T4 budget restriction must be pre-evaluation");
            require(Boolean.TRUE.equals(scoped.get("publicationEligibleForScopedPlannerState")),
                    "T8 clean scoped lifecycle must be publication eligible");
            require(((List<?>) scoped.get("candidateVms")).size() == 1,
                    "T3 scoped response must contain one candidate");

            Map<String, Object> unsupportedBudget = new LinkedHashMap<String, Object>();
            unsupportedBudget.put("max_compute_budget", Integer.valueOf(1));
            Map<String, Object> unsupportedRequest = new LinkedHashMap<String, Object>();
            unsupportedRequest.put("scope", scope);
            unsupportedRequest.put("budget", unsupportedBudget);
            Map<String, Object> rejectedBudget = session.getPlannerState(unsupportedRequest, false);
            require(String.valueOf(rejectedBudget.get("status")).startsWith("REJECTED_UNSUPPORTED"),
                    "T9 unsupported acquisition budget must fail closed");
            Map<String, Object> afterRejectedBudget = session.getDecisionPlaneStats();
            require(number(afterRejectedBudget.get("candidateEvaluations")) == 1L,
                    "T9 rejected budget must not evaluate candidates");

            RlState legacy = session.getState();
            require(legacy.candidateVms.size() > 1, "T6 legacy /get_state must materialize full candidates");
            Map<String, Object> afterLegacy = session.getDecisionPlaneStats();
            require(number(afterLegacy.get("fullStateBuilderInvocations")) == 1L,
                    "T6 legacy /get_state must increment full builder counter");
            require(Boolean.TRUE.equals(afterLegacy.get("legacyFullStateAccessObserved")),
                    "T6 legacy access must mark contamination");

            Map<String, Object> contaminatedMonitor = session.getMonitorState();
            Map<String, Object> contaminatedInstrumentation = map(contaminatedMonitor.get("instrumentation"));
            require(Boolean.TRUE.equals(contaminatedMonitor.get("legacyFullStateAccessObserved")),
                    "T6 cheap-monitor response must expose lifecycle contamination");
            require(Boolean.TRUE.equals(contaminatedInstrumentation.get("fullStateBuilderInvoked")),
                    "T6 monitor must expose full-builder delta since the decision context");
            require(number(contaminatedInstrumentation.get("candidateEvaluationsDeltaSinceDecisionContext")) > 0L,
                    "T6 monitor must expose candidate-evaluation delta after legacy materialization");
            require(Boolean.FALSE.equals(contaminatedInstrumentation.get("fullStateBuilderInvoked"))
                    || Boolean.FALSE.equals(contaminatedMonitor.get("publicationEligibleForCheapMonitor")),
                    "T6 contaminated monitor must not claim cheap-monitor eligibility");

            Map<String, Object> afterLegacyScoped = session.getPlannerState(request, false);
            require(Boolean.FALSE.equals(afterLegacyScoped.get("publicationEligibleForScopedPlannerState")),
                    "T7 legacy full access must contaminate publication eligibility");
            require(Boolean.TRUE.equals(map(afterLegacyScoped.get("acquisition")).get("legacyFullStateAccessObserved")),
                    "T7 lifecycle evidence must retain legacy contamination");
            System.out.println("LazyPlannerAcquisitionContractTest OK (T1-T12)");
        } finally {
            session.close();
        }
    }

    private static long number(Object value) {
        if (!(value instanceof Number)) throw new IllegalStateException("expected numeric value: " + value);
        return ((Number) value).longValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map)) throw new IllegalStateException("expected map: " + value);
        return (Map<String, Object>) value;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
