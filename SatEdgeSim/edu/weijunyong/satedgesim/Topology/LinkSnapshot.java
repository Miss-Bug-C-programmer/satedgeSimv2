package edu.weijunyong.satedgesim.Topology;

import edu.weijunyong.satedgesim.ScenarioManager.simulationParameters;

/** Deterministic physical link evaluation at one simulation-clock time. */
public final class LinkSnapshot {
    public final double timeSec;
    public final TopologyNodeRef source;
    public final TopologyNodeRef destination;
    public final TopologyPosition sourcePosition;
    public final TopologyPosition destinationPosition;
    public final double distanceMeters;
    public final boolean geometryVisible;
    public final boolean withinRange;
    public final boolean available;
    public final simulationParameters.TYPES sourceType;
    public final simulationParameters.TYPES destinationType;
    public final double maxRangeMeters;
    public final Double elevationDeg;

    public LinkSnapshot(double timeSec, TopologyNodeRef source, TopologyNodeRef destination,
            TopologyPosition sourcePosition, TopologyPosition destinationPosition,
            double distanceMeters, boolean geometryVisible, boolean withinRange,
            boolean available, double maxRangeMeters, Double elevationDeg) {
        this.timeSec = timeSec;
        this.source = source;
        this.destination = destination;
        this.sourcePosition = sourcePosition;
        this.destinationPosition = destinationPosition;
        this.distanceMeters = distanceMeters;
        this.geometryVisible = geometryVisible;
        this.withinRange = withinRange;
        this.available = available;
        this.sourceType = source.type;
        this.destinationType = destination.type;
        this.maxRangeMeters = maxRangeMeters;
        this.elevationDeg = elevationDeg;
    }
}
