package edu.weijunyong.satedgesim.server;

import java.util.LinkedHashMap;
import java.util.Map;

/** Server-owned, single-use authorization record for one exact patch intent. */
public final class ValidationReceipt {
    public final String validationReceiptId;
    public final String sessionId;
    public final String interventionId;
    public final long baseConfigurationVersion;
    public final long observedWorldVersion;
    public final long validatedWorldVersion;
    public final long controlStateRevision;
    public final String worldIdentityDigest;
    public final String requestedScopeDigest;
    public final String patchDigest;
    public final String physicalAdvanceReceiptId;
    public final double validationSimulationTimeSec;
    public final long issuedAtWallClockMs;
    public final long expiresAtWallClockMs;
    public final boolean validatedAfterPhysicalAdvance;
    public final boolean singleUse;
    public boolean consumed;

    ValidationReceipt(String validationReceiptId, String sessionId, String interventionId,
            long baseConfigurationVersion, long observedWorldVersion, long validatedWorldVersion,
            long controlStateRevision, String worldIdentityDigest, String requestedScopeDigest,
            String patchDigest, String physicalAdvanceReceiptId, double validationSimulationTimeSec,
            long issuedAtWallClockMs, long expiresAtWallClockMs, boolean validatedAfterPhysicalAdvance,
            boolean singleUse) {
        this.validationReceiptId = validationReceiptId;
        this.sessionId = sessionId;
        this.interventionId = interventionId;
        this.baseConfigurationVersion = baseConfigurationVersion;
        this.observedWorldVersion = observedWorldVersion;
        this.validatedWorldVersion = validatedWorldVersion;
        this.controlStateRevision = controlStateRevision;
        this.worldIdentityDigest = worldIdentityDigest;
        this.requestedScopeDigest = requestedScopeDigest;
        this.patchDigest = patchDigest;
        this.physicalAdvanceReceiptId = physicalAdvanceReceiptId;
        this.validationSimulationTimeSec = validationSimulationTimeSec;
        this.issuedAtWallClockMs = issuedAtWallClockMs;
        this.expiresAtWallClockMs = expiresAtWallClockMs;
        this.validatedAfterPhysicalAdvance = validatedAfterPhysicalAdvance;
        this.singleUse = singleUse;
    }

    public boolean expired(long nowMs) {
        return expiresAtWallClockMs > 0L && nowMs >= expiresAtWallClockMs;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("validationReceiptId", validationReceiptId);
        out.put("validationReceiptToken", validationReceiptId);
        out.put("sessionId", sessionId);
        out.put("interventionId", interventionId);
        out.put("baseConfigurationVersion", baseConfigurationVersion);
        out.put("observedWorldVersion", observedWorldVersion);
        out.put("validatedWorldVersion", validatedWorldVersion);
        out.put("controlStateRevision", controlStateRevision);
        out.put("worldIdentityDigest", worldIdentityDigest);
        out.put("requestedScopeDigest", requestedScopeDigest);
        out.put("patchDigest", patchDigest);
        out.put("physicalAdvanceReceiptId", physicalAdvanceReceiptId);
        out.put("validationSimulationTimeSec", validationSimulationTimeSec);
        out.put("issuedAtWallClockMs", issuedAtWallClockMs);
        out.put("expiresAtWallClockMs", expiresAtWallClockMs);
        out.put("validatedAfterPhysicalAdvance", validatedAfterPhysicalAdvance);
        out.put("singleUse", singleUse);
        out.put("consumed", consumed);
        out.put("validationResult", "VALIDATED");
        return out;
    }
}
