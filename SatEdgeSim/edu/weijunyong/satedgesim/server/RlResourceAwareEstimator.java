package edu.weijunyong.satedgesim.server;

import java.util.LinkedHashMap;
import java.util.Map;

public final class RlResourceAwareEstimator {
    public static final double DEFAULT_MAX_TX_POWER_W = 1.0;

    private RlResourceAwareEstimator() {
    }

    public static Estimate estimate(
            double nativeVmMips,
            double nativeLinkBandwidthMbps,
            double baseTransmissionDelaySec,
            double baseComputeDelaySec,
            double baseQueueDelaySec,
            double propagationDelaySec,
            boolean local,
            RlResourceProfile profile) {
        RlResourceProfile p = profile == null
                ? RlResourceProfile.fromAction(null, RlResourceBindingMode.candidate_only)
                : profile;
        double effectiveMips = Math.max(1.0, nativeVmMips * (p.continuousApplied ? p.cpuShareClamped : 1.0));
        double effectiveBandwidth = Math.max(1.0e-6, nativeLinkBandwidthMbps * (p.continuousApplied ? p.bandwidthShareClamped : 1.0));
        double txPowerW = DEFAULT_MAX_TX_POWER_W * (p.continuousApplied ? p.txPowerRatioClamped : 1.0);
        double computeDelay = baseComputeDelaySec;
        if (p.continuousApplied) {
            computeDelay = baseComputeDelaySec / Math.max(1.0e-6, p.cpuShareClamped);
        }
        double txDelay = local ? 0.0 : baseTransmissionDelaySec;
        if (!local && p.continuousApplied) {
            txDelay = baseTransmissionDelaySec / Math.max(1.0e-6, p.bandwidthShareClamped);
        }
        double queueDelay = p.continuousApplied ? baseQueueDelaySec / Math.max(1.0e-6, p.cpuShareClamped) : baseQueueDelaySec;
        double computeEnergy = 1.0e-6 * effectiveMips * computeDelay;
        double txEnergy = local ? 0.0 : txPowerW * txDelay;
        Estimate out = new Estimate();
        out.effectiveMips = effectiveMips;
        out.effectiveBandwidthMbps = effectiveBandwidth;
        out.txPowerW = txPowerW;
        out.expectedTxDelaySec = txDelay;
        out.expectedComputeDelaySec = computeDelay;
        out.expectedQueueDelaySec = queueDelay;
        out.expectedDelaySec = Math.max(0.0, propagationDelaySec) + txDelay + computeDelay + queueDelay;
        out.expectedEnergyJ = Math.max(0.0, computeEnergy + txEnergy);
        return out;
    }

    public static class Estimate {
        public double effectiveMips;
        public double effectiveBandwidthMbps;
        public double txPowerW;
        public double expectedTxDelaySec;
        public double expectedComputeDelaySec;
        public double expectedQueueDelaySec;
        public double expectedDelaySec;
        public double expectedEnergyJ;

        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("effectiveMips", effectiveMips);
            out.put("effectiveBandwidthMbps", effectiveBandwidthMbps);
            out.put("txPowerW", txPowerW);
            out.put("expectedTxDelaySec", expectedTxDelaySec);
            out.put("expectedComputeDelaySec", expectedComputeDelaySec);
            out.put("expectedQueueDelaySec", expectedQueueDelaySec);
            out.put("expectedDelaySec", expectedDelaySec);
            out.put("expectedEnergyJ", expectedEnergyJ);
            return out;
        }
    }
}

