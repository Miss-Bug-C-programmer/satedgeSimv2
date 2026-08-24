package edu.weijunyong.satedgesim.server;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Deterministic server/client-independent digest for a patch intent. */
public final class ConfigurationPatchDigest {
    private ConfigurationPatchDigest() {
    }

    public static String patch(ConfigurationPatch patch) {
        if (patch == null) return sha256("null");
        java.util.LinkedHashMap<String, Object> intent = new java.util.LinkedHashMap<String, Object>();
        intent.put("baseConfigurationVersion", patch.baseConfigurationVersion);
        intent.put("baseWorldVersion", patch.baseWorldVersion);
        intent.put("observedWorldVersion", patch.observedWorldVersion);
        intent.put("observedControlEpoch", patch.observedControlEpoch);
        intent.put("revalidatedWorldVersion", patch.revalidatedWorldVersion);
        intent.put("acquisitionEpoch", patch.acquisitionEpoch);
        intent.put("requestedScope", patch.requestedScope);
        intent.put("taskAssignmentChanges", patch.taskAssignmentChanges);
        intent.put("routeChanges", patch.routeChanges);
        intent.put("resourceChanges", patch.resourceChanges);
        intent.put("cpuAllocationChanges", patch.cpuAllocationChanges);
        intent.put("bandwidthAllocationChanges", patch.bandwidthAllocationChanges);
        intent.put("priorityChanges", patch.priorityChanges);
        intent.put("persistentRuleChanges", patch.persistentRuleChanges);
        intent.put("preserveResumeRecompute", patch.preserveResumeRecompute);
        intent.put("originatingPlannerId", patch.originatingPlannerId);
        intent.put("originatingInterventionId", patch.originatingInterventionId);
        intent.put("provenance", patch.provenance);
        intent.put("planningDelayMetadata", patch.planningDelayMetadata);
        intent.put("acquisitionMetadata", patch.acquisitionMetadata);
        intent.put("physicalAdvanceReceiptId", patch.physicalAdvanceReceiptId);
        intent.put("strict", patch.strict);
        return sha256(canonical(intent));
    }

    public static String scope(Map<String, Object> scope) {
        return sha256(canonical(scope == null ? Collections.emptyMap() : scope));
    }

    public static String object(Object value) {
        return sha256(canonical(value));
    }

    private static String canonical(Object value) {
        if (value == null) return "null";
        if (value instanceof Map) {
            List<String> keys = new ArrayList<String>();
            for (Object key : ((Map<?, ?>) value).keySet()) keys.add(String.valueOf(key));
            Collections.sort(keys);
            StringBuilder out = new StringBuilder("{");
            for (String key : keys) {
                out.append(quote(key)).append(':');
                Object child = null;
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                    if (key.equals(String.valueOf(entry.getKey()))) {
                        child = entry.getValue();
                        break;
                    }
                }
                out.append(canonical(child)).append(';');
            }
            return out.append('}').toString();
        }
        if (value instanceof List) {
            StringBuilder out = new StringBuilder("[");
            for (Object item : (List<?>) value) out.append(canonical(item)).append(';');
            return out.append(']').toString();
        }
        if (value instanceof Number) {
            Number number = (Number) value;
            double asDouble = number.doubleValue();
            if (Double.isNaN(asDouble) || Double.isInfinite(asDouble)) return "number:" + number.toString();
            return "number:" + number.toString();
        }
        if (value instanceof Boolean) return "boolean:" + value.toString();
        return "string:" + quote(String.valueOf(value));
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte item : digest) out.append(String.format("%02x", Byte.valueOf(item)));
            return out.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is required for patch identity", error);
        }
    }
}
