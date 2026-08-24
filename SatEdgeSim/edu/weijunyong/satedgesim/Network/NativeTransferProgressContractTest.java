package edu.weijunyong.satedgesim.Network;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import edu.weijunyong.satedgesim.server.ControlPhysicalContract;

/** Contract tests for the native bandwidth allocator and transfer evidence. */
public final class NativeTransferProgressContractTest {
    private NativeTransferProgressContractTest() {
    }

    public static void main(String[] args) {
        testBandwidthConservation();
        testContactQualificationAndByteConservation();
        testCapabilityRequiresObservedEvent();
        System.out.println("NativeTransferProgressContractTest OK (native progression contracts)");
    }

    private static void testBandwidthConservation() {
        for (int count : new int[] {2, 4, 8}) {
            double sum = 0.0;
            for (int i = 0; i < count; i++) {
                double weight = i == 0 ? 0.5 : 1.0;
                sum += DefaultNetworkModel.weightedCapacity(1000.0, weight, totalWeight(count, 0.5));
            }
            require(sum <= 1000.0 + 1.0e-9, "same-link bandwidth conservation failed for " + count + " flows");
        }
        double independentA = DefaultNetworkModel.weightedCapacity(1000.0, 1.0, 1.0);
        double independentB = DefaultNetworkModel.weightedCapacity(1000.0, 1.0, 1.0);
        require(independentA == 1000.0 && independentB == 1000.0,
                "independent LAN domains must not share a synthetic global bottleneck");
    }

    private static double totalWeight(int count, double firstWeight) {
        return firstWeight + Math.max(0, count - 1);
    }

    private static void testContactQualificationAndByteConservation() {
        FileTransferProgress zero = new FileTransferProgress(null, 100.0, FileTransferProgress.Type.TASK);
        require(!ContactEnforcementPolicy.isQualifyingMidTransfer(
                zero.getTransferredFileSize(), zero.getRemainingFileSize()),
                "zero-byte boundary must not qualify as mid-transfer interruption");

        FileTransferProgress partial = new FileTransferProgress(null, 100.0, FileTransferProgress.Type.TASK);
        partial.setRemainingFileSize(60.0);
        partial.setRemainingFileSize(60.0); // repeated observation must not double-count traffic
        require(ContactEnforcementPolicy.isQualifyingMidTransfer(
                partial.getTransferredFileSize(), partial.getRemainingFileSize()),
                "positive partial progress must qualify when contact closes");
        require(Math.abs(partial.getTransferredFileSize() + partial.getRemainingFileSize() - partial.getFileSize()) < 1.0e-9,
                "moved plus remaining transfer work must conserve total");
        partial.setContactRequired(true);
        partial.setContactEvidenceAvailable(true);
        partial.setContactEndSec(5.0);
        partial.setContactInterrupted(true);
        partial.setContactInterruptionQualified(true);
        partial.setPostInterruptionAction("fail_task_after_partial_transfer");
        Map<String, Object> evidence = partial.toRuntimeEvidence(
                "FAILED", partial.getPostInterruptionAction(), 5.0);
        require(((Number) evidence.get("bytesMovedBeforeInterruption")).doubleValue() > 0.0,
                "contact evidence must expose positive bytes moved");
        require(((Number) evidence.get("remainingBytes")).doubleValue() > 0.0,
                "contact evidence must expose remaining bytes");

        FileTransferProgress complete = new FileTransferProgress(null, 100.0, FileTransferProgress.Type.TASK);
        complete.setRemainingFileSize(0.0);
        require(!ContactEnforcementPolicy.isQualifyingMidTransfer(
                complete.getTransferredFileSize(), complete.getRemainingFileSize()),
                "completed transfer must not qualify as interruption");
    }

    private static void testCapabilityRequiresObservedEvent() {
        Map<String, Object> noObservation = new LinkedHashMap<String, Object>();
        noObservation.put("nativeContactInterruptionObserved", false);
        noObservation.put("nativeContactInterruptionEvidenceConsistent", false);
        Map<String, Object> beforeEvent = ControlPhysicalContract.capabilities(true, noObservation);
        require(Boolean.FALSE.equals(beforeEvent.get("supportsMidTransferContactEnforcement")),
                "contact capability must fail closed before a qualifying runtime event");
        noObservation.put("nativeContactInterruptionObserved", true);
        noObservation.put("nativeContactInterruptionEvidenceConsistent", true);
        Map<String, Object> afterEvent = ControlPhysicalContract.capabilities(true, noObservation);
        require(Boolean.TRUE.equals(afterEvent.get("supportsMidTransferContactEnforcement")),
                "contact capability may become observable only after qualifying evidence");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
