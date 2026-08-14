package edu.weijunyong.satedgesim.TasksOrchestration;

import org.cloudbus.cloudsim.vms.Vm;

import edu.weijunyong.satedgesim.DataCentersManager.DataCenter;
import edu.weijunyong.satedgesim.Topology.LinkAvailability;
import edu.weijunyong.satedgesim.ScenarioManager.simulationParameters;
import edu.weijunyong.satedgesim.TasksGenerator.Task;

public final class CandidateCostEstimator {
    public static final String VERSION = "v2_phase_calibrated_delay_queue";

    private CandidateCostEstimator() {
    }

    public static void populateActual(
            Orchestrator.FeasibilityInfo info,
            Task task,
            Vm vm,
            DataCenter source,
            DataCenter destination,
            int queueLength,
            String queueEstimateSource) {
        info.estimatedQueueLength = Math.max(0, queueLength);
        info.queueEstimateSource = queueEstimateSource == null || "".equals(queueEstimateSource) ? "unknown" : queueEstimateSource;
        info.sourceDistance = info.isLocalToSource ? 0.0 : SimulationManagerShim.distance(source, destination);
        info.propagationDelaySec = propagationDelaySec(info.sourceDistance);
        info.linkAvailable = info.isLocalToSource || (SimulationManagerShim.hasLink(source, destination)
                && LinkAvailability.withinRange(info.sourceDistance, destination.getType()));
        info.estimatedTransmissionRateMbps = estimateRateMbps(vm, destination, info.isLocalToSource);
        info.estimatedComputeCapacity = Math.max(1.0, vm.getMips() * Math.max(1.0, vm.getNumberOfPes()));
        info.estimatedTransmissionDelaySec = transmissionDelaySec(task, info.estimatedTransmissionRateMbps, info.isLocalToSource, 1.0);
        info.estimatedComputeDelaySec = computeDelaySec(task, info.estimatedComputeCapacity, 1.0);
        info.estimatedQueueDelaySec = queueDelaySec(info.estimatedQueueLength, info.estimatedComputeDelaySec, 1.0);
        info.estimatedTotalDelaySec = totalDelaySec(info);
    }

    public static void populateControlled(Orchestrator.FeasibilityInfo info, Task task) {
        populateControlled(info, task, 1.0, 1.0, 1.0);
    }

    public static void populateControlled(
            Orchestrator.FeasibilityInfo info,
            Task task,
            double transmissionDemandScale,
            double computeDemandScale,
            double queueDelayScale) {
        info.estimatedTransmissionDelaySec = transmissionDelaySec(
                task,
                info.estimatedTransmissionRateMbps,
                info.isLocalToSource,
                transmissionDemandScale);
        info.estimatedComputeDelaySec = computeDelaySec(task, info.estimatedComputeCapacity, computeDemandScale);
        info.estimatedQueueDelaySec = queueDelaySec(info.estimatedQueueLength, info.estimatedComputeDelaySec, queueDelayScale);
        info.estimatedTotalDelaySec = totalDelaySec(info);
    }

    public static double transmissionDelaySec(Task task, double rateMbps, boolean local) {
        return transmissionDelaySec(task, rateMbps, local, 1.0);
    }

    public static double transmissionDelaySec(Task task, double rateMbps, boolean local, double demandScale) {
        if (local) {
            return 0.0;
        }
        double taskBits = Math.max(0.0, task == null ? 0.0 : task.getFileSize()) * Math.max(0.10, demandScale) * 8.0;
        double rateBps = Math.max(1.0e-6, rateMbps) * 1_000_000.0;
        return taskBits / rateBps;
    }

    public static double computeDelaySec(Task task, double computeCapacityMips) {
        return computeDelaySec(task, computeCapacityMips, 1.0);
    }

    public static double computeDelaySec(Task task, double computeCapacityMips, double demandScale) {
        return Math.max(0.0, task == null ? 0.0 : task.getLength()) * Math.max(0.10, demandScale) / Math.max(1.0, computeCapacityMips);
    }

    public static double queueDelaySec(int queueLength, double expectedServiceTimeSec) {
        return queueDelaySec(queueLength, expectedServiceTimeSec, 1.0);
    }

    public static double queueDelaySec(int queueLength, double expectedServiceTimeSec, double queueDelayScale) {
        return Math.max(0, queueLength) * Math.max(0.0, expectedServiceTimeSec) * Math.max(0.10, queueDelayScale);
    }

    public static double totalDelaySec(Orchestrator.FeasibilityInfo info) {
        return Math.max(0.0, info.propagationDelaySec)
                + Math.max(0.0, info.estimatedTransmissionDelaySec)
                + Math.max(0.0, info.estimatedComputeDelaySec)
                + Math.max(0.0, info.estimatedQueueDelaySec);
    }

    public static double propagationDelaySec(double distanceMeters) {
        double speed = Math.max(1.0, simulationParameters.WAN_PROPAGATION_SPEED);
        return Math.max(0.0, distanceMeters) / speed;
    }

    public static double estimateRateMbps(Vm vm, DataCenter destination, boolean local) {
        double vmBw = Math.max(1.0, vm.getBw().getCapacity());
        if (local) {
            return vmBw;
        }
        double networkRateMbps;
        if (destination.getType() == simulationParameters.TYPES.CLOUD) {
            networkRateMbps = Math.max(1.0, simulationParameters.WAN_BANDWIDTH / 1000.0);
        } else {
            networkRateMbps = Math.max(1.0, simulationParameters.BANDWIDTH_WLAN / 1000.0);
        }
        return Math.min(vmBw, networkRateMbps);
    }

    private static final class SimulationManagerShim {
        private SimulationManagerShim() {
        }

        private static double distance(DataCenter source, DataCenter destination) {
            return edu.weijunyong.satedgesim.SimulationManager.SimulationManager.getdistance(source, destination);
        }

        private static boolean hasLink(DataCenter source, DataCenter destination) {
            return edu.weijunyong.satedgesim.SimulationManager.SimulationManager.issetlink(source, destination);
        }
    }
}
