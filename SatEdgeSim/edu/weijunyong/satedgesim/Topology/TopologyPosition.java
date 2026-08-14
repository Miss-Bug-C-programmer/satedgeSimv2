package edu.weijunyong.satedgesim.Topology;

/** Immutable ECEF position in metres at a simulation-clock time. */
public final class TopologyPosition {
    public final double timeSec;
    public final double xMeters;
    public final double yMeters;
    public final double zMeters;

    public TopologyPosition(double timeSec, double xMeters, double yMeters, double zMeters) {
        this.timeSec = timeSec;
        this.xMeters = xMeters;
        this.yMeters = yMeters;
        this.zMeters = zMeters;
    }

    public double[] toArray() {
        return new double[] { xMeters, yMeters, zMeters };
    }

    public double radiusMeters() {
        return Math.sqrt(xMeters * xMeters + yMeters * yMeters + zMeters * zMeters);
    }

    public double distanceTo(TopologyPosition other) {
        if (other == null) return Double.NaN;
        double dx = xMeters - other.xMeters;
        double dy = yMeters - other.yMeters;
        double dz = zMeters - other.zMeters;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    @Override
    public String toString() {
        return "TopologyPosition{" + timeSec + ", " + xMeters + ", " + yMeters + ", " + zMeters + "}";
    }
}
