package edu.weijunyong.satedgesim.Topology;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import edu.weijunyong.satedgesim.DataCentersManager.DataCenter;
import edu.weijunyong.satedgesim.Network.LinkGeometry;
import edu.weijunyong.satedgesim.ScenarioManager.simulationParameters;

/** Deterministic physical-topology truth over the loaded trajectory backend. */
public final class TopologyOracle {
    public static final String SOURCE = "physical_ground_truth";

    private final TrajectoryPositionProvider positionProvider;

    public TopologyOracle(TrajectoryPositionProvider positionProvider) {
        if (positionProvider == null) throw new IllegalArgumentException("position provider must not be null");
        this.positionProvider = positionProvider;
    }

    public TopologyPosition getPosition(TopologyNodeRef node, double timeSec) {
        requireNode(node);
        return positionProvider.getPosition(node.type, node.deviceId, timeSec);
    }

    public LinkSnapshot getLinkSnapshot(TopologyNodeRef source, TopologyNodeRef destination, double timeSec) {
        requireNode(source);
        requireNode(destination);
        double effectiveTime = requireAndClampTime(timeSec);
        TopologyPosition sourcePosition = getPosition(source, effectiveTime);
        TopologyPosition destinationPosition = getPosition(destination, effectiveTime);
        double distance = sourcePosition.distanceTo(destinationPosition);
        boolean geometryVisible = LinkGeometry.isVisible(source.type, sourcePosition.toArray(),
                destination.type, destinationPosition.toArray());
        double maxRange = LinkAvailability.maxRangeMeters(destination.type);
        boolean withinRange = distance < maxRange || distance == 0.0;
        Double elevation = null;
        if (source.type == simulationParameters.TYPES.EDGE_DATACENTER
                && destination.type != simulationParameters.TYPES.EDGE_DATACENTER) {
            elevation = LinkGeometry.groundElevationDeg(sourcePosition.toArray(), destinationPosition.toArray());
        } else if (destination.type == simulationParameters.TYPES.EDGE_DATACENTER
                && source.type != simulationParameters.TYPES.EDGE_DATACENTER) {
            elevation = LinkGeometry.groundElevationDeg(destinationPosition.toArray(), sourcePosition.toArray());
        }
        return new LinkSnapshot(effectiveTime, source, destination, sourcePosition, destinationPosition,
                distance, geometryVisible, withinRange, geometryVisible && withinRange,
                maxRange, elevation);
    }

    public LinkSnapshot getLinkSnapshot(DataCenter source, DataCenter destination, double timeSec) {
        return getLinkSnapshot(toRef(source), toRef(destination), timeSec);
    }

    public boolean isLinkAvailable(DataCenter source, DataCenter destination, double timeSec) {
        return getLinkSnapshot(source, destination, timeSec).available;
    }

    public List<LinkSnapshot> getTopologySnapshot(Collection<? extends DataCenter> activeNodes, double timeSec) {
        List<LinkSnapshot> result = new ArrayList<LinkSnapshot>();
        if (activeNodes == null) return result;
        for (DataCenter source : activeNodes) {
            for (DataCenter destination : activeNodes) {
                if (source != destination) result.add(getLinkSnapshot(source, destination, timeSec));
            }
        }
        return result;
    }

    public List<LinkSnapshot> getOutgoingLinks(DataCenter source,
            Collection<? extends DataCenter> activeNodes, double timeSec) {
        List<LinkSnapshot> result = new ArrayList<LinkSnapshot>();
        if (source == null || activeNodes == null) return result;
        for (DataCenter destination : activeNodes) {
            if (source != destination) result.add(getLinkSnapshot(source, destination, timeSec));
        }
        return result;
    }

    public double getMaxAvailableTimeSec() {
        return positionProvider.getMaxAvailableTimeSec();
    }

    public double clampTime(double timeSec) {
        if (Double.isNaN(timeSec) || Double.isInfinite(timeSec) || timeSec < 0.0) {
            throw new IllegalArgumentException("topology time must be finite and non-negative: " + timeSec);
        }
        return positionProvider.clampTime(timeSec);
    }

    public boolean isWithinHorizon(double timeSec) {
        return positionProvider.isWithinHorizon(timeSec);
    }

    public TrajectoryPositionProvider getPositionProvider() {
        return positionProvider;
    }

    public static TopologyNodeRef toRef(DataCenter dataCenter) {
        if (dataCenter == null || dataCenter.getType() == null) {
            throw new IllegalArgumentException("datacenter type must not be null");
        }
        return new TopologyNodeRef(dataCenter.getType(), dataCenter.getDeviceID());
    }

    private double requireAndClampTime(double timeSec) {
        return clampTime(timeSec);
    }

    private static void requireNode(TopologyNodeRef node) {
        if (node == null) throw new IllegalArgumentException("topology node must not be null");
    }
}
