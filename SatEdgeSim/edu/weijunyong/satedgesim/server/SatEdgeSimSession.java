package edu.weijunyong.satedgesim.server;

import java.lang.reflect.Constructor;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.vms.Vm;
import org.cloudsimplus.util.Log;

import ch.qos.logback.classic.Level;
import edu.weijunyong.satedgesim.DataCentersManager.DataCenter;
import edu.weijunyong.satedgesim.DataCentersManager.DefaultDataCenter;
import edu.weijunyong.satedgesim.DataCentersManager.DefaultEnergyModel;
import edu.weijunyong.satedgesim.DataCentersManager.EnergyModel;
import edu.weijunyong.satedgesim.DataCentersManager.ServersManager;
import edu.weijunyong.satedgesim.LocationManager.DefaultMobilityModel;
import edu.weijunyong.satedgesim.LocationManager.Mobility;
import edu.weijunyong.satedgesim.Network.FileTransferProgress;
import edu.weijunyong.satedgesim.Network.DefaultNetworkModel;
import edu.weijunyong.satedgesim.Network.NetworkModel;
import edu.weijunyong.satedgesim.ScenarioManager.FilesParser;
import edu.weijunyong.satedgesim.ScenarioManager.Scenario;
import edu.weijunyong.satedgesim.ScenarioManager.simulationParameters;
import edu.weijunyong.satedgesim.SimulationManager.SimLog;
import edu.weijunyong.satedgesim.SimulationManager.SimulationManager;
import edu.weijunyong.satedgesim.Topology.ContactForecast;
import edu.weijunyong.satedgesim.Topology.ContactPlan;
import edu.weijunyong.satedgesim.Topology.ContactWindow;
import edu.weijunyong.satedgesim.Topology.LinkSnapshot;
import edu.weijunyong.satedgesim.Topology.TopologyNodeRef;
import edu.weijunyong.satedgesim.Topology.TopologyOracle;
import edu.weijunyong.satedgesim.TasksGenerator.DefaultTasksGenerator;
import edu.weijunyong.satedgesim.TasksGenerator.Task;
import edu.weijunyong.satedgesim.TasksGenerator.TasksGenerator;
import edu.weijunyong.satedgesim.TasksOrchestration.ExternalRLOrchestrator;
import edu.weijunyong.satedgesim.TasksOrchestration.Orchestrator;

/** A single long-running SatEdgeSim simulation session controlled by REST calls. */
public class SatEdgeSimSession {
    private final String sessionId;
    private final ServerConfig config;
    private final ResetRequest resetRequest;
    private final RlDecisionBridge bridge;

    private Thread simulationThread;
    private CloudSim simulation;
    private SimulationManager simulationManager;
    private SimLog simLog;
    private volatile Throwable failure;
    private PersistentExecutionConfiguration currentConfiguration;
    private double configurationAppliedAtSec = Double.NaN;
    private long configurationReceiptSequence = 0L;

    private Class<? extends Mobility> mobilityManager = DefaultMobilityModel.class;
    private Class<? extends DataCenter> edgeDatacenter = DefaultDataCenter.class;
    private Class<? extends TasksGenerator> tasksGenerator = DefaultTasksGenerator.class;
    private Class<? extends Orchestrator> orchestrator = ExternalRLOrchestrator.class;
    private Class<? extends EnergyModel> energyModel = DefaultEnergyModel.class;
    private Class<? extends NetworkModel> networkModel = DefaultNetworkModel.class;

    public SatEdgeSimSession(ServerConfig config, ResetRequest resetRequest) {
        this.sessionId = UUID.randomUUID().toString();
        this.config = config;
        this.resetRequest = resetRequest == null ? new ResetRequest() : resetRequest;
        this.bridge = new RlDecisionBridge(sessionId);
    }

    public String getSessionId() {
        return sessionId;
    }

