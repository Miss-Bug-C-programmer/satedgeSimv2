package edu.weijunyong.satedgesim.Topology;

import java.util.List;

import edu.weijunyong.satedgesim.Network.LinkGeometry;
import edu.weijunyong.satedgesim.ScenarioManager.FilesParser;
import edu.weijunyong.satedgesim.ScenarioManager.simulationParameters;

/** End-to-end deterministic position, topology and contact-plan smoke test. */
public final class TopologyOracleSmoke {
    private static final String SCENARIO = "SatEdgeSim/settings/scenarios/china_regional_4geo_28leo_12ground";

    private TopologyOracleSmoke() {
    }

    public static void main(String[] args) {
        String scenario = args.length == 0 ? SCENARIO : args[0];
        simulationParameters.SERVER_MODE = true;
        String root = scenario.replace('\\', '/');
        FilesParser parser = new FilesParser();
        require(parser.checkFiles(
                root + "/simulation_parameters.properties",
                root + "/edge_devices.xml",
                root + "/edge_datacenters.xml",
                "SatEdgeSim/settings/applications.xml",
                root + "/cloud.xml",
                root + "/locations/geo.csv",
                root + "/locations/ground.csv",
                root + "/locations/leo.csv"), "scenario files must load");

        TopologyOracle oracle = new TopologyOracle(new TrajectoryPositionProvider());
        require(oracle.getMaxAvailableTimeSec() >= 3600.0, "trace horizon must be 3600 seconds");
        TopologyPosition geo0 = oracle.getPosition(new TopologyNodeRef(simulationParameters.TYPES.CLOUD, 1), 0.0);
        TopologyPosition geo100 = oracle.getPosition(new TopologyNodeRef(simulationParameters.TYPES.CLOUD, 1), 100.0);
        TopologyPosition ground0 = oracle.getPosition(new TopologyNodeRef(simulationParameters.TYPES.EDGE_DATACENTER, 1), 0.0);
        TopologyPosition ground100 = oracle.getPosition(new TopologyNodeRef(simulationParameters.TYPES.EDGE_DATACENTER, 1), 100.0);
        TopologyNodeRef leoRef = new TopologyNodeRef(simulationParameters.TYPES.EDGE_DEVICE, 1);
        TopologyPosition leo100 = oracle.getPosition(leoRef, 100.0);
        TopologyPosition leo101 = oracle.getPosition(leoRef, 101.0);
        TopologyPosition leoFractional = oracle.getPosition(leoRef, 100.5);
        TopologyPosition leoEnd = oracle.getPosition(leoRef, oracle.getMaxAvailableTimeSec());
        TopologyPosition leoBeyondEnd = oracle.getPosition(leoRef, oracle.getMaxAvailableTimeSec() + 100.0);
        require(geo0.distanceTo(geo100) < 1.0e-6, "GEO ECEF position must be fixed");
        require(ground0.distanceTo(ground100) < 1.0e-6, "ground ECEF position must be fixed");
        require(leo100.distanceTo(leo101) > 1.0, "LEO position must move");
        require(Math.abs(leoFractional.xMeters - (leo100.xMeters + leo101.xMeters) / 2.0) < 1.0e-3
                && Math.abs(leoFractional.yMeters - (leo100.yMeters + leo101.yMeters) / 2.0) < 1.0e-3
                && Math.abs(leoFractional.zMeters - (leo100.zMeters + leo101.zMeters) / 2.0) < 1.0e-3,
                "fractional position must be linearly interpolated");
        require(!oracle.isWithinHorizon(oracle.getMaxAvailableTimeSec() + 1.0),
                "time beyond trace horizon must be out of horizon");
        require(leoEnd.distanceTo(leoBeyondEnd) < 1.0e-6,
                "position query beyond horizon must clamp, never wrap");
        require(geo0.radiusMeters() > 42164000.0 && geo0.radiusMeters() < 42165000.0,
                "GEO radius must be physical");
        require(ground0.radiusMeters() > 6378000.0 && ground0.radiusMeters() < 6378300.0,
                "ground radius must be physical");

        TopologyNodeRef geoRef = new TopologyNodeRef(simulationParameters.TYPES.CLOUD, 1);
        TopologyNodeRef groundRef = new TopologyNodeRef(simulationParameters.TYPES.EDGE_DATACENTER, 1);
        checkOracleRawConsistency(oracle, leoRef, new TopologyNodeRef(simulationParameters.TYPES.EDGE_DEVICE, 2), 0.0);
        checkOracleRawConsistency(oracle, leoRef, geoRef, 300.0);
        checkOracleRawConsistency(oracle, leoRef, groundRef, 900.0);

        ContactPlan plan = new ContactPlan(oracle, 3600.0, 1.0, 0.1);
        PairAndWindow leoLeo = findFirstWindow(plan, simulationParameters.TYPES.EDGE_DEVICE, 28,
                simulationParameters.TYPES.EDGE_DEVICE, 28, true);
        PairAndWindow leoGeo = findFirstWindow(plan, simulationParameters.TYPES.EDGE_DEVICE, 28,
                simulationParameters.TYPES.CLOUD, 4, true);
        PairAndWindow leoGround = findFirstWindow(plan, simulationParameters.TYPES.EDGE_DEVICE, 28,
                simulationParameters.TYPES.EDGE_DATACENTER, 12, true);
        require(leoLeo != null, "at least one LEO-LEO contact window must exist");
        require(leoGeo != null, "at least one LEO-GEO contact window must exist");
        require(leoGround != null, "at least one LEO-ground contact window must exist");

        assertLifetime(plan, leoGround);
        assertCensored(plan, leoGround);
        assertNextWindow(plan, leoGround);
        System.out.println("TopologyOracle smoke: PASS");
        printPair("LEO-LEO", leoLeo);
        printPair("LEO-GEO", leoGeo);
        printPair("LEO-ground", leoGround);
        ContactPlan.Stats stats = plan.getStats();
        System.out.println("ContactPlan stats: pairsCached=" + stats.pairsCached
                + ", cacheHits=" + stats.cacheHits + ", cacheMisses=" + stats.cacheMisses
                + ", contactWindowsGenerated=" + stats.contactWindowsGenerated
                + ", topologyQueries=" + stats.topologyQueries);
        measureCache(plan, oracle);
    }

