package edu.weijunyong.satedgesim.server;

import java.util.Map;

public class RlResourceBindingModeTest {
    public static void main(String[] args) {
        RlAction action = new RlAction();
        action.cpuShare = 0.5;
        action.bandwidthShare = 0.5;
        action.txPowerRatio = 0.5;

        RlResourceProfile estimator = RlResourceProfile.fromAction(
                action,
                RlResourceBindingMode.resource_aware_estimator_bound);
        require(estimator.estimatorBound(), "estimator-bound profile must be allowed");
        require(!estimator.nativeSchedulerBound(), "estimator-bound profile must not claim native binding");
        Map<String, Object> estimatorMetadata = RlResourceBindingAudit.metadata(estimator);
        require("resource_aware_estimator_bound".equals(estimatorMetadata.get("resource_binding_mode")),
                "metadata must expose estimator resource_binding_mode");
        require(Boolean.FALSE.equals(estimatorMetadata.get("native_scheduler_bound")),
                "estimator metadata must not claim native scheduler binding");
        require(Boolean.FALSE.equals(estimatorMetadata.get("lower_continuous_allocator_validated_by_satedgesim")),
                "estimator-bound replay must not validate lower continuous allocator natively");

        RlResourceProfile nativeProfile = RlResourceProfile.fromAction(action, RlResourceBindingMode.native_scheduler_bound);
        require(nativeProfile.nativeSchedulerBound(), "native profile must report native scheduler binding mode");
        require(nativeProfile.continuousApplied, "native profile must apply continuous resources");
        Map<String, Object> nativeMetadata = RlResourceBindingAudit.metadata(nativeProfile);
        require(Boolean.TRUE.equals(nativeMetadata.get("native_scheduler_bound")),
                "native metadata must claim native scheduler binding");
        require(Boolean.TRUE.equals(nativeMetadata.get("lower_continuous_allocator_validated_by_satedgesim")),
                "native metadata must allow lower continuous allocator validation");
        require(Boolean.TRUE.equals(nativeMetadata.get("full_hybrid_closed_loop_claim_allowed")),
                "native metadata must allow full closed-loop claims");
        require("vm_mips_scoped_min_active_share".equals(nativeMetadata.get("native_cpu_binding_scope")),
                "native metadata must expose CPU binding scope");
        require("file_transfer_progress_bandwidth_share".equals(nativeMetadata.get("native_network_binding_scope")),
                "native metadata must expose network binding scope");
        require("wireless_transmission_energy_ratio".equals(nativeMetadata.get("native_tx_power_binding_scope")),
                "native metadata must expose power binding scope");

        System.out.println("RlResourceBindingModeTest OK");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
