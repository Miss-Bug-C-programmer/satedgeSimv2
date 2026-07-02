package edu.weijunyong.satedgesim.server;

import java.util.LinkedHashMap;
import java.util.Map;

public class RlDecisionResult {
    public long taskId = -1L;
    public int vmIndex = -1;
    public int abstractAction = -1;
    public String resolvedTier = "";
    public String intendedTier = "";
    public boolean fallbackUsed = false;
    public RlResourceProfile resourceProfile;
    public RlNativeResourceBindingManager.BindingSnapshot nativeBinding;
    public ExecutionReceipt schedulingReceipt;
    public double decisionTimestamp = 0.0;

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("taskId", taskId);
        out.put("vmIndex", vmIndex);
        out.put("abstractAction", abstractAction);
        out.put("resolvedTier", resolvedTier);
        out.put("intendedTier", intendedTier);
        out.put("fallbackUsed", fallbackUsed);
        out.put("resourceProfile", resourceProfile == null ? null : resourceProfile.toMap());
        out.put("nativeBinding", nativeBinding == null ? null : nativeBinding.toMap());
        out.put("native_binding", nativeBinding == null ? null : nativeBinding.toMap());
        out.put("schedulingReceipt", schedulingReceipt == null ? null : schedulingReceipt.toMap());
        out.put("decisionTimestamp", decisionTimestamp);
        return out;
    }
}

