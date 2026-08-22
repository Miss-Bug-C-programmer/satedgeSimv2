package edu.weijunyong.satedgesim.server;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Canonical runtime representation of the active execution configuration Π_k.
 *
 * <p>The object is also the compatibility source for the older persistent-rule
 * contract. Assignment/resource maps are the state consumed by the decision
 * bridge and native binding manager; they are not an audit-only planner DTO.</p>
 */
public class ExecutionConfiguration {
    public String configId = "default";
    public long version = 0L;
    public double creationSimTimeSec = Double.NaN;
    public double lastUpdateSimTimeSec = Double.NaN;
    public double configuredLifetimeSec = Double.NaN;
    public double expiresAtSimTimeSec = Double.NaN;
    public long worldVersion = 0L;
    public String sourceStateId;
    public String sourceDecisionId;

    /** X_k: task/source keyed execution placement assignments. */
    public Map<String, Object> assignments = new LinkedHashMap<String, Object>();
    /** Reusable persistent rules used by the existing future-task dispatcher. */
    public Map<String, Object> reusableRules = new LinkedHashMap<String, Object>();
    /** R_k: task/source keyed native resource allocations. */
    public Map<String, Object> resourceAllocations = new LinkedHashMap<String, Object>();
    public Map<String, Object> cpuAllocations = new LinkedHashMap<String, Object>();
    public Map<String, Object> bandwidthAllocations = new LinkedHashMap<String, Object>();
    /** P_k: route/policy values. Route actuation remains explicitly unsupported. */
    public Map<String, Object> routes = new LinkedHashMap<String, Object>();
    public Map<String, Object> priorities = new LinkedHashMap<String, Object>();
    public Map<String, Object> provenance = new LinkedHashMap<String, Object>();
    public Map<String, Object> metadata = new LinkedHashMap<String, Object>();

    public ExecutionConfiguration copy() {
        ExecutionConfiguration result = new ExecutionConfiguration();
        result.configId = configId;
        result.version = version;
        result.creationSimTimeSec = creationSimTimeSec;
        result.lastUpdateSimTimeSec = lastUpdateSimTimeSec;
        result.configuredLifetimeSec = configuredLifetimeSec;
        result.expiresAtSimTimeSec = expiresAtSimTimeSec;
        result.worldVersion = worldVersion;
        result.sourceStateId = sourceStateId;
        result.sourceDecisionId = sourceDecisionId;
        result.assignments = deepCopyMap(assignments);
        result.reusableRules = deepCopyMap(reusableRules);
        result.resourceAllocations = deepCopyMap(resourceAllocations);
        result.cpuAllocations = deepCopyMap(cpuAllocations);
        result.bandwidthAllocations = deepCopyMap(bandwidthAllocations);
        result.routes = deepCopyMap(routes);
        result.priorities = deepCopyMap(priorities);
        result.provenance = deepCopyMap(provenance);
        result.metadata = deepCopyMap(metadata);
        return result;
    }

    public boolean isExpired(double simulationTimeSec) {
        return Double.isFinite(expiresAtSimTimeSec) && simulationTimeSec >= expiresAtSimTimeSec;
    }

    public double ageAt(double simulationTimeSec) {
        double start = Double.isFinite(lastUpdateSimTimeSec) ? lastUpdateSimTimeSec : creationSimTimeSec;
        return Double.isFinite(start) ? Math.max(0.0, simulationTimeSec - start) : Double.NaN;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("configId", configId);
        result.put("version", version);
        result.put("creationSimTimeSec", finiteOrNull(creationSimTimeSec));
        result.put("lastUpdateSimTimeSec", finiteOrNull(lastUpdateSimTimeSec));
        result.put("configuredLifetimeSec", finiteOrNull(configuredLifetimeSec));
        result.put("expiresAtSimTimeSec", finiteOrNull(expiresAtSimTimeSec));
        result.put("worldVersion", worldVersion);
        result.put("sourceStateId", sourceStateId);
        result.put("sourceDecisionId", sourceDecisionId);
        result.put("assignments", deepCopyMap(assignments));
        result.put("reusableRules", deepCopyMap(reusableRules));
        result.put("resourceAllocations", deepCopyMap(resourceAllocations));
        result.put("cpuAllocations", deepCopyMap(cpuAllocations));
        result.put("bandwidthAllocations", deepCopyMap(bandwidthAllocations));
        result.put("routes", deepCopyMap(routes));
        result.put("priorities", deepCopyMap(priorities));
        result.put("provenance", deepCopyMap(provenance));
        result.put("metadata", deepCopyMap(metadata));
        return result;
    }

