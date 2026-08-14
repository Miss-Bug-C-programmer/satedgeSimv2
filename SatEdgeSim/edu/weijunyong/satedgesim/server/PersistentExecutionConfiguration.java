package edu.weijunyong.satedgesim.server;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reusable execution rules owned by the control plane and enforced by the backend. */
public final class PersistentExecutionConfiguration {
    public String configId = "default";
    public long version = 0L;
    public String sourceStateId;
    public String sourceDecisionId;
    public Map<String, Object> assignments = new LinkedHashMap<String, Object>();
    public Map<String, Object> reusableRules = new LinkedHashMap<String, Object>();
    public Map<String, Object> resourceAllocations = new LinkedHashMap<String, Object>();
    public Map<String, Object> routes = new LinkedHashMap<String, Object>();
    public Map<String, Object> metadata = new LinkedHashMap<String, Object>();

    @SuppressWarnings("unchecked")
    public static PersistentExecutionConfiguration fromRequest(Map<String, Object> request) {
        Map<String, Object> source = request == null ? new LinkedHashMap<String, Object>() : request;
        Object nested = source.get("configuration");
        if (nested instanceof Map) {
            source = (Map<String, Object>) nested;
        }
        PersistentExecutionConfiguration result = new PersistentExecutionConfiguration();
        result.configId = stringValue(source.get("config_id"), stringValue(source.get("configId"), "default"));
        result.version = numberValue(source.get("version"), 0L);
        result.sourceStateId = optionalString(source.get("source_state_id"), source.get("sourceStateId"));
        result.sourceDecisionId = optionalString(source.get("source_decision_id"), source.get("sourceDecisionId"));
        copyMap(source.get("assignments"), result.assignments);
        copyMap(source.get("reusable_rules"), result.reusableRules);
        copyMap(source.get("reusableRules"), result.reusableRules);
        copyMap(source.get("resource_allocations"), result.resourceAllocations);
        copyMap(source.get("resourceAllocations"), result.resourceAllocations);
        copyMap(source.get("routes"), result.routes);
        copyMap(source.get("metadata"), result.metadata);
        return result;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("configId", configId);
        result.put("version", version);
        result.put("sourceStateId", sourceStateId);
        result.put("sourceDecisionId", sourceDecisionId);
        result.put("assignments", assignments);
        result.put("reusableRules", reusableRules);
        result.put("resourceAllocations", resourceAllocations);
        result.put("routes", routes);
        result.put("metadata", metadata);
        return result;
    }

    public Object materialize(Map<String, Object> task) {
        String taskId = taskValue(task, "taskId", "task_id");
        if (taskId != null && assignments.containsKey(taskId)) {
            return assignments.get(taskId);
        }
        Object best = null;
        int bestScore = -1;
        for (Map.Entry<String, Object> entry : reusableRules.entrySet()) {
            if (!(entry.getValue() instanceof Map)) {
                continue;
            }
            Map<?, ?> rule = (Map<?, ?>) entry.getValue();
            Object selector = rule.containsKey("selector") ? rule.get("selector") : rule.get("match");
            int score = selectorScore(selector, task);
            if (score < 0) {
                continue;
            }
            if ("default".equalsIgnoreCase(entry.getKey())) {
                score = Math.max(0, score);
            }
            if (score > bestScore) {
                bestScore = score;
                best = rule.containsKey("assignment") ? rule.get("assignment")
                        : (rule.containsKey("action") ? rule.get("action") : rule);
            }
        }
        if (best != null) {
            return best;
        }
        return assignments.get("default");
    }

    private static int selectorScore(Object selector, Map<String, Object> task) {
        if (selector == null) {
            return 0;
        }
        if (!(selector instanceof Map)) {
            return -1;
        }
        Map<?, ?> map = (Map<?, ?>) selector;
        int score = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            String actual = taskValue(task, key, alias(key));
            if (actual == null || !matches(entry.getValue(), actual)) {
                return -1;
            }
            score++;
        }
        return score;
    }

    private static boolean matches(Object expected, String actual) {
        if (expected instanceof List) {
            for (Object value : (List<?>) expected) {
                if (String.valueOf(value).equals(actual)) return true;
            }
            return false;
        }
        return String.valueOf(expected).equals(actual);
    }

    private static String taskValue(Map<String, Object> task, String first, String second) {
        if (task == null) return null;
        Object value = task.get(first);
        if (value == null && second != null) value = task.get(second);
        return value == null ? null : String.valueOf(value);
    }

    private static String alias(String key) {
        if ("source".equals(key)) return "sourceId";
        if ("application".equals(key)) return "applicationId";
        if ("traffic".equals(key)) return "trafficPhase";
        if ("flow".equals(key)) return "flowId";
        if ("node".equals(key)) return "nodeId";
        if ("route".equals(key)) return "routeId";
        if ("resource".equals(key)) return "resourceKey";
        if (key.endsWith("_id")) return key.substring(0, key.length() - 3) + "Id";
        return key;
    }

    @SuppressWarnings("unchecked")
    private static void copyMap(Object raw, Map<String, Object> target) {
        if (raw instanceof Map) target.putAll((Map<String, Object>) raw);
    }

    private static String stringValue(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static String optionalString(Object first, Object second) {
        return first == null && second == null ? null : String.valueOf(first == null ? second : first);
    }

    private static long numberValue(Object value, long fallback) {
        return value instanceof Number ? ((Number) value).longValue() : fallback;
    }
}
