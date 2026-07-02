package edu.weijunyong.satedgesim.server;

import java.util.LinkedHashMap;
import java.util.Map;

public class RlCompletionReceipt {
    public String receiptStage = "completion";
    public long decisionId = -1L;
    public long taskId = -1L;
    public boolean taskScheduled = false;
    public boolean taskCompleted = true;
    public boolean taskSucceeded = false;
    public boolean actionAccepted = true;
    public boolean executionScheduled = true;
    public double simulationTime = 0.0;
    public double finalDelay = 0.0;
    public Double finalEnergy = null;
    public Double simlogFinalEnergyWh = null;
    public Double estimatorExpectedEnergyJ = null;
    public String energySource = "simlog_final_wh";
    public String energyUnit = "Wh";
    public boolean energySourceAvailable = false;
    public String energyUnavailableReason = "energy_counter_not_read";
    public String failureReason = "none";
    public double completionTimestamp = 0.0;
    public RlResourceBindingMode bindingMode = RlResourceBindingMode.candidate_only;
    public boolean nativeBindingReleased = false;
    public Map<String, Object> nativeBindingRelease = null;

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("receiptStage", receiptStage);
        out.put("decisionId", decisionId);
        out.put("taskId", taskId);
        out.put("actionAccepted", actionAccepted);
        out.put("executionScheduled", executionScheduled);
        out.put("taskScheduled", taskScheduled);
        out.put("taskCompleted", taskCompleted);
        out.put("taskSucceeded", taskSucceeded);
        out.put("simulationTime", simulationTime);
        out.put("finalDelay", finalDelay);
        out.put("finalEnergy", finalEnergy);
        out.put("simlog_final_energy_wh", simlogFinalEnergyWh);
        out.put("estimator_expected_energy_j", estimatorExpectedEnergyJ);
        out.put("energySource", energySource);
        out.put("energyUnit", energyUnit);
        out.put("energySourceAvailable", energySourceAvailable);
        out.put("energy_source_available", energySourceAvailable);
        out.put("energyUnavailableReason", energyUnavailableReason);
        out.put("energy_unavailable_reason", energyUnavailableReason);
        out.put("failureReason", failureReason);
        out.put("completionTimestamp", completionTimestamp);
        out.put("bindingMode", bindingMode.toString());
        out.put("nativeBindingReleased", nativeBindingReleased);
        out.put("native_binding_released", nativeBindingReleased);
        out.put("nativeBindingRelease", nativeBindingRelease);
        out.put("native_binding_release", nativeBindingRelease);
        return out;
    }
}