    /** Materializes only the rule for the current task, including resources. */
    public Object materialize(Map<String, Object> task) {
        String taskId = taskValue(task, "taskId", "task_id");
        String sourceId = taskValue(task, "sourceId", "source_id");
        Object best = null;
        if (taskId != null && assignments.containsKey(taskId)) best = assignments.get(taskId);
        else if (sourceId != null && assignments.containsKey(sourceId)) best = assignments.get(sourceId);
        else if (sourceId != null && assignments.containsKey("source:" + sourceId)) best = assignments.get("source:" + sourceId);
        if (best == null) {
            int bestScore = -1;
            for (Map.Entry<String, Object> entry : reusableRules.entrySet()) {
                if (!(entry.getValue() instanceof Map)) continue;
                Map<?, ?> rule = (Map<?, ?>) entry.getValue();
                Object selector = rule.containsKey("selector") ? rule.get("selector") : rule.get("match");
                int score = selectorScore(selector, task);
                if (score < 0) continue;
                if ("default".equalsIgnoreCase(entry.getKey())) score = Math.max(0, score);
                if (score > bestScore) {
                    bestScore = score;
                    best = rule.containsKey("assignment") ? rule.get("assignment")
                            : (rule.containsKey("action") ? rule.get("action") : rule);
                }
            }
        }
        if (best == null) best = assignments.get("default");
        if (!(best instanceof Map)) return best;
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        @SuppressWarnings("unchecked") Map<String, Object> selected = (Map<String, Object>) best;
        result.putAll(selected);
        Map<String, Object> resource = resourceAllocationFor(taskId, sourceId);
        if (resource != null) result.putAll(resource);
        return result;
    }

    private Map<String, Object> resourceAllocationFor(String taskId, String sourceId) {
        Object allocation = taskId == null ? null : resourceAllocations.get(taskId);
        if (!(allocation instanceof Map) && sourceId != null) allocation = resourceAllocations.get(sourceId);
        if (!(allocation instanceof Map) && sourceId != null) allocation = resourceAllocations.get("source:" + sourceId);
        if (!(allocation instanceof Map)) allocation = resourceAllocations.get("default");
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (allocation instanceof Map) {
            @SuppressWarnings("unchecked") Map<String, Object> map = (Map<String, Object>) allocation;
            result.putAll(map);
        }
        mergeDimension(result, cpuAllocations, taskId, sourceId, "cpuShare");
        mergeDimension(result, bandwidthAllocations, taskId, sourceId, "bandwidthShare");
        return result.isEmpty() ? null : result;
    }

    private static void mergeDimension(Map<String, Object> target, Map<String, Object> source,
            String taskId, String sourceId, String field) {
        if (source == null) return;
        Object value = taskId == null ? null : source.get(taskId);
        if (value == null && sourceId != null) value = source.get(sourceId);
        if (value == null && sourceId != null) value = source.get("source:" + sourceId);
        if (value == null) value = source.get("default");
        if (value instanceof Map && ((Map<?, ?>) value).containsKey(field)) value = ((Map<?, ?>) value).get(field);
        if (value != null) target.put(field, value);
    }

