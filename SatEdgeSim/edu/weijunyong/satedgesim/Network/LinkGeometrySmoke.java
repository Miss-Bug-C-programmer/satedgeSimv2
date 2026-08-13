package edu.weijunyong.satedgesim.Network;

/** Lightweight geometry smoke test; no CloudSim lifecycle is required. */
public final class LinkGeometrySmoke {
    private static final double EARTH_RADIUS = 6378137.0;
    private static final double LEO_RADIUS = EARTH_RADIUS + 550000.0;

    private LinkGeometrySmoke() {
    }

    public static void main(String[] args) {
        double[] ground = new double[] { EARTH_RADIUS, 0.0, 0.0 };
        double[] overhead = new double[] { LEO_RADIUS, 0.0, 0.0 };
        double[] opposite = new double[] { -LEO_RADIUS, 0.0, 0.0 };
        double angle = Math.toRadians(10.0);
        double[] nearby = new double[] { LEO_RADIUS * Math.cos(angle), LEO_RADIUS * Math.sin(angle), 0.0 };

        require(LinkGeometry.isGroundSatelliteVisible(ground, overhead, 10.0), "overhead satellite must be visible");
        require(!LinkGeometry.isGroundSatelliteVisible(ground, opposite, 10.0), "below-horizon satellite must be hidden");
        require(!LinkGeometry.isSatelliteLinkVisible(opposite, overhead, EARTH_RADIUS, 100000.0), "Earth occultation must hide opposite satellites");
        require(LinkGeometry.isSatelliteLinkVisible(overhead, nearby, EARTH_RADIUS, 100000.0), "same-side satellites must be visible");
        require(LinkGeometry.isSatelliteLinkVisible(overhead, overhead, EARTH_RADIUS, 100000.0), "zero-distance satellite link must be visible");
        require(!LinkGeometry.isGroundGroundVisible(ground, nearby), "distinct ground nodes must not be linked");
        require(LinkGeometry.isGroundGroundVisible(ground, ground), "zero-distance ground node must be local");
        System.out.println("LinkGeometry smoke: PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
