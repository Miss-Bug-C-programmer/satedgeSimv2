package edu.weijunyong.satedgesim.Topology;

import edu.weijunyong.satedgesim.ScenarioManager.simulationParameters;

/** Stable physical-topology identity, independent of CloudSim internal IDs. */
public final class TopologyNodeRef {
    public final simulationParameters.TYPES type;
    public final int deviceId;

    public TopologyNodeRef(simulationParameters.TYPES type, int deviceId) {
        if (type == null) {
            throw new IllegalArgumentException("topology node type must not be null");
        }
        if (deviceId <= 0) {
            throw new IllegalArgumentException("topology deviceId must be positive: " + deviceId);
        }
        this.type = type;
        this.deviceId = deviceId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof TopologyNodeRef)) return false;
        TopologyNodeRef that = (TopologyNodeRef) other;
        return deviceId == that.deviceId && type == that.type;
    }

    @Override
    public int hashCode() {
        return 31 * type.hashCode() + deviceId;
    }

    @Override
    public String toString() {
        return type.name() + "-" + deviceId;
    }
}