    private static int selectorScore(Object selector, Map<String, Object> task) {
        if (selector == null) return 0;
        if (!(selector instanceof Map)) return -1;
        int score = 0;
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) selector).entrySet()) {
            String key = String.valueOf(entry.getKey());
            String actual = taskValue(task, key, alias(key));
            if (actual == null || !matches(entry.getValue(), actual)) return -1;
            score++;
        }
        return score;
    }

    private static boolean matches(Object expected, String actual) {
        if (expected instanceof List) {
            for (Object value : (List<?>) expected) if (String.valueOf(value).equals(actual)) return true;
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

    protected static void populateFromRequest(ExecutionConfiguration result, Map<String, Object> request) {
        Map<String, Object> source = request == null ? new LinkedHashMap<String, Object>() : request;
        Object nested = source.get("configuration");
        if (nested instanceof Map) {
            @SuppressWarnings("unchecked") Map<String, Object> nestedMap = (Map<String, Object>) nested;
            source = nestedMap;
        }
        result.configId = stringValue(source.get("config_id"), stringValue(source.get("configId"), "default"));
        result.version = numberValue(source.get("version"), 0L);
        result.creationSimTimeSec = doubleValue(source.get("creation_sim_time_sec"), doubleValue(source.get("creationSimTimeSec"), Double.NaN));
        result.lastUpdateSimTimeSec = doubleValue(source.get("last_update_sim_time_sec"), doubleValue(source.get("lastUpdateSimTimeSec"), Double.NaN));
        result.configuredLifetimeSec = doubleValue(source.get("configured_lifetime_sec"), doubleValue(source.get("configuredLifetimeSec"), doubleValue(source.get("lifetimeSec"), Double.NaN)));
        result.expiresAtSimTimeSec = doubleValue(source.get("expires_at_sim_time_sec"), doubleValue(source.get("expiresAtSimTimeSec"), Double.NaN));
        result.worldVersion = numberValue(source.get("world_version"), numberValue(source.get("worldVersion"), 0L));
        result.sourceStateId = optionalString(source.get("source_state_id"), source.get("sourceStateId"));
        result.sourceDecisionId = optionalString(source.get("source_decision_id"), source.get("sourceDecisionId"));
        copyMap(source.get("assignments"), result.assignments);
        copyMap(source.get("reusable_rules"), result.reusableRules);
        copyMap(source.get("reusableRules"), result.reusableRules);
        copyMap(source.get("resource_allocations"), result.resourceAllocations);
        copyMap(source.get("resourceAllocations"), result.resourceAllocations);
        copyMap(source.get("cpu_allocations"), result.cpuAllocations);
        copyMap(source.get("cpuAllocations"), result.cpuAllocations);
        copyMap(source.get("bandwidth_allocations"), result.bandwidthAllocations);
        copyMap(source.get("bandwidthAllocations"), result.bandwidthAllocations);
        copyMap(source.get("routes"), result.routes);
        copyMap(source.get("route_decisions"), result.routes);
        copyMap(source.get("routeDecisions"), result.routes);
        copyMap(source.get("priorities"), result.priorities);
        copyMap(source.get("provenance"), result.provenance);
        copyMap(source.get("metadata"), result.metadata);
    }

    protected static Map<String, Object> deepCopyMap(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (source == null) return result;
        for (Map.Entry<String, Object> entry : source.entrySet()) result.put(entry.getKey(), deepCopy(entry.getValue()));
        return result;
    }

    @SuppressWarnings("unchecked")
    protected static Object deepCopy(Object value) {
        if (value instanceof Map) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) result.put(String.valueOf(entry.getKey()), deepCopy(entry.getValue()));
            return result;
        }
        if (value instanceof List) {
            List<Object> result = new ArrayList<Object>();
            for (Object item : (List<Object>) value) result.add(deepCopy(item));
            return result;
        }
        return value;
    }

    private static void copyMap(Object raw, Map<String, Object> target) {
        if (raw instanceof Map) {
            @SuppressWarnings("unchecked") Map<String, Object> map = (Map<String, Object>) raw;
            target.putAll(deepCopyMap(map));
        }
    }

    private static Object finiteOrNull(double value) { return Double.isFinite(value) ? Double.valueOf(value) : null; }
    private static String stringValue(Object value, String fallback) { return value == null ? fallback : String.valueOf(value); }
    private static String optionalString(Object first, Object second) { return first == null && second == null ? null : String.valueOf(first == null ? second : first); }
    private static long numberValue(Object value, long fallback) { return value instanceof Number ? ((Number) value).longValue() : fallback; }
    private static double doubleValue(Object value, double fallback) { return value instanceof Number ? ((Number) value).doubleValue() : fallback; }
}