    private static void checkOracleRawConsistency(TopologyOracle oracle, TopologyNodeRef source,
            TopologyNodeRef destination, double timeSec) {
        LinkSnapshot snapshot = oracle.getLinkSnapshot(source, destination, timeSec);
        boolean rawGeometry = LinkGeometry.isVisible(source.type, snapshot.sourcePosition.toArray(),
                destination.type, snapshot.destinationPosition.toArray());
        boolean rawAvailable = rawGeometry && LinkAvailability.withinRange(snapshot.distanceMeters, destination.type);
        require(snapshot.geometryVisible == rawGeometry, "oracle geometry must equal raw geometry");
        require(snapshot.available == rawAvailable, "oracle availability must equal geometry AND range");
    }

    private static PairAndWindow findFirstWindow(ContactPlan plan, simulationParameters.TYPES sourceType,
            int sourceCount, simulationParameters.TYPES destinationType, int destinationCount, boolean distinct) {
        for (int sourceId = 1; sourceId <= sourceCount; sourceId++) {
            for (int destinationId = 1; destinationId <= destinationCount; destinationId++) {
                if (distinct && sourceType == destinationType && sourceId == destinationId) continue;
                TopologyNodeRef source = new TopologyNodeRef(sourceType, sourceId);
                TopologyNodeRef destination = new TopologyNodeRef(destinationType, destinationId);
                List<ContactWindow> windows = plan.getContactWindows(source, destination, 0.0, 3600.0);
                for (ContactWindow window : windows) {
                    if (window.durationSec > 1.0) return new PairAndWindow(source, destination, window);
                }
            }
        }
        return null;
    }

    private static void assertLifetime(ContactPlan plan, PairAndWindow pair) {
        double now = pair.window.startSec + Math.min(1.0, pair.window.durationSec / 2.0);
        ContactForecast forecast = plan.getContactForecast(pair.source, pair.destination, now, 600.0);
        require(forecast.availableNow, "contact must be available inside its window");
        require(Math.abs(forecast.remainingLifetimeSec - (pair.window.endSec - now)) <= 0.11,
                "remaining lifetime must equal the contact end minus now");
    }

    private static void assertCensored(ContactPlan plan, PairAndWindow pair) {
        double now = pair.window.startSec;
        ContactForecast forecast = plan.getContactForecast(pair.source, pair.destination, now, 5.0);
        if (pair.window.durationSec > 5.1) {
            require(forecast.availableNow && forecast.remainingLifetimeSec >= 4.9
                    && forecast.remainingLifetimeCensored, "short horizon must censor an ongoing contact");
        }
    }

    private static void assertNextWindow(ContactPlan plan, PairAndWindow pair) {
        double gapStart = pair.window.endSec + 0.2;
        if (gapStart >= plan.getConfiguredHorizonSec()) return;
        ContactForecast forecast = plan.getContactForecast(pair.source, pair.destination, gapStart, 3600.0);
        if (!forecast.availableNow && forecast.nextContactStartSec != null) {
            require(forecast.nextContactStartSec > gapStart, "next contact must be in the future");
        }
    }

    private static void printPair(String label, PairAndWindow pair) {
        System.out.println(label + " " + pair.source + " -> " + pair.destination
                + " firstWindow=[" + pair.window.startSec + "," + pair.window.endSec
                + "] duration=" + pair.window.durationSec
                + " rightCensored=" + pair.window.rightCensored);
    }

    private static void measureCache(ContactPlan plan, TopologyOracle oracle) {
        TopologyNodeRef source = new TopologyNodeRef(simulationParameters.TYPES.EDGE_DEVICE, 2);
        TopologyNodeRef destination = new TopologyNodeRef(simulationParameters.TYPES.CLOUD, 2);
        long firstStart = System.nanoTime();
        plan.getContactForecast(source, destination, 0.0, 600.0);
        long firstNanos = System.nanoTime() - firstStart;
        long cachedStart = System.nanoTime();
        plan.getContactForecast(source, destination, 300.0, 600.0);
        long cachedNanos = System.nanoTime() - cachedStart;
        long batchStart = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            plan.getRemainingContactLifetimeSec(source, destination, i, 600.0);
        }
        long batchNanos = System.nanoTime() - batchStart;
        System.out.println("ContactPlan timing: firstPairMs=" + firstNanos / 1.0e6
                + ", cachedPairMs=" + cachedNanos / 1.0e6
                + ", cached100LifetimeMs=" + batchNanos / 1.0e6
                + ", oracleHorizonSec=" + oracle.getMaxAvailableTimeSec());
    }

    private static final class PairAndWindow {
        final TopologyNodeRef source;
        final TopologyNodeRef destination;
        final ContactWindow window;

        PairAndWindow(TopologyNodeRef source, TopologyNodeRef destination, ContactWindow window) {
            this.source = source;
            this.destination = destination;
            this.window = window;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