    public void start() throws Exception {
        System.out.println("[SatEdgeSimSession] start sessionId=" + sessionId);
        loadSimulationFiles();
        System.out.println("[SatEdgeSimSession] settings loaded");
        buildSimulation();
        System.out.println("[SatEdgeSimSession] simulation built");
        simulationThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    System.out.println("[SatEdgeSimSession] simulation thread starting");
                    simulationManager.startSimulation();
                    System.out.println("[SatEdgeSimSession] simulation thread finished");
                    bridge.markFinished(readMetrics());
                } catch (Throwable t) {
                    failure = t;
                    bridge.markFailed(t);
                } finally {
                    RlDecisionBridgeRegistry.unregister(simulationManager.getSimulationId());
                }
            }
        }, "satedgesim-session-" + sessionId);
        simulationThread.setDaemon(true);
        simulationThread.start();

        if (resetRequest.waitForFirstDecision) {
            bridge.waitForDecisionOrFinish(resetRequest.waitTimeoutMs);
        }
    }

    private void loadSimulationFiles() {
        FilesParser parser = new FilesParser();
        simulationParameters.SERVER_MODE = true;
        boolean ok = parser.checkFiles(
                config.simConfigFile,
                config.edgeDevicesFile,
                config.edgeDataCentersFile,
                config.applicationsFile,
                config.cloudFile,
                config.cloudLocationFile,
                config.edgeDataCentersLocationFile,
                config.edgeDevicesLocationFile);
        if (!ok) {
            throw new IllegalStateException("SatEdgeSim settings files failed validation");
        }

        int trajectoryCount = simulationParameters.EdgeDeviceslocationinfo == null
                ? 0 : simulationParameters.EdgeDeviceslocationinfo.size();
        int requestedDevices = resetRequest.devicesCount == -1
                ? simulationParameters.MAX_NUM_OF_EDGE_DEVICES : resetRequest.devicesCount;
        if (requestedDevices < 1) {
            throw new IllegalArgumentException("devicesCount must satisfy 1 <= devicesCount <= "
                    + simulationParameters.MAX_NUM_OF_EDGE_DEVICES + ", got " + requestedDevices);
        }
        if (requestedDevices > trajectoryCount) {
            throw new IllegalArgumentException("devicesCount=" + requestedDevices
                    + " exceeds LEO trajectory block count=" + trajectoryCount);
        }
        if (requestedDevices > simulationParameters.MAX_NUM_OF_EDGE_DEVICES) {
            throw new IllegalArgumentException("devicesCount must satisfy 1 <= devicesCount <= "
                    + simulationParameters.MAX_NUM_OF_EDGE_DEVICES + ", got " + requestedDevices);
        }

        if (config.forceSequential) {
            simulationParameters.PARALLEL = false;
        }
        if (config.disableCharts) {
            simulationParameters.DISPLAY_REAL_TIME_CHARTS = false;
            simulationParameters.AUTO_CLOSE_REAL_TIME_CHARTS = true;
            simulationParameters.SAVE_CHARTS = false;
        }
        if (resetRequest.simulationTimeMinutes != null && resetRequest.simulationTimeMinutes.doubleValue() > 0.0) {
            simulationParameters.SIMULATION_TIME = simulationParameters.INITIALIZATION_TIME
                    + 60.0 * resetRequest.simulationTimeMinutes.doubleValue();
            if (simulationParameters.SIMULATION_TIME > simulationParameters.LOCATIONTIMENUM) {
                throw new IllegalStateException(
                        "simulationTimeMinutes override exceeds available location trace horizon: requestedSeconds="
                                + simulationParameters.SIMULATION_TIME
                                + " availableSeconds=" + simulationParameters.LOCATIONTIMENUM);
            }
        }
        if (resetRequest.tasksGenerationRate != null && resetRequest.tasksGenerationRate.intValue() > 0) {
            simulationParameters.TASKS_PER_EDGE_DEVICE_PER_MINUTES = resetRequest.tasksGenerationRate.intValue();
        }
        if (resetRequest.waitForAllTasks != null) {
            simulationParameters.WAIT_FOR_TASKS = resetRequest.waitForAllTasks.booleanValue();
        }
        simulationParameters.RL_SERVER_SEED = resetRequest.seed;
        simulationParameters.RL_SCENARIO_PROFILE = resetRequest.scenarioProfile == null ? "default" : resetRequest.scenarioProfile.trim();
        simulationParameters.RL_TASK_SOURCE_MODE = resetRequest.taskSourceMode == null ? "current" : resetRequest.taskSourceMode.trim();
        simulationParameters.RL_SUCCESS_PROFILE = resetRequest.successProfile == null ? "default" : resetRequest.successProfile.trim();
        simulationParameters.RL_ACTION_MASK_MODE = resetRequest.actionMaskMode == null ? "visible_only" : resetRequest.actionMaskMode.trim();
        simulationParameters.RL_MIN_LINK_SURVIVAL_MARGIN_SEC =
                resetRequest.minLinkSurvivalMarginSec == null ? 0.0 : Math.max(0.0, resetRequest.minLinkSurvivalMarginSec.doubleValue());
        simulationParameters.RL_IS_CONTROLLED_SCENARIO =
                !"default".equalsIgnoreCase(simulationParameters.RL_SCENARIO_PROFILE)
                || !"current".equalsIgnoreCase(simulationParameters.RL_TASK_SOURCE_MODE);
        simulationParameters.PAUSE_LENGTH = 0;
        simulationParameters.CLEAN_OUTPUT_FOLDER = resetRequest.cleanOutputFolder;
        Log.setLevel(simulationParameters.DEEP_LOGGING ? Level.ALL : Level.OFF);
    }

    private void buildSimulation() throws Exception {
        int devicesCount = resetRequest.devicesCount == -1
                ? simulationParameters.MAX_NUM_OF_EDGE_DEVICES : resetRequest.devicesCount;
        int algorithmIndex = clamp(resetRequest.algorithmIndex, 0, simulationParameters.ORCHESTRATION_AlGORITHMS.length - 1);
        int architectureIndex = clamp(resetRequest.architectureIndex, 0, simulationParameters.ORCHESTRATION_ARCHITECTURES.length - 1);
        Scenario scenario = new Scenario(devicesCount, algorithmIndex, architectureIndex);

        String startTime = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date()) + "_server_" + sessionId.substring(0, 8);
        simLog = new SimLog(startTime, true);
        simulation = new CloudSim();
        simulationManager = new SimulationManager(simLog, simulation, 1, 1, scenario);
        simLog.initialize(simulationManager, scenario.getDevicesCount(), scenario.getOrchAlgorithm(), scenario.getOrchArchitecture());

        System.out.println("[SatEdgeSimSession] generating datacenters/devices");
        ServersManager serversManager = new ServersManager(simulationManager, mobilityManager, energyModel, edgeDatacenter);
        serversManager.generateDatacentersAndDevices();
        simulationManager.setServersManager(serversManager);

        System.out.println("[SatEdgeSimSession] generating tasks");
        Constructor<?> tasksGeneratorConstructor = tasksGenerator.getConstructor(SimulationManager.class);
        List<Task> tasksList = generateTasksForDecisionBudget(tasksGeneratorConstructor);
        applySuccessProfile(tasksList);
        simulationManager.setTasksList(tasksList);
        System.out.println("[SatEdgeSimSession] generated tasks count=" + tasksList.size());

        RlDecisionBridgeRegistry.register(simulationManager.getSimulationId(), bridge);
        System.out.println("[SatEdgeSimSession] building orchestrator");
        Constructor<?> orchestratorConstructor = orchestrator.getConstructor(SimulationManager.class);
        Orchestrator edgeOrchestrator = (Orchestrator) orchestratorConstructor.newInstance(simulationManager);
        simulationManager.setOrchestrator(edgeOrchestrator);

        System.out.println("[SatEdgeSimSession] building network model");
        Constructor<?> networkConstructor = networkModel.getConstructor(SimulationManager.class);
        NetworkModel network = (NetworkModel) networkConstructor.newInstance(simulationManager);
        simulationManager.setNetworkModel(network);
        bridge.updateMetrics(readMetrics());
        System.out.println("[SatEdgeSimSession] metrics initialized");
    }

    private List<Task> generateTasksForDecisionBudget(Constructor<?> tasksGeneratorConstructor) throws Exception {
        List<Task> tasksList = instantiateTasksGenerator(tasksGeneratorConstructor).generate();
        int minDecisions = resetRequest.maxDecisions == null ? 0 : resetRequest.maxDecisions.intValue();
        if (minDecisions <= 0 || tasksList.size() >= minDecisions) {
            return tasksList;
        }

        int desiredTasks = (int) Math.ceil(minDecisions * 1.05);
        int rate = Math.max(1, simulationParameters.TASKS_PER_EDGE_DEVICE_PER_MINUTES);
        for (int attempt = 1; attempt <= 4 && tasksList.size() < minDecisions; attempt++) {
            int currentCount = Math.max(1, tasksList.size());
            int scaledRate = Math.max(rate + 1, (int) Math.ceil(rate * (double) desiredTasks / currentCount));
            simulationParameters.TASKS_PER_EDGE_DEVICE_PER_MINUTES = scaledRate;
            System.out.println(
                    "[SatEdgeSimSession] maxDecisions="
                            + minDecisions
                            + " exceeds generated tasks="
                            + tasksList.size()
                            + ", regenerating with tasksPerEdgeDevicePerMinute="
                            + scaledRate
                            + " attempt="
                            + attempt);
            rate = scaledRate;
            tasksList = instantiateTasksGenerator(tasksGeneratorConstructor).generate();
        }
        return tasksList;
    }

    private TasksGenerator instantiateTasksGenerator(Constructor<?> tasksGeneratorConstructor) throws Exception {
        return (TasksGenerator) tasksGeneratorConstructor.newInstance(simulationManager);
    }

    private void applySuccessProfile(List<Task> tasksList) {
        if (tasksList == null || tasksList.isEmpty()) {
            return;
        }
        String profile = simulationParameters.RL_SUCCESS_PROFILE == null
                ? "default"
                : simulationParameters.RL_SUCCESS_PROFILE.trim().toLowerCase();
        if ("paper_strict".equals(profile) || "default".equals(profile)) {
            return;
        }
        if ("preflight_lenient".equals(profile)) {
            for (Task task : tasksList) {
                if (task == null) {
                    continue;
                }
                double oldLatency = task.getMaxLatency();
                if (oldLatency > 0.0) {
                    task.setMaxLatency(oldLatency * 2.5);
                }
            }
            return;
        }
    }

    public RlState getState() {
        RlState state = bridge.getState();
        if (failure != null) {
            state.status = "FAILED";
            state.message = failure.getMessage();
        }
        return state;
    }

    public RlState step(RlAction action, long waitTimeoutMs) {
        bridge.submitAction(action);
        bridge.waitForDecisionOrFinish(waitTimeoutMs <= 0 ? 30000L : waitTimeoutMs);
        return getState();
    }

    public ExecutionReceipt applyAction(RlAction action) {
        long t0 = System.nanoTime();
        if (simulation != null && simulation.isPaused()) {
            simulation.resume();
        }
        ExecutionReceipt receipt = bridge.submitAction(action);
        double elapsedMs = (System.nanoTime() - t0) / 1_000_000.0;
        receipt.serverProcessingMs = elapsedMs;
        bridge.recordDeliveredReceipt(receipt);
        if (elapsedMs > 100.0) {
            System.err.println("[SatEdgeSimSession] apply_action slow decisionId=" + receipt.decisionId + " processingMs=" + elapsedMs);
        }
        return receipt;
    }

    public Map<String, Object> getHealthPayload() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        RlState current = bridge.getCurrentStateSnapshot();
        result.put("ok", true);
        result.put("serverTimeMs", System.currentTimeMillis());
        result.put("scenarioProfile", simulationParameters.RL_SCENARIO_PROFILE);
        result.put("taskSourceMode", simulationParameters.RL_TASK_SOURCE_MODE);
        result.put("successProfile", simulationParameters.RL_SUCCESS_PROFILE);
        result.put("actionMaskMode", simulationParameters.RL_ACTION_MASK_MODE);
        result.put("minLinkSurvivalMarginSec", simulationParameters.RL_MIN_LINK_SURVIVAL_MARGIN_SEC);
        result.put("currentDecisionId", current == null ? null : current.decisionId);
        result.put("currentTaskId", current == null ? null : current.taskId);
        return result;
    }

    public Map<String, Object> getCurrentDecisionDebug() {
        return bridge.getCurrentDecisionDebug();
    }

    public Map<String, Object> getLastReceiptDebug() {
        ExecutionReceipt receipt = bridge.getLastExecutionReceipt();
        Map<String, Object> out = receipt == null ? new LinkedHashMap<String, Object>() : receipt.toMap();
        RlCompletionReceipt completion = bridge.getLastCompletionReceipt();
        if (completion != null) {
            out.put("completionReceipt", completion.toMap());
        }
        return out;
    }

    public Map<String, Object> getReceiptStats() {
        return bridge.getReceiptStats();
    }

    public Map<String, Object> getCurrentTopology() {
        ensureTopologyReady();
        double timeSec = simulation.clock();
        List<DataCenter> activeNodes = simulationManager.getServersManager().getDatacenterList();
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("simulationTimeSec", timeSec);
        response.put("source", TopologyOracle.SOURCE);
        List<Map<String, Object>> nodes = new java.util.ArrayList<Map<String, Object>>();
        for (DataCenter dataCenter : activeNodes) {
            TopologyNodeRef ref = TopologyOracle.toRef(dataCenter);
            edu.weijunyong.satedgesim.Topology.TopologyPosition position = simulationManager.getTopologyOracle().getPosition(ref, timeSec);
            Map<String, Object> node = new LinkedHashMap<String, Object>();
            node.put("type", ref.type.name());
            node.put("deviceId", ref.deviceId);
            node.put("x", position.xMeters);
            node.put("y", position.yMeters);
            node.put("z", position.zMeters);
            nodes.add(node);
        }
        response.put("nodes", nodes);
        List<Map<String, Object>> links = new java.util.ArrayList<Map<String, Object>>();
        for (DataCenter source : activeNodes) {
            for (DataCenter destination : activeNodes) {
                if (source == destination) continue;
                links.add(linkMap(simulationManager.getTopologyOracle().getLinkSnapshot(source, destination, timeSec)));
            }
        }
        response.put("links", links);
        return response;
    }

    public Map<String, Object> getContactPlan(Map<String, Object> request) {
        ensureTopologyReady();
        TopologyNodeRef source = parseNodeRef(request, "source");
        TopologyNodeRef destination = parseNodeRef(request, "destination");
        DataCenter sourceDataCenter = findActiveNode(source);
        DataCenter destinationDataCenter = findActiveNode(destination);
        if (sourceDataCenter == null || destinationDataCenter == null) {
            throw new IllegalArgumentException("contact-plan nodes must belong to the active session: "
                    + source + " -> " + destination);
        }
        Object horizonValue = request == null ? null : request.get("horizonSec");
        double horizon = horizonValue instanceof Number ? ((Number) horizonValue).doubleValue()
                : simulationParameters.TOPOLOGY_FORECAST_HORIZON_SEC;
        double now = simulation.clock();
        ContactForecast forecast = simulationManager.getContactPlan().getContactForecast(source, destination, now, horizon);
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("simulationTimeSec", now);
        response.put("forecastType", "deterministic_orbit_contact");
        response.put("sourceType", forecast.source);
        response.put("containsFutureStochasticState", false);
        response.put("source", nodeMap(source));
        response.put("destination", nodeMap(destination));
        response.put("availableNow", forecast.availableNow);
        response.put("remainingLifetimeSec", forecast.remainingLifetimeSec);
        response.put("remainingLifetimeCensored", forecast.remainingLifetimeCensored);
        response.put("currentContactEndSec", forecast.currentContactEndSec);
        response.put("nextContactStartSec", forecast.nextContactStartSec);
        response.put("nextContactEndSec", forecast.nextContactEndSec);
        response.put("forecastStartSec", forecast.forecastStartSec);
        response.put("forecastEndSec", forecast.forecastEndSec);
        response.put("effectiveHorizonSec", forecast.effectiveHorizonSec);
        List<Map<String, Object>> windows = new java.util.ArrayList<Map<String, Object>>();
        for (ContactWindow window : forecast.windows) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("startSec", window.startSec);
            item.put("endSec", window.endSec);
            item.put("durationSec", window.durationSec);
            item.put("startsInsideQuery", window.startsInsideQuery);
            item.put("endsInsideQuery", window.endsInsideQuery);
            item.put("leftCensored", window.leftCensored);
            item.put("rightCensored", window.rightCensored);
            windows.add(item);
        }
        response.put("windows", windows);
        return response;
    }

    public Map<String, Object> getContactPlanStats() {
        ensureTopologyReady();
        ContactPlan.Stats stats = simulationManager.getContactPlan().getStats();
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("cacheHits", stats.cacheHits);
        response.put("cacheMisses", stats.cacheMisses);
        response.put("pairsCached", stats.pairsCached);
        response.put("contactWindowsGenerated", stats.contactWindowsGenerated);
        response.put("topologyQueries", stats.topologyQueries);
        return response;
    }

    /** Current-task configuration viability report; report-only, no action mutation. */
    public Map<String, Object> getConfigurationViability() {
        RlState state = bridge.getState();
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("simulationTimeSec", simulation == null ? 0.0 : simulation.clock());
        response.put("status", state.status);
        response.put("mode", simulationParameters.CONFIGURATION_VIABILITY_MODE);
        response.put("source", "current_rl_state");
        response.put("taskId", state.taskId);
        response.put("decisionId", state.decisionId);
        response.put("scenarioProfile", state.scenarioProfile);
        response.put("isControlledRlScenario", state.isControlledRlScenario);
        response.put("viableCandidateCount", state.viableCandidateCount);
        response.put("inviableCandidateCount", state.inviableCandidateCount);
        response.put("uncertainCandidateCount", state.uncertainCandidateCount);
        response.put("viabilitySummarySource", state.viabilitySummarySource);
        List<Map<String, Object>> candidates = new java.util.ArrayList<Map<String, Object>>();
        for (RlState.VmView vm : state.candidateVms) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("vmIndex", vm.vmIndex);
            item.put("vmId", vm.vmId);
            item.put("datacenterType", vm.datacenterType);
            item.put("datacenterDeviceId", vm.datacenterDeviceId);
            item.put("abstractAction", vm.abstractAction);
            item.put("viabilityStatus", vm.viabilityStatus);
            item.put("viabilityReason", vm.viabilityReason);
            item.put("viabilitySource", vm.viabilitySource);
            item.put("viabilityEvaluated", vm.viabilityEvaluated);
            item.put("contactEndCensored", vm.viabilityContactEndCensored);
            item.put("availableContactSec", vm.viabilityAvailableContactSec);
            item.put("requiredContactSec", vm.viabilityRequiredContactSec);
            item.put("serviceMarginSec", vm.viabilityServiceMarginSec);
            item.put("linkAvailableNow", vm.linkAvailableNow);
            item.put("estimatedLinkLifetimeSec", vm.estimatedLinkLifetimeSec);
            item.put("estimatedTaskCompletionTimeSec", vm.estimatedTaskCompletionTimeSec);
            candidates.add(item);
        }
        response.put("candidates", candidates);
        return response;
    }

    private static long numberAsLong(Object value, long fallback) {
        return value instanceof Number ? ((Number) value).longValue() : fallback;
    }

    private static int numberAsInt(Object value, int fallback) {
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    private static int budgetLimit(Map<String, Object> budget) {
        if (budget == null) {
            return -1;
        }
        for (String key : new String[] {"max_candidate_count", "maxCandidateCount", "candidateCount"}) {
            Object value = budget.get(key);
            if (value instanceof Number) {
                return Math.max(0, ((Number) value).intValue());
            }
        }
        return -1;
    }

    private static boolean scopeMatches(Map<String, Object> scope, RlState state, RlState.VmView vm) {
        if (scope == null || scope.isEmpty()) {
            return true;
        }
        if (contains(scope, "task_ids", "taskIds") && containsValue(scope, "task_ids", "taskIds", state.taskId)) {
            return true;
        }
        if (contains(scope, "source_ids", "sourceIds") && containsValue(scope, "source_ids", "sourceIds", state.sourceDeviceId)) {
            return true;
        }
        if (contains(scope, "node_ids", "nodeIds")) {
            if (containsValue(scope, "node_ids", "nodeIds", vm.datacenterDeviceId)
                    || containsValue(scope, "node_ids", "nodeIds", vm.vmId)
                    || containsValue(scope, "node_ids", "nodeIds", vm.datacenterId)) {
                return true;
            }
        }
        if (contains(scope, "resource_keys", "resourceKeys")
                && (containsValue(scope, "resource_keys", "resourceKeys", vm.vmId)
                        || containsValue(scope, "resource_keys", "resourceKeys", vm.id))) {
            return true;
        }
        return false;
    }

    private static boolean contains(Map<String, Object> scope, String first, String second) {
        return scope.containsKey(first) || scope.containsKey(second);
    }

    private static boolean containsValue(Map<String, Object> scope, String first, String second, Object value) {
        Object raw = scope.containsKey(first) ? scope.get(first) : scope.get(second);
        if (!(raw instanceof List)) {
            return false;
        }
        for (Object item : (List<?>) raw) {
            if (String.valueOf(item).equals(String.valueOf(value))) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Object> vmMap(RlState.VmView vm) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("id", vm.id);
        result.put("vmIndex", vm.vmIndex);
        result.put("vmId", vm.vmId);
        result.put("hostId", vm.hostId);
        result.put("mips", vm.mips);
        result.put("pesNumber", vm.pesNumber);
        result.put("ram", vm.ram);
        result.put("bw", vm.bw);
        result.put("size", vm.size);
        result.put("datacenterId", vm.datacenterId);
        result.put("datacenterDeviceId", vm.datacenterDeviceId);
        result.put("datacenterType", vm.datacenterType);
        result.put("logicalTier", vm.logicalTier);
        result.put("abstractAction", vm.abstractAction);
        result.put("abstractActionName", vm.abstractActionName);
        result.put("isLocalToSource", vm.isLocalToSource);
        result.put("isRemoteToSource", vm.isRemoteToSource);
        result.put("linkAvailable", vm.linkAvailable);
        result.put("linkAvailableNow", vm.linkAvailableNow);
        result.put("estimatedLinkLifetimeSec", vm.estimatedLinkLifetimeSec);
        result.put("estimatedTotalDelaySec", vm.estimatedTotalDelaySec);
        result.put("estimatedTaskCompletionTimeSec", vm.estimatedTaskCompletionTimeSec);
        result.put("estimatedQueueLength", vm.estimatedQueueLength);
        result.put("estimatedTransmissionRateMbps", vm.estimatedTransmissionRateMbps);
        result.put("estimatedComputeCapacity", vm.estimatedComputeCapacity);
        result.put("propagationDelaySec", vm.propagationDelaySec);
        result.put("linkSurvivalMarginSec", vm.linkSurvivalMarginSec);
        result.put("linkSurvivalMarginToCompletionSec", vm.linkSurvivalMarginToCompletionSec);
        result.put("handoverRequired", vm.handoverRequired);
        result.put("handoverAvailable", vm.handoverAvailable);
        result.put("mobilityRisk", vm.mobilityRisk);
        result.put("viabilityStatus", vm.viabilityStatus);
        result.put("viabilityReason", vm.viabilityReason);
        result.put("viabilitySource", vm.viabilitySource);
        result.put("viabilityEvaluated", vm.viabilityEvaluated);
        result.put("isFeasible", vm.isFeasible);
        result.put("feasible", vm.feasible);
        result.put("infeasibleReason", vm.infeasibleReason);
        return result;
    }

    public synchronized Map<String, Object> getCurrentConfiguration() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("active", currentConfiguration != null);
        result.put("simulationTimeSec", simulation == null ? 0.0 : simulation.clock());
        result.put("configId", currentConfiguration == null ? null : currentConfiguration.configId);
        result.put("version", currentConfiguration == null ? 0L : currentConfiguration.version);
        result.put("configurationAgeSec", currentConfiguration == null || !Double.isFinite(configurationAppliedAtSec)
                ? null : Math.max(0.0, simulation.clock() - configurationAppliedAtSec));
        result.put("configuration", currentConfiguration == null ? null : currentConfiguration.toMap());
        result.put("containsFutureStochasticState", false);
        return result;
    }

    public synchronized Map<String, Object> validateConfiguration(Map<String, Object> request) {
        PersistentExecutionConfiguration candidate = PersistentExecutionConfiguration.fromRequest(request);
        Map<String, Object> receipt = new LinkedHashMap<String, Object>();
        receipt.put("receiptType", "configuration_validation");
        receipt.put("contractVersion", ControlPhysicalContract.VERSION);
        receipt.put("configId", candidate.configId);
        receipt.put("version", candidate.version);
        receipt.put("simulationTimeSec", simulation == null ? 0.0 : simulation.clock());
        receipt.put("containsFutureStochasticState", false);
        List<String> reasons = new ArrayList<String>();
        if (candidate.configId == null || candidate.configId.trim().isEmpty()) {
            reasons.add("missing_config_id");
        }
        if (candidate.version < 0L) {
            reasons.add("invalid_version");
        }
        if (currentConfiguration != null) {
            boolean exactVersion = candidate.version == currentConfiguration.version;
            boolean exactConfiguration = exactVersion
                    && currentConfiguration.configId.equals(candidate.configId)
                    && currentConfiguration.toMap().equals(candidate.toMap());
            if (candidate.version < currentConfiguration.version || (exactVersion && !exactConfiguration)) {
                reasons.add("stale_configuration_version");
            }
        }
        if (candidate.assignments.isEmpty() && candidate.reusableRules.isEmpty()) {
            reasons.add("no_persistent_execution_rule");
        }
        validateBindings(candidate.assignments, reasons);
        validateBindings(candidate.reusableRules, reasons);
        receipt.put("accepted", reasons.isEmpty());
        receipt.put("reasons", reasons);
        receipt.put("validationSource", "satedgesim_physical_backend");
        receipt.put("targetAvailabilityChecked", simulationManager != null && simulationManager.getServersManager() != null);
        return receipt;
    }

    /** Advance CloudSim through its public pause-at/resume mechanism. */
    public synchronized Map<String, Object> advanceWorld(double deltaSec) {
        if (simulation == null) throw new IllegalStateException("simulation is not ready");
        if (Double.isNaN(deltaSec) || Double.isInfinite(deltaSec) || deltaSec <= 0.0) {
            throw new IllegalArgumentException("deltaSec must be finite and positive");
        }
        Map<String, Object> scalars = bridge.getCurrentDecisionScalars();
        if (numberAsLong(scalars.get("taskId"), -1L) >= 0L) {
            Map<String, Object> rejected = new LinkedHashMap<String, Object>();
            rejected.put("accepted", false);
            rejected.put("reason", "simulation_waiting_for_decision");
            rejected.put("simulationTimeSec", simulation.clock());
            rejected.put("requestedDeltaSec", deltaSec);
            rejected.put("physicalClockAdvanced", false);
            rejected.put("directClockMutation", false);
            return rejected;
        }
        double before = simulation.clock();
        double target = before + deltaSec;
        boolean scheduled = simulation.pause(target);
        long deadline = System.currentTimeMillis() + 30000L;
        while (scheduled && !simulation.isPaused() && !bridge.isFinished() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10L);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        double after = simulation.clock();
        List<Long> uncoveredTaskIds = new ArrayList<Long>();
        if (simulationManager != null && simulationManager.getTasksList() != null) {
            for (Task task : simulationManager.getTasksList()) {
                if (task != null && task.getTime() > before && task.getTime() <= after) {
                    uncoveredTaskIds.add(task.getId());
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("accepted", scheduled && after > before);
        result.put("status", simulation.isPaused() ? "ADVANCED_AND_RESUMED" : "ADVANCE_TIMEOUT");
        result.put("requestedDeltaSec", deltaSec);
        result.put("simulationTimeBeforeSec", before);
        result.put("simulationTimeSec", after);
        result.put("targetSimulationTimeSec", target);
        result.put("physicalClockAdvanced", after > before);
        result.put("physicalStateChanged", after > before);
        result.put("directClockMutation", false);
        result.put("advanceMechanism", "CloudSim.pauseAt");
        result.put("resumeAfterReceipt", simulation.isPaused());
        result.put("oldConfigurationActiveDuringDelay", currentConfiguration == null ? null : currentConfiguration.configId);
        result.put("newConfigurationAppliedAfterDelay", false);
        result.put("uncoveredTaskCountDuringDelta", uncoveredTaskIds.size());
        result.put("uncoveredTaskIdsDuringDelta", uncoveredTaskIds);
        result.put("containsFutureStochasticState", false);
        result.put("validationRequiredBeforeConfigurationActivation", true);
        if (simulation.isPaused()) {
            simulation.resume();
        }
        return result;
    }

    public synchronized Map<String, Object> applyConfiguration(Map<String, Object> request) {
        PersistentExecutionConfiguration candidate = PersistentExecutionConfiguration.fromRequest(request);
        Map<String, Object> validation = validateConfiguration(request);
        if (!Boolean.TRUE.equals(validation.get("accepted"))) {
            return validation;
        }
        Map<String, Object> receipt = new LinkedHashMap<String, Object>();
        boolean idempotent = currentConfiguration != null
                && currentConfiguration.configId.equals(candidate.configId)
                && currentConfiguration.version == candidate.version
                && currentConfiguration.toMap().equals(candidate.toMap());
        Map<String, Object> before = currentConfiguration == null ? new LinkedHashMap<String, Object>() : currentConfiguration.toMap();
        currentConfiguration = candidate;
        if (!idempotent || !Double.isFinite(configurationAppliedAtSec)) {
            configurationAppliedAtSec = simulation == null ? Double.NaN : simulation.clock();
        }
        bridge.setPersistentConfiguration(candidate);
        configurationReceiptSequence += 1L;
        receipt.put("receiptType", "configuration_apply");
        receipt.put("accepted", true);
        receipt.put("idempotent", idempotent);
        receipt.put("receiptId", configurationReceiptSequence);
        receipt.put("configId", candidate.configId);
        receipt.put("version", candidate.version);
        receipt.put("simulationTimeSec", simulation == null ? 0.0 : simulation.clock());
        receipt.put("changed", !idempotent);
        receipt.put("previousConfiguration", before);
        receipt.put("configuration", candidate.toMap());
        receipt.put("reusableRuleCount", candidate.reusableRules.size());
        receipt.put("dispatchMode", "persistent_reusable_rule");
        receipt.put("containsFutureStochasticState", false);
        return receipt;
    }

    public synchronized Map<String, Object> dispatchUnderConfiguration(Map<String, Object> request) {
        PersistentExecutionConfiguration candidate = PersistentExecutionConfiguration.fromRequest(request);
        Map<String, Object> receipt = new LinkedHashMap<String, Object>();
        receipt.put("receiptType", "configuration_dispatch");
        receipt.put("configId", candidate.configId);
        receipt.put("version", candidate.version);
        receipt.put("containsFutureStochasticState", false);
        if (currentConfiguration == null || !currentConfiguration.configId.equals(candidate.configId)
                || currentConfiguration.version != candidate.version) {
            receipt.put("accepted", false);
            receipt.put("reason", "configuration_not_active");
            return receipt;
        }
        Map<String, Object> taskContext = request == null || !(request.get("task") instanceof Map)
                ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>((Map<String, Object>) request.get("task"));
        Map<String, Object> scalars = bridge.getCurrentDecisionScalars();
        if (!taskContext.containsKey("taskId")) taskContext.put("taskId", scalars.get("taskId"));
        Object rule = currentConfiguration.materialize(taskContext);
        receipt.put("task", taskContext);
        receipt.put("resolvedRule", rule);
        if (!(rule instanceof Map)) {
            receipt.put("accepted", false);
            receipt.put("reason", "no_matching_reusable_rule");
            return receipt;
        }
        if (numberAsLong(scalars.get("taskId"), -1L) < 0L) {
            receipt.put("accepted", false);
            receipt.put("reason", "no_pending_decision");
            return receipt;
        }
        RlAction action = actionFromRule((Map<?, ?>) rule, scalars);
        if (simulation != null && simulation.isPaused()) {
            simulation.resume();
        }
        ExecutionReceipt execution = bridge.submitAction(action);
        bridge.recordDeliveredReceipt(execution);
        receipt.put("accepted", execution.accepted);
        receipt.put("reason", execution.accepted ? "persistent_rule_dispatched" : execution.fallbackReason);
        receipt.put("dispatchSource", "persistent_execution_rule");
        receipt.put("executionReceipt", execution.toMap());
        return receipt;
    }

    private void validateBindings(Map<String, Object> bindings, List<String> reasons) {
        if (bindings == null) return;
        for (Object raw : bindings.values()) {
            Object candidate = raw;
            if (raw instanceof Map) {
                Map<?, ?> map = (Map<?, ?>) raw;
                if (map.containsKey("assignment")) candidate = map.get("assignment");
                else if (map.containsKey("action")) candidate = map.get("action");
                validateResourceFields(map, reasons);
            }
            if (candidate instanceof Map) validateResourceFields((Map<?, ?>) candidate, reasons);
        }
    }

    private void validateResourceFields(Map<?, ?> binding, List<String> reasons) {
        for (String key : new String[] {"cpuShare", "cpu_share", "bandwidthShare", "bandwidth_share", "txPowerRatio", "tx_power_ratio"}) {
            Object value = binding.get(key);
            if (value instanceof Number) {
                double number = ((Number) value).doubleValue();
                if (Double.isNaN(number) || Double.isInfinite(number) || number <= 0.0 || number > 1.0) {
                    reasons.add("invalid_resource_binding:" + key);
                }
            }
        }
        Object contactEnd = binding.get("contactEndSec");
        if (contactEnd instanceof Number && simulation != null && simulation.clock() >= ((Number) contactEnd).doubleValue()) {
            reasons.add("expired_contact");
        }
        if (binding.containsKey("targetVmId") || binding.containsKey("selectedVmId") || binding.containsKey("vmId")) {
            long target = numberAsLong(binding.get("targetVmId"), numberAsLong(binding.get("selectedVmId"), numberAsLong(binding.get("vmId"), -1L)));
            if (!vmIdAvailable(target)) reasons.add("target_unavailable");
        }
        if (binding.containsKey("targetVmIndex") || binding.containsKey("vmIndex")) {
            int index = numberAsInt(binding.get("targetVmIndex"), numberAsInt(binding.get("vmIndex"), -1));
            if (simulationManager != null && simulationManager.getServersManager() != null
                    && (index < 0 || index >= simulationManager.getServersManager().getVmList().size())) {
                reasons.add("target_unavailable");
            }
        }
    }

    private boolean vmIdAvailable(long target) {
        if (target < 0L || simulationManager == null || simulationManager.getServersManager() == null) return false;
        for (org.cloudbus.cloudsim.vms.Vm vm : simulationManager.getServersManager().getVmList()) {
            if (vm.getId() == target) return true;
        }
        return false;
    }

    private static RlAction actionFromRule(Map<?, ?> rule, Map<String, Object> scalars) {
        RlAction action = new RlAction();
        action.decisionId = numberAsLong(scalars.get("decisionId"), -1L);
        action.requestId = action.decisionId;
        action.taskId = numberAsLong(scalars.get("taskId"), -1L);
        action.targetVmIndex = numberAsInt(rule.get("targetVmIndex"), numberAsInt(rule.get("vmIndex"), -1));
        action.targetVmId = numberAsLong(rule.get("targetVmId"), -1L);
        action.selectedVmId = numberAsLong(rule.get("selectedVmId"), numberAsLong(rule.get("vmId"), -1L));
        action.policyUpperAction = numberAsInt(rule.get("policyUpperAction"), numberAsInt(rule.get("abstractAction"), -1));
        action.abstractAction = numberAsInt(rule.get("abstractAction"), -1);
        action.policyUpperActionName = rule.get("policyUpperActionName") == null ? "persistent_rule" : String.valueOf(rule.get("policyUpperActionName"));
        action.abstractActionName = rule.get("abstractActionName") == null ? "persistent_rule" : String.valueOf(rule.get("abstractActionName"));
        action.cpuShare = numberAsDouble(rule.get("cpuShare"), 1.0);
        action.bandwidthShare = numberAsDouble(rule.get("bandwidthShare"), 1.0);
        action.txPowerRatio = numberAsDouble(rule.get("txPowerRatio"), 1.0);
        Object bindingMode = rule.get("bindingMode");
        if (bindingMode == null) bindingMode = rule.get("continuous_resource_binding_mode");
        if (bindingMode == null) bindingMode = rule.get("continuousResourceBindingMode");
        if (bindingMode != null) action.extra.put("bindingMode", String.valueOf(bindingMode));
        return action;
    }

    private static double numberAsDouble(Object value, double fallback) {
        return value instanceof Number ? ((Number) value).doubleValue() : fallback;
    }

    private void ensureTopologyReady() {
        if (simulationManager == null || simulationManager.getTopologyOracle() == null
                || simulationManager.getContactPlan() == null || simulation == null) {
            throw new IllegalStateException("topology backend is not ready");
        }
    }

    private DataCenter findActiveNode(TopologyNodeRef ref) {
        for (DataCenter dataCenter : simulationManager.getServersManager().getDatacenterList()) {
            if (dataCenter.getType() == ref.type && dataCenter.getDeviceID() == ref.deviceId) return dataCenter;
        }
        return null;
    }

    private static TopologyNodeRef parseNodeRef(Map<String, Object> request, String field) {
        if (request == null || !(request.get(field) instanceof Map)) {
            throw new IllegalArgumentException("missing " + field + " node object");
        }
        Map<?, ?> node = (Map<?, ?>) request.get(field);
        Object typeValue = node.get("type");
        Object idValue = node.get("deviceId");
        if (typeValue == null || !(idValue instanceof Number)) {
            throw new IllegalArgumentException(field + " requires type and positive deviceId");
        }
        try {
            simulationParameters.TYPES type = simulationParameters.TYPES.valueOf(String.valueOf(typeValue));
            return new TopologyNodeRef(type, ((Number) idValue).intValue());
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("invalid " + field + " node: " + typeValue + "/" + idValue);
        }
    }

    private static Map<String, Object> nodeMap(TopologyNodeRef ref) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("type", ref.type.name());
        result.put("deviceId", ref.deviceId);
        return result;
    }

    /**
     * Cheap monitor path.  No call to bridge.getState() or RlStateBuilder is
     * allowed here: the DTO is constructed from scalar bridge metadata and
     * aggregate counters only.
     */
    public Map<String, Object> getMonitorState() {
        Map<String, Object> scalars = bridge.getCurrentDecisionScalars();
        CheapMonitorState monitor = new CheapMonitorState();
        monitor.sessionId = sessionId;
        monitor.status = String.valueOf(scalars.get("status"));
        double now = simulation == null ? 0.0 : simulation.clock();
        monitor.simulationTimeSec = now;
        monitor.currentDecisionId = numberAsLong(scalars.get("decisionId"), -1L);
        monitor.currentTaskId = numberAsLong(scalars.get("taskId"), -1L);
        monitor.sourceDeviceId = numberAsInt(scalars.get("sourceDeviceId"), -1);
        monitor.currentConfigId = currentConfiguration == null ? null : currentConfiguration.configId;
        monitor.currentConfigVersion = currentConfiguration == null ? 0L : currentConfiguration.version;
        if (currentConfiguration != null && Double.isFinite(configurationAppliedAtSec)) {
            monitor.configurationAgeSec = Math.max(0.0, now - configurationAppliedAtSec);
        }
        List<Task> tasks = simulationManager == null ? null : simulationManager.getTasksList();
        populateArrivedWorkload(monitor, tasks, now);
        populateCurrentServiceRate(monitor, tasks, now);
        populateCurrentTransferContact(monitor, now);
        populatePhaseAwareWorkloadAndApplicability(monitor, tasks, now);
        monitor.queueSummary.put("pendingDecision", monitor.currentTaskId >= 0 ? 1.0 : 0.0);
        monitor.smallNeighborhood.put("sourceDeviceId", monitor.sourceDeviceId);
        monitor.smallNeighborhood.put("topologySource", "TopologyOracle");
        monitor.cachedState.put("lastReceiptAvailable", bridge.getLastExecutionReceipt() != null);
        monitor.cachedState.put("completionReceiptAvailable", bridge.getLastCompletionReceipt() != null);
        monitor.degradationIndicators.put("simulationFailure", failure == null ? 0.0 : 1.0);
        monitor.instrumentation.put("candidateEvaluations", 0L);
        monitor.instrumentation.put("fullStateBuilderInvoked", false);
        monitor.instrumentation.put("containsFutureStochasticState", false);
        monitor.instrumentation.put("serviceRateObservedAvailable", monitor.serviceRateObserved != null);
        monitor.instrumentation.put("serviceRateLowerBoundAvailable", monitor.serviceRateLowerBound != null && monitor.serviceBoundCertified);
        monitor.instrumentation.put("serviceBoundCertified", monitor.serviceBoundCertified);
        monitor.instrumentation.put("serviceHorizonAvailable", monitor.serviceHorizonSec != null);
        monitor.instrumentation.put("serviceEvidenceStatus", monitor.serviceEvidenceStatus);
        monitor.instrumentation.put("serviceHorizonSource", monitor.serviceHorizonSource);
        monitor.instrumentation.put("phaseStateUncertain", monitor.phaseStateUncertain);
        monitor.instrumentation.put("computeReadyWorkloadMi", monitor.computeReadyWorkloadMi);
        monitor.instrumentation.put("executingWorkloadMi", monitor.executingWorkloadMi);
        monitor.instrumentation.put("waitingDispatchWorkloadMi", monitor.waitingDispatchWorkloadMi);
        monitor.instrumentation.put("networkRemainingBits", monitor.networkRemainingBits);
        monitor.instrumentation.put("contactApplicabilityKnown", monitor.contactApplicabilityKnown);
        monitor.instrumentation.put("contactEvidenceRequired", monitor.contactEvidenceRequired);
        monitor.instrumentation.put("contactEvidenceStatus", monitor.contactEvidenceStatus);
        monitor.instrumentation.put("deadlineEvidenceStatus", monitor.deadlineEvidenceStatus);
        monitor.instrumentation.put("uncertaintyEvidenceStatus", monitor.uncertaintyEvidenceStatus);
        if (!monitor.instrumentation.containsKey("contactSlackAvailable")) {
            monitor.instrumentation.put("contactSlackAvailable", false);
        }
        monitor.instrumentation.put("predictionUncertaintyAvailable", false);
        monitor.instrumentation.put("payloadKind", "cheap_monitor");
        if (!monitor.cachedState.containsKey("serviceRateSource")) {
            monitor.cachedState.put("serviceRateSource", "unavailable_at_cheap_monitor_cost");
        }
        if (!monitor.cachedState.containsKey("contactSlackSource")) {
            monitor.cachedState.put("contactSlackSource", "unavailable_at_cheap_monitor_cost");
        }
        monitor.cachedState.put("predictionUncertaintySource", "unavailable_not_calibrated");
        return monitor.toMap();
    }

    /**
     * Derives only native task/network phase aggregates.  It does not inspect
     * candidate VMs or build planner state.  A compute certificate is emitted
     * only when every compute-phase task has a created, working VM processor
     * and the existing simulation update interval provides the explicit
     * certificate horizon.
     */
    private void populatePhaseAwareWorkloadAndApplicability(
            CheapMonitorState monitor, List<Task> tasks, double now) {
        double computeReady = 0.0;
        double executing = 0.0;
        double waitingDispatch = 0.0;
        double networkBits = 0.0;
        boolean phaseUncertain = false;
        boolean hadWork = false;
        boolean remoteRequired = false;
        boolean applicabilityKnown = true;
        boolean certificateEligible = true;
        Set<Long> computeVmIds = new HashSet<Long>();
        Set<Long> activeNetworkTasks = new HashSet<Long>();
        if (simulationManager != null && simulationManager.getNetworkModel() != null
                && simulationManager.getNetworkModel().getTransferProgressList() != null) {
            for (FileTransferProgress transfer : new ArrayList<FileTransferProgress>(
                    simulationManager.getNetworkModel().getTransferProgressList())) {
                if (transfer == null || transfer.getTask() == null
                        || transfer.getRemainingFileSize() <= 0.0
                        || transfer.getTask().getTime() > now + 1.0e-9
                        || isTerminalTask(transfer.getTask())) {
                    continue;
                }
                activeNetworkTasks.add(Long.valueOf(transfer.getTask().getId()));
                networkBits += Math.max(0.0, transfer.getRemainingFileSize());
            }
        }
        if (tasks != null) {
            for (Task task : tasks) {
                if (task == null || task.getTime() > now + 1.0e-9 || isTerminalTask(task)) continue;
                hadWork = true;
                long remaining = Math.max(0L, task.getLength() - task.getFinishedLengthSoFar());
                DataCenter destination = task.getVm() == null || task.getVm().getHost() == null
                        || !(task.getVm().getHost().getDatacenter() instanceof DataCenter)
                        ? null : (DataCenter) task.getVm().getHost().getDatacenter();
                if (destination == null) {
                    applicabilityKnown = false;
                    certificateEligible = false;
                    waitingDispatch += remaining;
                    continue;
                }
                if (task.getEdgeDevice() == null) {
                    applicabilityKnown = false;
                } else if (task.getEdgeDevice() != destination) {
                    remoteRequired = true;
                }
                if (activeNetworkTasks.contains(Long.valueOf(task.getId()))) {
                    continue;
                }
                String status = task.getStatus() == null ? "" : task.getStatus().name();
                if ("INEXEC".equals(status)) {
                    executing += remaining;
                    if (!task.getVm().isCreated() || !task.getVm().isWorking()
                            || task.getVm().getProcessor() == null
                            || task.getVm().getProcessor().getMips() <= 0.0) {
                        certificateEligible = false;
                    } else {
                        computeVmIds.add(Long.valueOf(task.getVm().getId()));
                    }
                } else if ("READY".equals(status) || "QUEUED".equals(status) || "PAUSED".equals(status)) {
                    computeReady += remaining;
                    if (!task.getVm().isCreated() || !task.getVm().isWorking()
                            || task.getVm().getProcessor() == null
                            || task.getVm().getProcessor().getMips() <= 0.0) {
                        certificateEligible = false;
                    } else {
                        computeVmIds.add(Long.valueOf(task.getVm().getId()));
                    }
                } else {
                    phaseUncertain = true;
                    certificateEligible = false;
                    waitingDispatch += remaining;
                }
            }
        }
        monitor.computeReadyWorkloadMi = computeReady;
        monitor.executingWorkloadMi = executing;
        monitor.waitingDispatchWorkloadMi = waitingDispatch;
        monitor.networkRemainingBits = networkBits;
        monitor.phaseStateUncertain = phaseUncertain;

        boolean computeApplicable = computeReady + executing + waitingDispatch > 0.0;
        monitor.serviceEvidenceApplicable = computeApplicable;
        monitor.serviceEvidenceStatus = computeApplicable ? "UNAVAILABLE" : "NOT_APPLICABLE";
        monitor.serviceHorizonSec = null;
        monitor.serviceHorizonSource = "unavailable_at_cheap_monitor_cost";
        monitor.serviceRateLowerBound = null;
        monitor.serviceBoundCertified = false;
        if (computeApplicable && certificateEligible && computeVmIds.size() > 0
                && simulationParameters.UPDATE_INTERVAL > 0.0) {
            double guaranteedMips = 0.0;
            boolean capacityAvailable = true;
            for (Long vmId : computeVmIds) {
                Vm selected = null;
                if (tasks != null) {
                    for (Task task : tasks) {
                        if (task != null && task.getVm() != null && task.getVm().getId() == vmId.longValue()) {
                            selected = task.getVm();
                            break;
                        }
                    }
                }
                if (selected == null || selected.getProcessor() == null
                        || selected.getProcessor().getMips() <= 0.0) {
                    capacityAvailable = false;
                    break;
                }
                guaranteedMips += selected.getProcessor().getMips();
            }
            if (capacityAvailable && guaranteedMips > 0.0) {
                monitor.serviceRateLowerBound = guaranteedMips;
                monitor.serviceBoundCertified = true;
                monitor.serviceHorizonSec = simulationParameters.UPDATE_INTERVAL;
                monitor.serviceHorizonSource = "simulation_update_interval";
                monitor.serviceRateSource = "cloudsim_vm_processor_mips_assigned_compute_phases";
                monitor.serviceBoundSemantics = "conservative_assigned_vm_processor_capacity_over_update_interval";
                monitor.serviceEvidenceStatus = "AVAILABLE";
            }
        }

        monitor.contactApplicabilityKnown = !hadWork || applicabilityKnown;
        monitor.contactEvidenceRequired = remoteRequired;
        if (!hadWork || (monitor.contactApplicabilityKnown && !remoteRequired)) {
            monitor.contactEvidenceStatus = "NOT_APPLICABLE";
        } else if (!monitor.contactApplicabilityKnown) {
            monitor.contactEvidenceStatus = "UNAVAILABLE";
        } else {
            monitor.contactEvidenceStatus = monitor.contactSlack.isEmpty() ? "UNAVAILABLE" : "AVAILABLE";
        }
        monitor.deadlineEvidenceApplicable = hadWork;
        monitor.deadlineEvidenceAvailable = !monitor.deadlineSlack.isEmpty();
        monitor.deadlineEvidenceStatus = !hadWork ? "NOT_APPLICABLE"
                : (monitor.deadlineEvidenceAvailable ? "AVAILABLE" : "UNAVAILABLE");
        monitor.uncertaintyEvidenceApplicable = hadWork;
        monitor.uncertaintyEvidenceAvailable = false;
        monitor.uncertaintyEvidenceStatus = hadWork ? "UNAVAILABLE" : "NOT_APPLICABLE";
    }

    /**
     * Reports only the service actually visible on VMs already assigned to
     * arrived, unfinished tasks.  It does not enumerate candidate VMs and does
     * not treat VM inventory as service capacity.
     */
    private static void populateCurrentServiceRate(CheapMonitorState monitor, List<Task> tasks, double now) {
        if (tasks == null) return;
        Set<Long> observedVmIds = new HashSet<Long>();
        double serviceRateMips = 0.0;
        int observedVms = 0;
        for (Task task : tasks) {
            if (task == null || task.getTime() > now + 1.0e-9 || isTerminalTask(task)) continue;
            Vm vm = task.getVm();
            if (vm == null || vm == Vm.NULL || !observedVmIds.add(vm.getId())) continue;
            observedVms++;
            double currentMips = vm.getTotalCpuMipsUsage();
            if (!Double.isNaN(currentMips) && !Double.isInfinite(currentMips)) {
                serviceRateMips += Math.max(0.0, currentMips);
            }
        }
        if (observedVms > 0) {
            monitor.serviceRateObserved = serviceRateMips;
            monitor.serviceRateLowerBound = null;
            monitor.serviceBoundCertified = false;
            monitor.serviceRateSource = "cloudsim_vm_scheduler_current_mips";
            monitor.serviceBoundSemantics = "instantaneous_observed_usage_not_future_lower_bound";
            monitor.instrumentation.put("serviceRateObservedAvailable", true);
            monitor.instrumentation.put("serviceRateLowerBoundAvailable", false);
            monitor.instrumentation.put("serviceBoundCertified", false);
            monitor.instrumentation.put("serviceRateScope", "assigned_arrived_tasks");
            monitor.instrumentation.put("serviceRateObservedVmCount", observedVms);
            monitor.cachedState.put("serviceRateSource", monitor.serviceRateSource);
            monitor.cachedState.put("serviceBoundSemantics", monitor.serviceBoundSemantics);
        }
    }

    /**
     * Uses only transfers that are currently present in the native network
     * progress list.  Contact slack is the current contact lifetime minus the
     * remaining transfer time at the native current bandwidth.
     */
    private void populateCurrentTransferContact(CheapMonitorState monitor, double now) {
        if (simulationManager == null || simulationManager.getNetworkModel() == null
                || simulationManager.getContactPlan() == null || simulationManager.getNetworkModel().getTransferProgressList() == null) {
            return;
        }
        int observedTransfers = 0;
        int contactSlackObservations = 0;
        for (FileTransferProgress transfer : new ArrayList<FileTransferProgress>(simulationManager.getNetworkModel().getTransferProgressList())) {
            if (transfer == null || transfer.getTask() == null || transfer.getRemainingFileSize() <= 0.0) continue;
            DataCenter source = transferSource(transfer);
            DataCenter destination = transferDestination(transfer);
            if (source == null || destination == null || source == destination) continue;
            ContactForecast forecast;
            try {
                forecast = simulationManager.getContactPlan().getContactForecast(
                        TopologyOracle.toRef(source), TopologyOracle.toRef(destination), now,
                        simulationParameters.TOPOLOGY_FORECAST_HORIZON_SEC);
            } catch (RuntimeException unavailable) {
                continue;
            }
            String key = "transfer:" + transfer.getTask().getId() + ":" + transfer.getTransferType().name();
            double remainingLifetime = forecast.availableNow ? forecast.remainingLifetimeSec : 0.0;
            monitor.remainingContactLifetime.put(key, remainingLifetime);
            Map<String, Object> next = new LinkedHashMap<String, Object>();
            next.put("availableNow", forecast.availableNow);
            next.put("remainingLifetimeSec", remainingLifetime);
            next.put("source", forecast.source);
            if (forecast.nextContactStartSec != null) next.put("nextContactStartSec", forecast.nextContactStartSec);
            if (forecast.nextContactEndSec != null) next.put("nextContactEndSec", forecast.nextContactEndSec);
            monitor.nextContact.put(key, next);
            observedTransfers++;
            double currentBandwidth = transfer.getCurrentBandwidth();
            if (currentBandwidth > 0.0 && !Double.isNaN(currentBandwidth) && !Double.isInfinite(currentBandwidth)) {
                double remainingTransferTime = transfer.getRemainingFileSize() / currentBandwidth;
                monitor.contactSlack.put(key, remainingLifetime - remainingTransferTime);
                contactSlackObservations++;
            }
        }
        if (observedTransfers > 0) {
            monitor.instrumentation.put("contactSlackAvailable", contactSlackObservations > 0);
            monitor.instrumentation.put("contactObservationCount", observedTransfers);
            monitor.cachedState.put("contactSlackSource", "native_transfer_progress_and_contact_plan");
        }
    }

    private static DataCenter transferSource(FileTransferProgress transfer) {
        Task task = transfer.getTask();
        switch (transfer.getTransferType()) {
        case REQUEST:
            return task.getEdgeDevice();
        case TASK:
            return task.getOrchestrator();
        case RESULTS_TO_ORCH:
            return destinationDataCenter(task);
        case RESULTS_TO_DEV:
            return task.getOrchestrator();
        case CONTAINER:
            return task.getRegistry();
        default:
            return null;
        }
    }

    private static DataCenter transferDestination(FileTransferProgress transfer) {
        Task task = transfer.getTask();
        switch (transfer.getTransferType()) {
        case REQUEST:
            return task.getOrchestrator();
        case TASK:
            return destinationDataCenter(task);
        case RESULTS_TO_ORCH:
            return task.getOrchestrator();
        case RESULTS_TO_DEV:
            return task.getEdgeDevice();
        case CONTAINER:
            return task.getEdgeDevice();
        default:
            return null;
        }
    }

    private static DataCenter destinationDataCenter(Task task) {
        if (task == null || task.getVm() == null || task.getVm() == Vm.NULL
                || task.getVm().getHost() == null
                || !(task.getVm().getHost().getDatacenter() instanceof DataCenter)) {
            return null;
        }
        return (DataCenter) task.getVm().getHost().getDatacenter();
    }

    private static boolean isTerminalTask(Task task) {
        if (task.isFinished()) return true;
        org.cloudbus.cloudsim.cloudlets.Cloudlet.Status status = task.getStatus();
        return status == org.cloudbus.cloudsim.cloudlets.Cloudlet.Status.SUCCESS
                || status == org.cloudbus.cloudsim.cloudlets.Cloudlet.Status.FAILED
                || status == org.cloudbus.cloudsim.cloudlets.Cloudlet.Status.CANCELED
                || status == org.cloudbus.cloudsim.cloudlets.Cloudlet.Status.FAILED_RESOURCE_UNAVAILABLE;
    }

    /**
     * Aggregates only task records that have arrived by the current simulation
     * time.  This is intentionally independent of VM/candidate enumeration and
     * is also used by the DTO regression test.
     */
    static void populateArrivedWorkload(CheapMonitorState monitor, List<Task> tasks, double now) {
        double arrivedTaskCount = 0.0;
        double unfinishedTaskCount = 0.0;
        double totalRemainingWorkload = 0.0;
        double futureTaskCount = 0.0;
        double waitingDispatchWorkload = 0.0;
        double computeReadyWorkload = 0.0;
        double executingWorkload = 0.0;
        boolean phaseUncertain = false;
        if (tasks != null) {
            for (Task task : tasks) {
                if (task == null) continue;
                if (task.getTime() > now + 1.0e-9) {
                    futureTaskCount += 1.0;
                    continue;
                }
                arrivedTaskCount += 1.0;
                if (isTerminalTask(task)) continue;
                unfinishedTaskCount += 1.0;
                long remaining = Math.max(0L, task.getLength() - task.getFinishedLengthSoFar());
                totalRemainingWorkload += remaining;
                if (task.getVm() == null || task.getVm() == Vm.NULL) {
                    waitingDispatchWorkload += remaining;
                } else {
                    String status = task.getStatus() == null ? "" : task.getStatus().name();
                    if ("INEXEC".equals(status)) executingWorkload += remaining;
                    else if ("READY".equals(status) || "QUEUED".equals(status) || "PAUSED".equals(status)) {
                        computeReadyWorkload += remaining;
                    } else {
                        phaseUncertain = true;
                        waitingDispatchWorkload += remaining;
                    }
                }
                String source = task.getEdgeDevice() == null
                        ? "unknown" : String.valueOf(task.getEdgeDevice().getDeviceID());
                String sourceKey = "source:" + source;
                monitor.remainingWorkload.put(sourceKey,
                        monitor.remainingWorkload.containsKey(sourceKey)
                                ? monitor.remainingWorkload.get(sourceKey) + remaining : (double) remaining);
                double deadlineSlack = task.getMaxLatency() - (now - task.getTime());
                monitor.deadlineSlack.put(String.valueOf(task.getId()), deadlineSlack);
            }
        }
        monitor.queueSummary.put("arrivedTaskCount", arrivedTaskCount);
        monitor.queueSummary.put("unfinishedTaskCount", unfinishedTaskCount);
        monitor.remainingWorkload.put("total", totalRemainingWorkload);
        monitor.computeReadyWorkloadMi = computeReadyWorkload;
        monitor.executingWorkloadMi = executingWorkload;
        monitor.waitingDispatchWorkloadMi = waitingDispatchWorkload;
        monitor.networkRemainingBits = 0.0;
        monitor.phaseStateUncertain = phaseUncertain;
        monitor.instrumentation.put("futureTaskCountExcluded", futureTaskCount);
        monitor.instrumentation.put("remainingWorkloadSource", "arrived_unfinished_cloudlets");
        monitor.instrumentation.put("deadlineSlackSource", "Task.maxLatency_minus_current_time");
    }

    /**
     * Unified planner-state endpoint.  The current decision state has already
     * been acquired by the simulation at the orchestration point.  This method
     * applies scope and budget while constructing the response and records the
     * acquisition semantics explicitly; it never calls the full builder.
     */
    public Map<String, Object> getPlannerState(Map<String, Object> request, boolean compatibilityFull) {
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("contractVersion", ControlPhysicalContract.VERSION);
        response.put("payloadKind", "planner_state");
        response.put("simulationTimeSec", simulation == null ? 0.0 : simulation.clock());
        response.put("containsFutureStochasticState", false);
        Map<String, Object> scope = request == null || !(request.get("scope") instanceof Map)
                ? new LinkedHashMap<String, Object>() : new LinkedHashMap<String, Object>((Map<String, Object>) request.get("scope"));
        Map<String, Object> budget = request == null || !(request.get("budget") instanceof Map)
                ? new LinkedHashMap<String, Object>() : new LinkedHashMap<String, Object>((Map<String, Object>) request.get("budget"));
        if (!hasNonEmptyList(scope)) scope.clear();
        String fidelityHint = request == null ? null : String.valueOf(request.get("fidelityHint"));
        int budgetLimit = budgetLimit(budget);
        RlState state = compatibilityFull
                ? bridge.getState()
                : bridge.buildScopedPlannerState(scope, budgetLimit);
        response.put("status", state.status);
        response.put("message", state.message);
        List<Map<String, Object>> candidates = new ArrayList<Map<String, Object>>();
        if (state.candidateVms != null) {
            for (RlState.VmView vm : state.candidateVms) candidates.add(vmMap(vm));
        }
        int sourceCount = bridge.getCurrentCandidateCount();
        response.put("sessionId", state.sessionId);
        response.put("decisionId", state.decisionId);
        response.put("requestId", state.requestId);
        response.put("taskId", state.taskId);
        response.put("sourceDeviceId", state.sourceDeviceId);
        response.put("task", state.task);
        response.put("candidateVms", candidates);
        response.put("actionMask", state.actionMask);
        response.put("abstractActionMask", state.abstractActionMask);
        response.put("abstractActionMaskVisible", state.abstractActionMaskVisible);
        response.put("abstractActionMaskMobilitySafe", state.abstractActionMaskMobilitySafe);
        response.put("abstractActionMaskCompletionSafe", state.abstractActionMaskCompletionSafe);
        response.put("scenarioProfile", state.scenarioProfile);
        response.put("scenarioPhase", state.scenarioPhase);
        response.put("taskType", state.taskType);
        response.put("trafficPhase", state.trafficPhase);
        response.put("configurationViabilityMode", state.configurationViabilityMode);
        response.put("viableCandidateCount", state.viableCandidateCount);
        response.put("inviableCandidateCount", state.inviableCandidateCount);
        response.put("uncertainCandidateCount", state.uncertainCandidateCount);
        response.put("requestedScope", scope);
        response.put("appliedScope", scope);
        response.put("requestedBudget", budget);
        response.put("appliedBudget", budget);
        response.put("fidelityHint", fidelityHint);
        response.put("candidateCountBeforeRestriction", sourceCount);
        response.put("candidateCountAfterRestriction", candidates.size());
        response.put("scopeRestrictionApplied", !scope.isEmpty());
        response.put("budgetRestrictionApplied", budgetLimit >= 0);
        response.put("budgetAppliedDuringAcquisition", !compatibilityFull && budgetLimit >= 0);
        response.put("postFilterOnly", false);
        response.put("fullStateEquivalent", compatibilityFull && scope.isEmpty() && budget.isEmpty());
        response.put("readEntities", new String[] {"current_task", "selected_candidates", "current_contact_cache"});
        Map<String, Object> acquisition = new LinkedHashMap<String, Object>();
        acquisition.put("mode", compatibilityFull ? "legacy_full_state_compatibility" : "native_scoped_candidate_acquisition");
        acquisition.put("fullStateBuilderInvoked", false);
        acquisition.put("candidateEvaluations", compatibilityFull ? 0L : candidates.size());
        acquisition.put("containsFutureStochasticState", false);
        acquisition.put("requestedScopeAppliedAt", "response_acquisition");
        response.put("acquisition", acquisition);
        return response;
    }

    private static boolean hasNonEmptyList(Map<String, Object> values) {
        if (values == null || values.isEmpty()) return false;
        for (Object value : values.values()) {
            if (value instanceof List && !((List<?>) value).isEmpty()) return true;
        }
        return false;
    }

    public Map<String, Object> getDecisionPlaneStats() {
        Map<String, Object> result = bridge.getDecisionPlaneStats();
        result.put("cheapMonitorEndpoint", "/get_monitor_state");
        result.put("plannerEndpoint", "/get_planner_state");
        result.put("containsFutureStochasticState", false);
        return result;
    }

    private static Map<String, Object> linkMap(LinkSnapshot snapshot) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("sourceType", snapshot.sourceType.name());
        result.put("sourceDeviceId", snapshot.source.deviceId);
        result.put("destinationType", snapshot.destinationType.name());
        result.put("destinationDeviceId", snapshot.destination.deviceId);
        result.put("timeSec", snapshot.timeSec);
        result.put("distanceMeters", snapshot.distanceMeters);
        result.put("geometryVisible", snapshot.geometryVisible);
        result.put("withinRange", snapshot.withinRange);
        result.put("available", snapshot.available);
        result.put("maxRangeMeters", snapshot.maxRangeMeters);
        result.put("elevationDeg", snapshot.elevationDeg);
        return result;
    }

    public void recordTimeoutSuspected() {
        bridge.recordTimeoutSuspected();
    }

    public Map<String, Object> readMetrics() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("sessionId", sessionId);
        result.put("finished", bridge.isFinished());
        result.put("closed", bridge.isClosed());
        if (simulation != null) {
            result.put("simulationClock", simulation.clock());
        }
        if (simLog != null) {
            result.putAll(simLog.getMetricsSnapshot());
        }
        if (simulationManager != null && simulationManager.getBroker() != null) {
            result.put("finishedCloudlets", simulationManager.getBroker().getCloudletFinishedList().size());
        }
        if (simulationManager != null && simulationManager.getTasksList() != null) {
            result.put("totalTasks", simulationManager.getTasksList().size());
        }
        result.put("energyCounterUnit", "Wh");
        result.put("energyCounterSemantics", "cumulative_total_across_all_datacenters");
        result.put("energyCounterIsCumulative", true);
        result.put("energyCounterLabelWarning", "SimLog legacy labels say W/dBW, but the implementation accumulates energy counter deltas in Wh.");
        RlCompletionReceipt completion = bridge.getLastCompletionReceipt();
        result.put("completionReceiptAvailable", completion != null);
        if (completion != null) {
            result.put("lastCompletionReceipt", completion.toMap());
        }
        result.put("completionReceipts", bridge.getCompletionReceiptMaps());
        Map<String, Object> binding = RlResourceBindingAudit.metadata(
                receiptProfileOrCandidate(bridge.getLastExecutionReceipt()));
        result.putAll(binding);
        result.put("scenarioProfile", simulationParameters.RL_SCENARIO_PROFILE);
        result.put("taskSourceMode", simulationParameters.RL_TASK_SOURCE_MODE);
        result.put("successProfile", simulationParameters.RL_SUCCESS_PROFILE);
        result.put("actionMaskMode", simulationParameters.RL_ACTION_MASK_MODE);
        result.put("minLinkSurvivalMarginSec", simulationParameters.RL_MIN_LINK_SURVIVAL_MARGIN_SEC);
        result.put("isControlledRlScenario", simulationParameters.RL_IS_CONTROLLED_SCENARIO);
        return result;
    }

    private RlResourceProfile receiptProfileOrCandidate(ExecutionReceipt receipt) {
        if (receipt != null && receipt.resourceProfile != null) {
            return receipt.resourceProfile;
        }
        return RlResourceProfile.fromAction(null, RlResourceBindingMode.candidate_only);
    }

    public void close() {
        bridge.close();
        try {
            if (simulation != null) {
                simulation.terminate();
            }
        } catch (Throwable ignored) {
        }
        try {
            if (simulationThread != null) {
                simulationThread.join(3000L);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }
}
