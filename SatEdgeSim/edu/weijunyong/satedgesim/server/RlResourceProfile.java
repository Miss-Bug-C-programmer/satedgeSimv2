package edu.weijunyong.satedgesim.server;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class RlResourceProfile {
    public double cpuShare = 1.0;
    public double bandwidthShare = 1.0;
    public double txPowerRatio = 1.0;
    public double cpuShareClamped = 1.0;
    public double bandwidthShareClamped = 1.0;
    public double txPowerRatioClamped = 1.0;
    public boolean continuousApplied = false;
    public RlResourceBindingMode bindingMode = RlResourceBindingMode.candidate_only;
    public double minShare = 0.10;
    public double maxShare = 1.0;
    public List<String> validationWarnings = new ArrayList<String>();

    public static RlResourceProfile fromAction(RlAction action, RlResourceBindingMode requestedMode) {
        RlResourceProfile profile = new RlResourceProfile();
        if (action == null) {
            profile.validationWarnings.add("missing_action_default_resource_profile");
        } else {
            profile.cpuShare = action.cpuShare;
            profile.bandwidthShare = action.bandwidthShare;
            profile.txPowerRatio = action.txPowerRatio;
        }
        profile.cpuShareClamped = clamp(profile.cpuShare, profile.minShare, profile.maxShare);
        profile.bandwidthShareClamped = clamp(profile.bandwidthShare, profile.minShare, profile.maxShare);
        profile.txPowerRatioClamped = clamp(profile.txPowerRatio, profile.minShare, profile.maxShare);
        if (profile.cpuShare != profile.cpuShareClamped) {
            profile.validationWarnings.add("cpuShare_clamped");
        }
        if (profile.bandwidthShare != profile.bandwidthShareClamped) {
            profile.validationWarnings.add("bandwidthShare_clamped");
        }
        if (profile.txPowerRatio != profile.txPowerRatioClamped) {
            profile.validationWarnings.add("txPowerRatio_clamped");
        }
        profile.bindingMode = requestedMode == null ? RlResourceBindingMode.candidate_only : requestedMode;
        profile.continuousApplied = profile.bindingMode == RlResourceBindingMode.resource_aware_estimator_bound
                || profile.bindingMode == RlResourceBindingMode.native_scheduler_bound;
        return profile;
    }

    public boolean nativeSchedulerBound() {
        return bindingMode == RlResourceBindingMode.native_scheduler_bound;
    }

    public boolean estimatorBound() {
        return bindingMode == RlResourceBindingMode.resource_aware_estimator_bound;
    }

    public boolean hasInvalidNumericValue() {
        return !Double.isFinite(cpuShare) || !Double.isFinite(bandwidthShare) || !Double.isFinite(txPowerRatio)
                || !Double.isFinite(cpuShareClamped) || !Double.isFinite(bandwidthShareClamped)
                || !Double.isFinite(txPowerRatioClamped)
                || cpuShare < minShare || cpuShare > maxShare
                || bandwidthShare < minShare || bandwidthShare > maxShare
                || txPowerRatio < minShare || txPowerRatio > maxShare;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("cpuShare", cpuShare);
        out.put("bandwidthShare", bandwidthShare);
        out.put("txPowerRatio", txPowerRatio);
        out.put("cpuShareClamped", cpuShareClamped);
        out.put("bandwidthShareClamped", bandwidthShareClamped);
        out.put("txPowerRatioClamped", txPowerRatioClamped);
        out.put("continuousApplied", continuousApplied);
        out.put("bindingMode", bindingMode.toString());
        out.put("nativeSchedulerBound", nativeSchedulerBound());
        out.put("estimatorBound", estimatorBound());
        out.put("minShare", minShare);
        out.put("maxShare", maxShare);
        out.put("validationWarnings", new ArrayList<String>(validationWarnings));
        return out;
    }

    private static double clamp(double value, double min, double max) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return Double.NaN;
        }
        return Math.max(min, Math.min(max, value));
    }
}
