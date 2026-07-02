package edu.weijunyong.satedgesim.server;

import java.util.LinkedHashMap;
import java.util.Map;

public final class RlResourceBindingAudit {
    private RlResourceBindingAudit() {
    }

    public static Map<String, Object> metadata(RlResourceProfile profile) {
        RlResourceProfile p = profile == null
                ? RlResourceProfile.fromAction(null, RlResourceBindingMode.candidate_only)
                : profile;
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("resource_binding_mode", p.bindingMode.toString());
        out.put("continuous_resource_binding_mode", p.bindingMode.toString());
        out.put("continuous_resource_applied", p.continuousApplied);
        out.put("native_scheduler_bound", p.nativeSchedulerBound());
        out.put("estimator_bound", p.estimatorBound());
        out.put("full_hybrid_closed_loop_claim_allowed", p.nativeSchedulerBound());
        out.put("lower_continuous_allocator_validated_by_satedgesim", p.nativeSchedulerBound());
        out.put("native_cpu_binding_scope", RlNativeResourceBindingSemantics.CPU_BINDING_SCOPE);
        out.put("native_network_binding_scope", RlNativeResourceBindingSemantics.NETWORK_BINDING_SCOPE);
        out.put("native_tx_power_binding_scope", RlNativeResourceBindingSemantics.TX_POWER_BINDING_SCOPE);
        out.put("table5_title_suggestion", p.nativeSchedulerBound()
                ? "SatEdgeSim native VM/network/power-bound replay"
                : (p.estimatorBound()
                        ? "SatEdgeSim resource-aware estimator-bound replay"
                        : "SatEdgeSim candidate-level action-mapping replay"));
        return out;
    }
}
