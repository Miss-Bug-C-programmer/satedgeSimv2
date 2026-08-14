package edu.weijunyong.satedgesim.Topology;

import java.util.List;
import java.util.Map;

import edu.weijunyong.satedgesim.ScenarioManager.simulationParameters;

/** Reads the already-loaded trajectory blocks and interpolates ECEF positions. */
public final class TrajectoryPositionProvider {
    private final List<Map<String, List<String>>> cloudTrajectory;
    private final List<Map<String, List<String>>> groundTrajectory;
    private final List<Map<String, List<String>>> leoTrajectory;
    private final double maxAvailableTimeSec;

    public TrajectoryPositionProvider() {
        this(simulationParameters.Cloudlocationinfo,
                simulationParameters.EdgeDataCenterslocationinfo,
                simulationParameters.EdgeDeviceslocationinfo);
    }

    public TrajectoryPositionProvider(List<Map<String, List<String>>> cloudTrajectory,
            List<Map<String, List<String>>> groundTrajectory,
            List<Map<String, List<String>>> leoTrajectory) {
        this.cloudTrajectory = requireTrajectory(cloudTrajectory, "CLOUD");
        this.groundTrajectory = requireTrajectory(groundTrajectory, "EDGE_DATACENTER");
        this.leoTrajectory = requireTrajectory(leoTrajectory, "EDGE_DEVICE");
        maxAvailableTimeSec = Math.min(lastTime(cloudTrajectory),
                Math.min(lastTime(groundTrajectory), lastTime(leoTrajectory)));
    }

    public TopologyPosition getPosition(simulationParameters.TYPES type, int deviceId, double timeSec) {
        if (type == null) throw new IllegalArgumentException("trajectory type must not be null");
        if (Double.isNaN(timeSec) || Double.isInfinite(timeSec) || timeSec < 0.0) {
            throw new IllegalArgumentException("trajectory time must be finite and non-negative: " + timeSec);
        }
        double effectiveTime = clampTime(timeSec);
        List<Map<String, List<String>>> trajectory = trajectoryFor(type);
        validateDeviceId(trajectory, deviceId, type);
        Map<String, List<String>> block = trajectory.get(deviceId - 1);
        List<String> times = block.get("1");
        int lower = lowerSampleIndex(times, effectiveTime);
        int sampleCount = times.size() - 1;
        int upper = Math.min(lower + 1, sampleCount - 1);
        TopologyPosition lowerPosition = readSampleBlock(block, lower);
        if (lower == upper) return new TopologyPosition(effectiveTime,
                lowerPosition.xMeters, lowerPosition.yMeters, lowerPosition.zMeters);
        double lowerTime = Double.parseDouble(times.get(lower + 1));
        double upperTime = Double.parseDouble(times.get(upper + 1));
        if (Math.abs(effectiveTime - lowerTime) < 1.0e-12) return lowerPosition;
        TopologyPosition upperPosition = readSampleBlock(block, upper);
        double alpha = upperTime <= lowerTime ? 0.0 : (effectiveTime - lowerTime) / (upperTime - lowerTime);
        alpha = Math.max(0.0, Math.min(1.0, alpha));
        return new TopologyPosition(effectiveTime,
                interpolate(lowerPosition.xMeters, upperPosition.xMeters, alpha),
                interpolate(lowerPosition.yMeters, upperPosition.yMeters, alpha),
                interpolate(lowerPosition.zMeters, upperPosition.zMeters, alpha));
    }

    public double getMaxAvailableTimeSec() {
        return maxAvailableTimeSec;
    }

    public boolean isWithinHorizon(double timeSec) {
        return timeSec >= 0.0 && timeSec <= maxAvailableTimeSec;
    }

    public double clampTime(double timeSec) {
        if (Double.isNaN(timeSec) || Double.isInfinite(timeSec) || timeSec < 0.0) {
            throw new IllegalArgumentException("trajectory time must be finite and non-negative: " + timeSec);
        }
        return Math.max(0.0, Math.min(maxAvailableTimeSec, timeSec));
    }

