package edu.weijunyong.satedgesim.server;

public class RlResourceBindingSmoke {
    public static void main(String[] args) {
        RlAction low = new RlAction();
        low.cpuShare = 0.25;
        low.bandwidthShare = 0.25;
        low.txPowerRatio = 0.25;
        RlAction high = new RlAction();
        high.cpuShare = 1.0;
        high.bandwidthShare = 1.0;
        high.txPowerRatio = 1.0;

        RlResourceProfile candidate = RlResourceProfile.fromAction(low, RlResourceBindingMode.candidate_only);
        require(!candidate.continuousApplied, "candidate_only must not apply continuous resources");

        RlResourceProfile lowProfile = RlResourceProfile.fromAction(low, RlResourceBindingMode.resource_aware_estimator_bound);
        RlResourceProfile highProfile = RlResourceProfile.fromAction(high, RlResourceBindingMode.resource_aware_estimator_bound);
        require(lowProfile.continuousApplied, "estimator_bound must apply continuous resources");
        require(!lowProfile.nativeSchedulerBound(), "estimator_bound must not claim native scheduler binding");

        RlResourceAwareEstimator.Estimate lowEst = RlResourceAwareEstimator.estimate(
                1000.0, 100.0, 4.0, 2.0, 1.0, 0.1, false, lowProfile);
        RlResourceAwareEstimator.Estimate highEst = RlResourceAwareEstimator.estimate(
                1000.0, 100.0, 4.0, 2.0, 1.0, 0.1, false, highProfile);
        require(lowEst.expectedComputeDelaySec > highEst.expectedComputeDelaySec, "cpuShare must change compute delay");
        require(lowEst.expectedTxDelaySec > highEst.expectedTxDelaySec, "bandwidthShare must change tx delay");
        require(Math.abs(lowEst.txPowerW - highEst.txPowerW) > 1.0e-9, "txPowerRatio must change tx power");
        require(lowEst.expectedEnergyJ > 0.0 && highEst.expectedEnergyJ > 0.0, "energy estimates must be positive");

        RlResourceProfile nativeProfile = RlResourceProfile.fromAction(high, RlResourceBindingMode.native_scheduler_bound);
        require(nativeProfile.nativeSchedulerBound(), "native_scheduler_bound must be accepted after native binding implementation");
        require(nativeProfile.continuousApplied, "native binding must apply continuous resources");
        require(Boolean.TRUE.equals(RlResourceBindingAudit.metadata(nativeProfile).get("full_hybrid_closed_loop_claim_allowed")),
                "native binding metadata must allow full hybrid closed-loop claims");

        ExecutionReceipt scheduling = new ExecutionReceipt();
        scheduling.receiptStage = "scheduling";
        scheduling.actionAccepted = true;
        scheduling.executionScheduled = true;
        RlCompletionReceipt completion = new RlCompletionReceipt();
        completion.decisionId = 12L;
        completion.taskId = 34L;
        completion.taskCompleted = true;
        completion.taskSucceeded = false;
        completion.failureReason = "deadline_miss";
        require(!scheduling.receiptStage.equals(completion.receiptStage), "scheduling and completion stages must differ");
        require(scheduling.toMap().containsKey("taskCompleted"), "scheduling receipt must expose taskCompleted");
        require(scheduling.toMap().get("taskCompleted") == null, "scheduling taskCompleted must be null until completion");
        require(scheduling.toMap().get("taskSucceeded") == null, "scheduling taskSucceeded must be null until completion");
        require("completion".equals(completion.toMap().get("receiptStage")), "completion receipt stage must be completion");
        require(Boolean.TRUE.equals(completion.toMap().get("taskCompleted")), "completion receipt taskCompleted must be true");
        require(Boolean.FALSE.equals(completion.toMap().get("taskSucceeded")), "completion receipt must carry final taskSucceeded");
        require(Long.valueOf(12L).equals(completion.toMap().get("decisionId")), "completion receipt must carry decisionId");

        System.out.println("RlResourceBindingSmoke OK");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
