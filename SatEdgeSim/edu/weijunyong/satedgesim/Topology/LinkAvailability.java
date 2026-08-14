package edu.weijunyong.satedgesim.Topology;

import edu.weijunyong.satedgesim.ScenarioManager.simulationParameters;

/** Centralized directional communication-range semantics. */
public final class LinkAvailability {
    private LinkAvailability() {
    }

    public static double maxRangeMeters(simulationParameters.TYPES destinationType) {
        if (destinationType == simulationParameters.TYPES.CLOUD) return simulationParameters.CLOUD_RANGE;
        if (destinationType == simulationParameters.TYPES.EDGE_DATACENTER) return simulationParameters.EDGE_DATACENTERS_RANGE;
        return simulationParameters.EDGE_DEVICES_RANGE;
    }

    public static boolean withinRange(double distanceMeters, simulationParameters.TYPES destinationType) {
        return distanceMeters == 0.0 || distanceMeters < maxRangeMeters(destinationType);
    }
}