    /** Shared legacy coordinate conversion used by ServersManager.Setnodelocation. */
    public static double[] readLegacyPosition(List<Map<String, List<String>>> locationInfo,
            int id, int timeIndex) {
        TopologyPosition position = readSample(locationInfo, id, timeIndex);
        return new double[] { position.xMeters, position.yMeters, position.zMeters };
    }

    public static TopologyPosition readSample(List<Map<String, List<String>>> locationInfo,
            int id, int zeroBasedSampleIndex) {
        if (locationInfo == null || locationInfo.isEmpty()) throw new IllegalArgumentException("trajectory block list is empty");
        if (id <= 0 || id > locationInfo.size()) throw new IllegalArgumentException("trajectory deviceId is out of range: " + id);
        return readSampleBlock(locationInfo.get(id - 1), zeroBasedSampleIndex);
    }

    private static TopologyPosition readSampleBlock(Map<String, List<String>> block,
            int zeroBasedSampleIndex) {
        List<String> times = block.get("1");
        if (times == null || zeroBasedSampleIndex < 0 || zeroBasedSampleIndex + 1 >= times.size()) {
            throw new IllegalArgumentException("trajectory sample is out of range: " + zeroBasedSampleIndex);
        }
        List<String> xs = block.get("2");
        List<String> ys = block.get("3");
        List<String> zs = block.get("4");
        if (xs == null || ys == null || zs == null || zeroBasedSampleIndex + 1 >= xs.size()
                || zeroBasedSampleIndex + 1 >= ys.size() || zeroBasedSampleIndex + 1 >= zs.size()) {
            throw new IllegalArgumentException("trajectory coordinate columns are incomplete");
        }
        double time = Double.parseDouble(times.get(zeroBasedSampleIndex + 1));
        return new TopologyPosition(time,
                Double.parseDouble(xs.get(zeroBasedSampleIndex + 1)) * 1000.0,
                Double.parseDouble(ys.get(zeroBasedSampleIndex + 1)) * 1000.0,
                Double.parseDouble(zs.get(zeroBasedSampleIndex + 1)) * 1000.0);
    }

    private List<Map<String, List<String>>> trajectoryFor(simulationParameters.TYPES type) {
        if (type == simulationParameters.TYPES.CLOUD) return cloudTrajectory;
        if (type == simulationParameters.TYPES.EDGE_DATACENTER) return groundTrajectory;
        return leoTrajectory;
    }

    private static List<Map<String, List<String>>> requireTrajectory(List<Map<String, List<String>>> trajectory, String name) {
        if (trajectory == null || trajectory.isEmpty()) throw new IllegalStateException(name + " trajectory is not loaded");
        return trajectory;
    }

    private static double lastTime(List<Map<String, List<String>>> trajectory) {
        List<String> times = trajectory.get(0).get("1");
        if (times == null || times.size() < 2) throw new IllegalStateException("trajectory has no samples");
        return Double.parseDouble(times.get(times.size() - 1));
    }

    private static void validateDeviceId(List<Map<String, List<String>>> trajectory, int deviceId,
            simulationParameters.TYPES type) {
        if (deviceId <= 0 || deviceId > trajectory.size()) {
            throw new IllegalArgumentException(type + " deviceId is not present in the loaded trajectory: " + deviceId);
        }
    }

    private static int lowerSampleIndex(List<String> times, double timeSec) {
        int sampleCount = times.size() - 1;
        int low = 0;
        int high = sampleCount - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            double middleTime = Double.parseDouble(times.get(middle + 1));
            if (middleTime <= timeSec) low = middle + 1;
            else high = middle - 1;
        }
        return Math.max(0, Math.min(sampleCount - 1, high));
    }

    private static double interpolate(double lower, double upper, double alpha) {
        return lower + alpha * (upper - lower);
    }
}
