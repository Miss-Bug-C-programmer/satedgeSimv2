package edu.weijunyong.satedgesim.server;

import java.util.Map;

public class RlDecisionBridgeCompletionEnergyTest {
    public static void main(String[] args) {
        RlDecisionBridge bridge = new RlDecisionBridge("completion-energy-test");
        RlCompletionReceipt receipt = bridge.recordCompletion(null, null);
        Map<String, Object> payload = receipt.toMap();

        require("completion".equals(payload.get("receiptStage")), "receipt stage must be completion");
        require(payload.containsKey("simlog_final_energy_wh"), "completion receipt must expose simlog_final_energy_wh");
        require(payload.get("simlog_final_energy_wh") == null, "missing final energy must be null, not zero");
        require(payload.get("finalEnergy") == null, "legacy finalEnergy must stay null when unavailable");
        require(Boolean.FALSE.equals(payload.get("energySourceAvailable")), "missing final energy must be marked unavailable");
        require("simulation_manager_unavailable".equals(payload.get("energyUnavailableReason")),
                "missing SimulationManager must be reported explicitly");
        require("simlog_final_wh".equals(payload.get("energySource")), "completion energy source must be unit-qualified");
        require("Wh".equals(payload.get("energyUnit")), "completion energy unit must be Wh");

        ExecutionReceipt scheduling = new ExecutionReceipt();
        Map<String, Object> schedulingPayload = scheduling.toMap();
        require(schedulingPayload.get("receipt_energy_delta_wh") == null,
                "missing scheduling energy delta must be null, not zero");
        require(Boolean.FALSE.equals(schedulingPayload.get("energySourceAvailable")),
                "missing scheduling energy source must be marked unavailable");

        System.out.println("RlDecisionBridgeCompletionEnergyTest OK");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
