package edu.weijunyong.satedgesim.Network;

import edu.weijunyong.satedgesim.DataCentersManager.DataCenter;
import edu.weijunyong.satedgesim.LocationManager.Location;
import edu.weijunyong.satedgesim.ScenarioManager.simulationParameters;

/** Pure ECEF line-of-sight geometry for satellite and ground resources. */
public final class LinkGeometry {
    private static final double DEFAULT_EARTH_RADIUS_METERS = 6378137.0;
    private static final double DEFAULT_ISL_CLEARANCE_METERS = 100000.0;
    private static final double DEFAULT_GROUND_MIN_ELEVATION_DEG = 10.0;

    private LinkGeometry() {
    }

    /** Geometry visibility only; radio range is applied by callers separately. */
    public static boolean isVisible(DataCenter first, DataCenter second) {
        if (first == null || second == null || first.getLocation() == null || second.getLocation() == null) {
            return false;
        }
        if (first == second || first.getId() == second.getId()) {
            return true;
        }
        return isVisible(first.getType(), locationVector(first.getLocation()),
                second.getType(), locationVector(second.getLocation()));
    }

    /** Unified raw-vector visibility API used by future-time topology queries. */
    public static boolean isVisible(simulationParameters.TYPES firstType, double[] firstPosition,
            simulationParameters.TYPES secondType, double[] secondPosition) {
        if (firstType == null || secondType == null || firstPosition == null || secondPosition == null
                || firstPosition.length != 3 || secondPosition.length != 3) {
            return false;
        }
        boolean firstGround = firstType == simulationParameters.TYPES.EDGE_DATACENTER;
        boolean secondGround = secondType == simulationParameters.TYPES.EDGE_DATACENTER;
        if (firstGround && secondGround) {
            return distance(firstPosition, secondPosition) == 0.0;
        }
        if (firstGround || secondGround) {
            return isGroundSatelliteVisible(
                    firstGround ? firstPosition : secondPosition,
                    firstGround ? secondPosition : firstPosition,
                    groundMinElevationDeg());
        }
        return isSatelliteLinkVisible(firstPosition, secondPosition, earthRadiusMeters(), islMinClearanceMeters());
    }

    public static double groundElevationDeg(double[] ground, double[] satellite) {
        if (ground == null || satellite == null || ground.length != 3 || satellite.length != 3) {
            return Double.NaN;
        }
        double[] los = subtract(satellite, ground);
        double denominator = norm(los) * norm(ground);
        if (denominator == 0.0) return 90.0;
        double sine = dot(los, ground) / denominator;
        sine = Math.max(-1.0, Math.min(1.0, sine));
        return Math.toDegrees(Math.asin(sine));
    }

    public static boolean isGroundSatelliteVisible(double[] ground, double[] satellite, double minimumElevationDeg) {
        if (ground == null || satellite == null || ground.length != 3 || satellite.length != 3) {
            return false;
        }
        double[] los = subtract(satellite, ground);
        double losNorm = norm(los);
        double groundNorm = norm(ground);
        if (losNorm == 0.0 || groundNorm == 0.0) {
            return true;
        }
        double sinElevation = dot(los, ground) / (losNorm * groundNorm);
        return sinElevation >= Math.sin(Math.toRadians(minimumElevationDeg));
    }

    public static boolean isSatelliteLinkVisible(double[] first, double[] second,
            double earthRadiusMeters, double islMinClearanceMeters) {
        if (first == null || second == null || first.length != 3 || second.length != 3) {
            return false;
        }
        if (distance(first, second) == 0.0) {
            return true;
        }
        return minimumSegmentRadius(first, second) > earthRadiusMeters + islMinClearanceMeters;
    }

    /** Raw-vector helper for distinct ground nodes; identity is handled by isVisible. */
    public static boolean isGroundGroundVisible(double[] first, double[] second) {
        return distance(first, second) == 0.0;
    }

    public static double minimumSegmentRadius(double[] first, double[] second) {
        double[] delta = subtract(second, first);
        double denominator = dot(delta, delta);
        if (denominator == 0.0) {
            return norm(first);
        }
        double t = -dot(first, delta) / denominator;
        t = Math.max(0.0, Math.min(1.0, t));
        return norm(new double[] {
                first[0] + t * delta[0],
                first[1] + t * delta[1],
                first[2] + t * delta[2]
        });
    }

    public static double distance(double[] first, double[] second) {
        return norm(subtract(first, second));
    }

    private static double[] locationVector(Location location) {
        return new double[] { location.getXPos(), location.getYPos(), location.getZPos() };
    }

    private static double[] subtract(double[] first, double[] second) {
        return new double[] { first[0] - second[0], first[1] - second[1], first[2] - second[2] };
    }

    private static double dot(double[] first, double[] second) {
        return first[0] * second[0] + first[1] * second[1] + first[2] * second[2];
    }

    private static double norm(double[] vector) {
        return Math.sqrt(dot(vector, vector));
    }

    private static double earthRadiusMeters() {
        return simulationParameters.EARTH_RADIUS > 0.0
                ? simulationParameters.EARTH_RADIUS : DEFAULT_EARTH_RADIUS_METERS;
    }

    private static double groundMinElevationDeg() {
        return simulationParameters.GROUND_MIN_ELEVATION_DEG >= 0.0
                ? simulationParameters.GROUND_MIN_ELEVATION_DEG : DEFAULT_GROUND_MIN_ELEVATION_DEG;
    }

    private static double islMinClearanceMeters() {
        return simulationParameters.ISL_MIN_CLEARANCE_METERS > 0.0
                ? simulationParameters.ISL_MIN_CLEARANCE_METERS : DEFAULT_ISL_CLEARANCE_METERS;
    }
}
