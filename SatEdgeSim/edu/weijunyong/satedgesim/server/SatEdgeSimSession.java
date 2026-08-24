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
    private final boolean strictPhysicalClaims;

    private Thread simulationThread;
    private CloudSim simulation;
    private SimulationManager simulationManager;
    private SimLog simLog;
    private volatile Throwable failure;
    private ExecutionConfiguration currentConfiguration;
    private double configurationAppliedAtSec = Double.NaN;
    private long configurationReceiptSequence = 0L;
    private long interventionEvidenceSequence = 0L;
    private long worldVersion = 0L;
    private long controlStateRevision = 0L;
    private long protocolEventSequence = 0L;
    private String lastControlIdentityDigest;
    private String lastPhysicalAdvanceReceiptId;
    private long lastPhysicalAdvanceWorldVersion = -1L;
    private final List<Map<String, Object>> interventionEvidence = new ArrayList<Map<String, Object>>();
    private final List<Map<String, Object>> protocolEvents = new ArrayList<Map<String, Object>>();
    private final Map<String, ValidationReceipt> validationReceipts = new LinkedHashMap<String, ValidationReceipt>();
    private final List<Map<String, Object>> persistentRuleRuntimeEffects = new ArrayList<Map<String, Object>>();
    private boolean controlEpochPausedForActivation = false;

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
        this.strictPhysicalClaims = this.resetRequest.strictPhysicalClaims;
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
        RlNativeResourceBindingManager.resetForSimulation();
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

    /** Reset/health compatibility path that cannot trigger legacy full acquisition. */
    public RlState getLightweightState() {
        RlState state = bridge.getLightweightStateSnapshot();
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
        response.put("forecastProvenance", "deterministic_predictable_contact_plan");
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
        response.put("source", "legacy_full_state_compatibility");
        response.put("legacyFullStateAccessObserved", true);
        response.put("publicationEligibleForScopedPlannerState", false);
        response.put("acquisition", bridge.getCurrentAcquisitionEvidence());
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

    private static List<String> unsupportedAcquisitionBudgetDimensions(Map<String, Object> budget) {
        List<String> unsupported = new ArrayList<String>();
        if (budget == null) return unsupported;
        for (String key : budget.keySet()) {
            if (!"max_candidate_count".equals(key) && !"maxCandidateCount".equals(key)
                    && !"candidateCount".equals(key)) {
                unsupported.add(key);
            }
        }
        return unsupported;
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
        refreshControlStateIdentity();
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("active", currentConfiguration != null);
        result.put("simulationTimeSec", simulation == null ? 0.0 : simulation.clock());
        result.put("configId", currentConfiguration == null ? null : currentConfiguration.configId);
        result.put("version", currentConfiguration == null ? 0L : currentConfiguration.version);
        result.put("configurationAgeSec", currentConfiguration == null || !Double.isFinite(configurationAppliedAtSec)
                ? null : Math.max(0.0, simulation.clock() - configurationAppliedAtSec));
        result.put("configurationAgeFromRuntimeTimestampSec", currentConfiguration == null || simulation == null
                ? null : currentConfiguration.ageAt(simulation.clock()));
        result.put("configurationExpired", currentConfiguration != null && simulation != null
                && currentConfiguration.isExpired(simulation.clock()));
        result.put("worldVersion", worldVersion);
        result.put("controlStateRevision", controlStateRevision);
        result.put("worldIdentityDigest", currentControlIdentityDigest());
        result.put("configuration", currentConfiguration == null ? null : currentConfiguration.toMap());
        result.put("containsFutureStochasticState", false);
        return result;
    }

    public synchronized Map<String, Object> validateConfiguration(Map<String, Object> request) {
        if (request != null && (request.containsKey("patch") || request.containsKey("configurationPatch")
                || request.containsKey("baseConfigurationVersion") || request.containsKey("base_configuration_version")
                || request.containsKey("requestedScope") || request.containsKey("requested_scope")
                || request.containsKey("taskAssignmentChanges") || request.containsKey("task_assignment_changes")
                || request.containsKey("routeChanges") || request.containsKey("route_changes")
                || request.containsKey("resourceChanges") || request.containsKey("resource_changes")
                || request.containsKey("cpuAllocationChanges") || request.containsKey("cpu_allocation_changes")
                || request.containsKey("bandwidthAllocationChanges") || request.containsKey("bandwidth_allocation_changes")
                || request.containsKey("priorityChanges") || request.containsKey("priority_changes")
                || request.containsKey("persistentRuleChanges") || request.containsKey("persistent_rule_changes"))) {
            ConfigurationPatch patch = ConfigurationPatch.fromRequest(request);
            refreshControlStateIdentity();
            double now = simulation == null ? 0.0 : simulation.clock();
            ReconfigurationExecutor executor = new ReconfigurationExecutor(simulationManager, now, worldVersion);
            Map<String, Object> validation = executor.validate(currentConfiguration, patch, strictPhysicalClaims).toMap();
            validation.put("receiptType", "configuration_patch_validation");
            validation.put("validationOnly", true);
            validation.put("validationSource", "satedgesim_native_runtime");
            validation.put("serverWorldIdentityDigest", currentControlIdentityDigest());
            validation.put("controlStateRevision", controlStateRevision);
            if (Boolean.TRUE.equals(validation.get("accepted"))) {
                String delayReason = physicalDelayValidationReason(patch);
                if (delayReason != null) {
                    validation.put("accepted", false);
                    validation.put("decisionStatus", "REPLAN_REQUIRED");
                    validation.put("rejectionReason", delayReason);
                } else if (strictPhysicalClaims && currentConfiguration != null) {
                    ValidationReceipt receipt = issueValidationReceipt(patch, now);
                    validation.put("validationReceipt", receipt.toMap());
                    validation.put("validationReceiptId", receipt.validationReceiptId);
                    validation.put("validationReceiptToken", receipt.validationReceiptId);
                    validation.put("validatedAfterPhysicalAdvance", receipt.validatedAfterPhysicalAdvance);
                }
            }
            validation.put("interventionId", patch.originatingInterventionId);
            recordProtocolEvent(Boolean.TRUE.equals(validation.get("accepted"))
                    ? "post_delay_validation" : "post_delay_validation_rejected", validation);
            return validation;
        }
        refreshControlStateIdentity();
        PersistentExecutionConfiguration candidate = PersistentExecutionConfiguration.fromRequest(request);
        Map<String, Object> receipt = new LinkedHashMap<String, Object>();
        receipt.put("receiptType", "configuration_validation");
        receipt.put("contractVersion", ControlPhysicalContract.VERSION);
        receipt.put("configId", candidate.configId);
        receipt.put("version", candidate.version);
        receipt.put("simulationTimeSec", simulation == null ? 0.0 : simulation.clock());
        receipt.put("worldVersion", worldVersion);
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
        if (simulation != null && candidate.expiresAtSimTimeSec == candidate.expiresAtSimTimeSec
                && simulation.clock() >= candidate.expiresAtSimTimeSec) {
            reasons.add("expired_configuration");
        }
        if (candidate.assignments.isEmpty() && candidate.reusableRules.isEmpty()) {
            reasons.add("no_persistent_execution_rule");
        }
        validateBindings(candidate.assignments, reasons);
        validateBindings(candidate.reusableRules, reasons);
        validateBindings(candidate.resourceAllocations, reasons);
        validateBindings(candidate.cpuAllocations, reasons);
        validateBindings(candidate.bandwidthAllocations, reasons);
        validateScalarAllocations(candidate.cpuAllocations, "cpu", reasons);
        validateScalarAllocations(candidate.bandwidthAllocations, "bandwidth", reasons);
        receipt.put("accepted", reasons.isEmpty());
        receipt.put("reasons", reasons);
        receipt.put("validationSource", "satedgesim_physical_backend");
        receipt.put("targetAvailabilityChecked", simulationManager != null && simulationManager.getServersManager() != null);
        receipt.put("simTimeExpiryAuthoritative", true);
        return receipt;
    }

    private ValidationReceipt issueValidationReceipt(ConfigurationPatch patch, double now) {
        if (patch.originatingInterventionId == null || patch.originatingInterventionId.trim().isEmpty()) {
            throw new IllegalArgumentException("originatingInterventionId is mandatory for strict patch validation");
        }
        long issued = System.currentTimeMillis();
        String id = sessionId + "-validation-" + UUID.randomUUID().toString();
        ValidationReceipt receipt = new ValidationReceipt(
                id,
                sessionId,
                patch.originatingInterventionId,
                patch.baseConfigurationVersion == null ? currentConfiguration.version : patch.baseConfigurationVersion.longValue(),
                patch.observedWorldVersion == null ? worldVersion : patch.observedWorldVersion.longValue(),
                worldVersion,
                controlStateRevision,
                currentControlIdentityDigest(),
                ConfigurationPatchDigest.scope(patch.requestedScope),
                ConfigurationPatchDigest.patch(patch),
                patch.physicalAdvanceReceiptId,
                now,
                issued,
                issued + 300000L,
                patch.physicalAdvanceReceiptId != null,
                true);
        validationReceipts.put(receipt.validationReceiptId, receipt);
        while (validationReceipts.size() > 512) {
            String first = validationReceipts.keySet().iterator().next();
            validationReceipts.remove(first);
        }
        return receipt;
    }

    private ValidationReceipt verifyValidationReceipt(ConfigurationPatch patch) {
        String id = patch.validationReceiptId;
        if (id == null || id.trim().isEmpty()) {
            patch.validationReceiptFailureReason = "missing_validation_receipt";
            return null;
        }
        ValidationReceipt receipt = validationReceipts.get(id);
        if (receipt == null) {
            patch.validationReceiptFailureReason = "invalid_validation_receipt";
            return null;
        }
        if (!sessionId.equals(receipt.sessionId)) {
            patch.validationReceiptFailureReason = "validation_receipt_session_mismatch";
            return null;
        }
        if (receipt.expired(System.currentTimeMillis())) {
            patch.validationReceiptFailureReason = "validation_receipt_expired";
            return null;
        }
        if (receipt.singleUse && receipt.consumed) {
            patch.validationReceiptFailureReason = "validation_receipt_already_consumed";
            return null;
        }
        if (patch.originatingInterventionId == null
                || !receipt.interventionId.equals(patch.originatingInterventionId)) {
            patch.validationReceiptFailureReason = "validation_receipt_intervention_mismatch";
            return null;
        }
        if (patch.baseConfigurationVersion == null
                || patch.baseConfigurationVersion.longValue() != receipt.baseConfigurationVersion) {
            patch.validationReceiptFailureReason = "validation_receipt_base_configuration_mismatch";
            return null;
        }
        if (!receipt.patchDigest.equals(ConfigurationPatchDigest.patch(patch))) {
            patch.validationReceiptFailureReason = "validation_receipt_patch_mismatch";
            return null;
        }
        if (!receipt.requestedScopeDigest.equals(ConfigurationPatchDigest.scope(patch.requestedScope))) {
            patch.validationReceiptFailureReason = "validation_receipt_scope_mismatch";
            return null;
        }
        refreshControlStateIdentity();
        if (worldVersion != receipt.validatedWorldVersion
                || !receipt.worldIdentityDigest.equals(currentControlIdentityDigest())) {
            patch.validationReceiptFailureReason = "validation_receipt_world_identity_stale";
            return null;
        }
        boolean explicitZeroDelay = patch.planningDelayMetadata != null
                && Boolean.TRUE.equals(patch.planningDelayMetadata.get("zeroDelay"));
        if (!explicitZeroDelay && (patch.physicalAdvanceReceiptId == null
                || !patch.physicalAdvanceReceiptId.equals(receipt.physicalAdvanceReceiptId)
                || !patch.physicalAdvanceReceiptId.equals(lastPhysicalAdvanceReceiptId)
                || lastPhysicalAdvanceWorldVersion != worldVersion)) {
            patch.validationReceiptFailureReason = "validation_receipt_physical_advance_mismatch";
            return null;
        }
        return receipt;
    }

    private String physicalDelayValidationReason(ConfigurationPatch patch) {
        if (!strictPhysicalClaims) return null;
        if (patch.originatingInterventionId == null || patch.originatingInterventionId.trim().isEmpty()) {
            return "missing_originating_intervention_id";
        }
        boolean explicitZeroDelay = patch.planningDelayMetadata != null
                && Boolean.TRUE.equals(patch.planningDelayMetadata.get("zeroDelay"));
        if (explicitZeroDelay) return null;
        if (patch.physicalAdvanceReceiptId == null || patch.physicalAdvanceReceiptId.trim().isEmpty()) {
            return "missing_server_physical_advance_receipt";
        }
        if (!patch.physicalAdvanceReceiptId.equals(lastPhysicalAdvanceReceiptId)
                || lastPhysicalAdvanceWorldVersion != worldVersion) {
            return "physical_advance_receipt_not_current";
        }
        return null;
    }

    private void refreshControlStateIdentity() {
        String digest = computeControlIdentityDigest();
        if (lastControlIdentityDigest == null) {
            lastControlIdentityDigest = digest;
        } else if (!lastControlIdentityDigest.equals(digest)) {
            controlStateRevision += 1L;
            lastControlIdentityDigest = digest;
        }
    }

    private String currentControlIdentityDigest() {
        refreshControlStateIdentity();
        return lastControlIdentityDigest;
    }

    private String computeControlIdentityDigest() {
        Map<String, Object> identity = new LinkedHashMap<String, Object>();
        identity.put("simulationTimeSec", simulation == null ? 0.0 : simulation.clock());
        identity.put("worldVersion", worldVersion);
        identity.put("configurationVersion", currentConfiguration == null ? 0L : currentConfiguration.version);
        List<Map<String, Object>> taskState = new ArrayList<Map<String, Object>>();
        if (simulationManager != null && simulationManager.getTasksList() != null) {
            for (Task task : simulationManager.getTasksList()) {
                if (task == null) continue;
                Map<String, Object> item = new LinkedHashMap<String, Object>();
                item.put("taskId", task.getId());
                item.put("status", task.getStatus() == null ? null : task.getStatus().name());
                item.put("vmId", task.getVm() == null || task.getVm() == Vm.NULL ? null : task.getVm().getId());
                item.put("finishedLength", task.getFinishedLengthSoFar());
                item.put("contactInterrupted", task.isContactInterrupted());
                taskState.add(item);
            }
        }
        identity.put("tasks", taskState);
        List<Map<String, Object>> transfers = new ArrayList<Map<String, Object>>();
        if (simulationManager != null && simulationManager.getNetworkModel() != null
                && simulationManager.getNetworkModel().getTransferProgressList() != null) {
            for (FileTransferProgress transfer : simulationManager.getNetworkModel().getTransferProgressList()) {
                if (transfer == null) continue;
                Map<String, Object> item = new LinkedHashMap<String, Object>();
                item.put("transferId", transfer.getTransferId());
                item.put("remainingKbits", transfer.getRemainingFileSize());
                item.put("transferredKbits", transfer.getTransferredFileSize());
                item.put("contactEndSec", transfer.getContactEndSec());
                item.put("contactInterrupted", transfer.isContactInterrupted());
                transfers.add(item);
            }
        }
        identity.put("transfers", transfers);
        identity.put("nativeBindings", RlNativeResourceBindingManager.debugSnapshot());
        return ConfigurationPatchDigest.object(identity);
    }

    /** Advance CloudSim through its public pause-at/resume mechanism. */
    public synchronized Map<String, Object> advanceWorld(double deltaSec) {
        return jsonSafeMap(advanceWorldInternal(deltaSec, false));
    }

    /**
     * Advance CloudSim for a named intervention.  The intervention identity is
     * protocol metadata carried by the canonical physical-advance endpoint;
     * it does not change the simulator's progression semantics.
     */
    public synchronized Map<String, Object> advanceWorld(double deltaSec, String interventionId,
            boolean plannerCompleted) {
        recordInterventionAdvanceStart(interventionId, deltaSec, false, plannerCompleted);
        Map<String, Object> result = jsonSafeMap(advanceWorldInternal(deltaSec, false));
        annotateAndRecordInterventionAdvance(result, interventionId);
        return result;
    }

    /**
     * Advance a control-plane decision epoch while the active persistent
     * configuration remains responsible for task decisions.  The simulation
     * is left paused at the target so validation and configuration activation
     * happen before the next task-level request can block the simulation.
     */
    public synchronized Map<String, Object> advanceControlEpoch(double deltaSec) {
        return advanceControlEpoch(deltaSec, null, false);
    }

    /** Advance a named intervention while the old persistent configuration remains active. */
    public synchronized Map<String, Object> advanceControlEpoch(double deltaSec, String interventionId,
            boolean plannerCompleted) {
        recordInterventionAdvanceStart(interventionId, deltaSec, true, plannerCompleted);
        if (currentConfiguration == null) {
            Map<String, Object> rejected = new LinkedHashMap<String, Object>();
            rejected.put("accepted", false);
            rejected.put("reason", "no_active_persistent_configuration");
            rejected.put("physicalClockAdvanced", false);
            rejected.put("controlEpoch", true);
            rejected.put("controlEpochCompleted", false);
            rejected.put("controlEpochBlocked", true);
            rejected.put("pausedForConfigurationActivation", false);
            rejected.put("interventionId", interventionId);
            recordProtocolEvent("physical_advance_rejected", rejected);
            return jsonSafeMap(rejected);
        }
        Map<String, Object> result = jsonSafeMap(advanceWorldInternal(deltaSec, true));
        annotateAndRecordInterventionAdvance(result, interventionId);
        return result;
    }

    /**
     * Snapshot native execution progress around a physical delay.  This is
     * intentionally derived from CloudSim task status/finished length and
     * the live native transfer list; it is not a synthetic clock-only flag.
     */
    private Map<String, Object> capturePhysicalProgressSnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<String, Object>();
        double observationTime = simulation == null ? 0.0 : simulation.clock();
        RlNativeResourceBindingManager.observeRuntimeProgress(
                simulationManager == null ? null : simulationManager.getTasksList(), observationTime);
        double remainingTaskWork = 0.0;
        double finishedTaskWork = 0.0;
        Map<String, Integer> statusCounts = new LinkedHashMap<String, Integer>();
        if (simulationManager != null && simulationManager.getTasksList() != null) {
            for (Task task : simulationManager.getTasksList()) {
                if (task == null) continue;
                double length = Math.max(0.0, task.getLength());
                double finished = Math.max(0.0, Math.min(length, task.getFinishedLengthSoFar()));
                remainingTaskWork += Math.max(0.0, length - finished);
                finishedTaskWork += finished;
                String status = task.getStatus() == null ? "UNKNOWN" : task.getStatus().name();
                Integer count = statusCounts.get(status);
                statusCounts.put(status, Integer.valueOf(count == null ? 1 : count.intValue() + 1));
            }
        }
        double remainingNetworkWork = 0.0;
        int activeTransfers = 0;
        if (simulationManager != null && simulationManager.getNetworkModel() != null
                && simulationManager.getNetworkModel().getTransferProgressList() != null) {
            for (FileTransferProgress transfer : new ArrayList<FileTransferProgress>(
                    simulationManager.getNetworkModel().getTransferProgressList())) {
                if (transfer == null || transfer.getRemainingFileSize() <= 0.0) continue;
                activeTransfers += 1;
                remainingNetworkWork += Math.max(0.0, transfer.getRemainingFileSize());
            }
        }
        snapshot.put("remainingTaskWorkload", Double.valueOf(remainingTaskWork));
        snapshot.put("finishedTaskWorkload", Double.valueOf(finishedTaskWork));
        snapshot.put("taskStatusCounts", statusCounts);
        snapshot.put("activeTransferCount", Integer.valueOf(activeTransfers));
        snapshot.put("remainingNetworkWork", Double.valueOf(remainingNetworkWork));
        snapshot.put("cpuConservation", RlNativeResourceBindingManager.runtimeConservationEvidence());
        if (simulationManager != null && simulationManager.getNetworkModel() != null) {
            snapshot.put("bandwidthConservation", simulationManager.getNetworkModel().getBandwidthConservationEvidence());
            snapshot.put("nativeContactInterruptionCount",
                    simulationManager.getNetworkModel().getContactInterruptionEvidence().size());
        }
        return snapshot;
    }

    private synchronized Map<String, Object> advanceWorldInternal(double deltaSec, boolean controlEpoch) {
        if (simulation == null) throw new IllegalStateException("simulation is not ready");
        if (Double.isNaN(deltaSec) || Double.isInfinite(deltaSec) || deltaSec <= 0.0) {
            throw new IllegalArgumentException("deltaSec must be finite and positive");
        }
        Map<String, Object> scalars = bridge.getCurrentDecisionScalars();
        if (numberAsLong(scalars.get("taskId"), -1L) >= 0L) {
            if (!controlEpoch) {
                Map<String, Object> rejected = new LinkedHashMap<String, Object>();
                rejected.put("accepted", false);
                rejected.put("reason", "simulation_waiting_for_decision");
                rejected.put("simulationTimeSec", simulation.clock());
                rejected.put("requestedDeltaSec", deltaSec);
                rejected.put("physicalClockAdvanced", false);
                rejected.put("directClockMutation", false);
                return rejected;
            }
            Map<String, Object> dispatchRequest = new LinkedHashMap<String, Object>();
            dispatchRequest.put("configuration", currentConfiguration.toMap());
            Map<String, Object> task = new LinkedHashMap<String, Object>();
            task.put("taskId", scalars.get("taskId"));
            task.put("sourceId", scalars.get("sourceDeviceId"));
            dispatchRequest.put("task", task);
            Map<String, Object> dispatch = dispatchUnderConfiguration(dispatchRequest);
            if (!Boolean.TRUE.equals(dispatch.get("accepted"))) {
                Map<String, Object> rejected = new LinkedHashMap<String, Object>();
                rejected.put("accepted", false);
                rejected.put("reason", "persistent_configuration_cannot_resolve_pending_task");
                rejected.put("detail", dispatch.get("reason"));
                rejected.put("persistentDispatch", dispatch);
                rejected.put("simulationTimeSec", simulation.clock());
                rejected.put("requestedDeltaSec", deltaSec);
                rejected.put("physicalClockAdvanced", false);
                rejected.put("physicalStateChanged", false);
                rejected.put("directClockMutation", false);
                rejected.put("advanceMechanism", "CloudSim.pauseAt");
                rejected.put("controlEpoch", true);
                rejected.put("controlEpochCompleted", false);
                rejected.put("controlEpochBlocked", true);
                rejected.put("status", "CONTROL_EPOCH_BLOCKED_ON_TASK_DECISION");
                rejected.put("oldConfigurationActiveDuringDelay",
                        currentConfiguration == null ? null : currentConfiguration.configId);
                rejected.put("newConfigurationAppliedAfterDelay", false);
                rejected.put("pausedForConfigurationActivation", false);
                rejected.put("blockingDecisionId", scalars.get("decisionId"));
                rejected.put("blockingTaskId", scalars.get("taskId"));
                rejected.put("blockingSourceDeviceId", scalars.get("sourceDeviceId"));
                rejected.put("pauseTargetCancelled", false);
                return rejected;
            }
        }
        double before = simulation.clock();
        long worldVersionBefore = worldVersion;
        Map<String, Object> physicalProgressBefore = capturePhysicalProgressSnapshot();
        double target = before + deltaSec;
        boolean scheduled = simulation.pause(target);
        boolean blockedByUnmaterializedTask = false;
        long deadline = System.currentTimeMillis() + 30000L;
        while (scheduled && !simulation.isPaused() && !bridge.isFinished() && System.currentTimeMillis() < deadline) {
            if (controlEpoch) {
                Map<String, Object> pending = bridge.getCurrentDecisionScalars();
                if (numberAsLong(pending.get("taskId"), -1L) >= 0L) {
                    blockedByUnmaterializedTask = true;
                    break;
                }
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        double after = simulation.clock();
        Map<String, Object> physicalProgressAfter = capturePhysicalProgressSnapshot();
        boolean physicalStateChanged = !physicalProgressBefore.equals(physicalProgressAfter);
        String physicalAdvanceReceiptId = null;
        if (after > before) {
            worldVersion += 1L;
            if (currentConfiguration != null) currentConfiguration.worldVersion = worldVersion;
            physicalAdvanceReceiptId = sessionId + "-advance-" + UUID.randomUUID().toString();
            lastPhysicalAdvanceReceiptId = physicalAdvanceReceiptId;
            lastPhysicalAdvanceWorldVersion = worldVersion;
            refreshControlStateIdentity();
        }
        List<Long> uncoveredTaskIds = new ArrayList<Long>();
        if (simulationManager != null && simulationManager.getTasksList() != null) {
            for (Task task : simulationManager.getTasksList()) {
                if (task != null && task.getTime() > before && task.getTime() <= after) {
                    uncoveredTaskIds.add(task.getId());
                }
            }
        }
        if (controlEpoch && blockedByUnmaterializedTask) {
            Map<String, Object> pending = bridge.getCurrentDecisionScalars();
            cancelScheduledPause();
            Map<String, Object> blocked = new LinkedHashMap<String, Object>();
            blocked.put("accepted", false);
            blocked.put("status", "CONTROL_EPOCH_BLOCKED_ON_TASK_DECISION");
            blocked.put("reason", "persistent_configuration_cannot_resolve_task_during_control_epoch");
            blocked.put("detail", "old persistent configuration did not resolve a newly pending task");
            blocked.put("requestedDeltaSec", deltaSec);
            blocked.put("simulationTimeBeforeSec", before);
            blocked.put("simulationTimeSec", after);
            blocked.put("actualDeltaSec", Math.max(0.0, after - before));
            blocked.put("worldVersionBefore", worldVersionBefore);
            blocked.put("worldVersion", worldVersion);
            blocked.put("physicalAdvanceReceiptId", physicalAdvanceReceiptId);
            blocked.put("targetSimulationTimeSec", target);
            blocked.put("physicalClockAdvanced", after > before);
            blocked.put("physicalStateChanged", physicalStateChanged);
            blocked.put("physicalProgressBefore", physicalProgressBefore);
            blocked.put("physicalProgressAfter", physicalProgressAfter);
            blocked.put("directClockMutation", false);
            blocked.put("advanceMechanism", "CloudSim.pauseAt");
            blocked.put("controlEpoch", true);
            blocked.put("controlEpochCompleted", false);
            blocked.put("controlEpochBlocked", true);
            blocked.put("pausedForConfigurationActivation", false);
            blocked.put("oldConfigurationActiveDuringDelay", currentConfiguration == null ? null : currentConfiguration.configId);
            blocked.put("newConfigurationAppliedAfterDelay", false);
            blocked.put("blockingDecisionId", pending.get("decisionId"));
            blocked.put("blockingTaskId", pending.get("taskId"));
            blocked.put("blockingSourceDeviceId", pending.get("sourceDeviceId"));
            blocked.put("persistentDispatch", bridge.getDecisionPlaneStats().get("lastPersistentDispatch"));
            blocked.put("uncoveredTaskCountDuringDelta", uncoveredTaskIds.size());
            blocked.put("uncoveredTaskIdsDuringDelta", uncoveredTaskIds);
            blocked.put("pauseTargetCancelled", true);
            blocked.put("containsFutureStochasticState", false);
            return blocked;
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        boolean pausedForActivation = controlEpoch && scheduled && after > before && simulation.isPaused();
        result.put("accepted", scheduled && after > before);
        result.put("status", pausedForActivation
                ? "ADVANCED_AND_PAUSED_FOR_CONFIGURATION"
                : (simulation.isPaused() ? "ADVANCED_AND_RESUMED" : "ADVANCE_TIMEOUT"));
        result.put("requestedDeltaSec", deltaSec);
        result.put("simulationTimeBeforeSec", before);
        result.put("simulationTimeSec", after);
        result.put("actualDeltaSec", Math.max(0.0, after - before));
        result.put("worldVersionBefore", worldVersionBefore);
        result.put("worldVersion", worldVersion);
        result.put("physicalAdvanceReceiptId", physicalAdvanceReceiptId);
        result.put("targetSimulationTimeSec", target);
        result.put("physicalClockAdvanced", after > before);
        result.put("physicalStateChanged", physicalStateChanged);
        result.put("physicalProgressBefore", physicalProgressBefore);
        result.put("physicalProgressAfter", physicalProgressAfter);
        result.put("directClockMutation", false);
        result.put("advanceMechanism", "CloudSim.pauseAt");
        result.put("controlEpoch", controlEpoch);
        result.put("resumeAfterReceipt", !controlEpoch && simulation.isPaused());
        result.put("oldConfigurationActiveDuringDelay", currentConfiguration == null ? null : currentConfiguration.configId);
        result.put("newConfigurationAppliedAfterDelay", false);
        result.put("pausedForConfigurationActivation", pausedForActivation);
        result.put("controlEpochCompleted", controlEpoch && scheduled && after > before && simulation.isPaused());
        result.put("uncoveredTaskCountDuringDelta", uncoveredTaskIds.size());
        result.put("uncoveredTaskIdsDuringDelta", uncoveredTaskIds);
        result.put("containsFutureStochasticState", false);
        result.put("validationRequiredBeforeConfigurationActivation", true);
        if (pausedForActivation) {
            controlEpochPausedForActivation = true;
        } else if (simulation.isPaused()) {
            simulation.resume();
        }
        return result;
    }

    private void recordInterventionAdvanceStart(String interventionId, double deltaSec,
            boolean controlEpoch, boolean plannerCompleted) {
        if (interventionId == null || interventionId.trim().isEmpty()) return;
        if (plannerCompleted) {
            Map<String, Object> planner = new LinkedHashMap<String, Object>();
            planner.put("interventionId", interventionId);
            planner.put("simulationTimeSec", simulation == null ? 0.0 : simulation.clock());
            planner.put("worldVersion", worldVersion);
            planner.put("plannerCompleted", true);
            recordProtocolEvent("planner_completed", planner);
        }
        Map<String, Object> start = new LinkedHashMap<String, Object>();
        start.put("interventionId", interventionId);
        start.put("requestedDeltaSec", deltaSec);
        start.put("controlEpoch", controlEpoch);
        start.put("simulationTimeSec", simulation == null ? 0.0 : simulation.clock());
        start.put("worldVersion", worldVersion);
        start.put("oldConfigurationActiveDuringDelay",
                currentConfiguration == null ? null : currentConfiguration.configId);
        start.put("newConfigurationAppliedAfterDelay", false);
        recordProtocolEvent("physical_advance_started", start);
    }

    private void annotateAndRecordInterventionAdvance(Map<String, Object> result, String interventionId) {
        if (result == null || interventionId == null || interventionId.trim().isEmpty()) return;
        result.put("interventionId", interventionId);
        recordProtocolEvent(Boolean.TRUE.equals(result.get("accepted"))
                ? "physical_advance_accepted" : "physical_advance_rejected", result);
    }

    /** Cancel the pause-at target without mutating the CloudSim clock. */
    private void cancelScheduledPause() {
        if (simulation == null || simulation.isPaused()) return;
        simulation.pause(simulation.clock());
        simulation.resume();
    }

    public synchronized Map<String, Object> resumeControlEpoch() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("receiptType", "control_epoch_resume");
        result.put("controlEpoch", true);
        result.put("simulationTimeSec", simulation == null ? 0.0 : simulation.clock());
        result.put("oldConfigurationActiveDuringDelay", currentConfiguration == null ? null : currentConfiguration.configId);
        if (!controlEpochPausedForActivation) {
            result.put("accepted", false);
            result.put("reason", "no_paused_control_epoch");
            result.put("physicalClockAdvanced", false);
            return result;
        }
        boolean wasPaused = simulation != null && simulation.isPaused();
        if (wasPaused) simulation.resume();
        controlEpochPausedForActivation = false;
        result.put("accepted", wasPaused);
        result.put("reason", wasPaused ? "old_configuration_resumed" : "simulation_not_paused");
        result.put("resumed", wasPaused);
        result.put("physicalClockAdvanced", false);
        result.put("newConfigurationAppliedAfterDelay", false);
        return result;
    }

    public synchronized Map<String, Object> applyConfiguration(Map<String, Object> request) {
        PersistentExecutionConfiguration candidate = PersistentExecutionConfiguration.fromRequest(request);
        Map<String, Object> receipt = new LinkedHashMap<String, Object>();
        double now = simulation == null ? 0.0 : simulation.clock();
        boolean compatibilityMode = request != null
                && (Boolean.TRUE.equals(request.get("compatibilityMode"))
                    || Boolean.TRUE.equals(request.get("compatibility_mode"))
                    || "COMPATIBILITY_FULL_APPLY".equals(request.get("operationClass")));
        boolean bootstrap = currentConfiguration == null;
        boolean idempotent = currentConfiguration != null
                && currentConfiguration.configId.equals(candidate.configId)
                && currentConfiguration.version == candidate.version
                && currentConfiguration.toMap().equals(candidate.toMap());
        String operationClass = bootstrap ? "BOOTSTRAP_CONFIGURATION"
                : (idempotent ? "IDEMPOTENT_REAPPLY"
                : "COMPATIBILITY_FULL_APPLY");
        if (!bootstrap && !idempotent && strictPhysicalClaims) {
            receipt.put("receiptType", "configuration_apply_rejected");
            receipt.put("accepted", false);
            receipt.put("operationClass", operationClass);
            receipt.put("rejectionReason", "material_update_requires_configuration_patch");
            receipt.put("configurationChanged", false);
            receipt.put("publicationEligibleForReconfigurationClaim", false);
            receipt.put("simulationTimeSec", now);
            receipt.put("worldVersion", worldVersion);
            receipt.put("configurationVersion", currentConfiguration.version);
            recordProtocolEvent("configuration_apply_rejected", receipt);
            return receipt;
        }
        Map<String, Object> validation = validateConfiguration(request);
        if (!Boolean.TRUE.equals(validation.get("accepted"))) {
            validation.put("operationClass", operationClass);
            validation.put("configurationChanged", false);
            validation.put("publicationEligibleForReconfigurationClaim", false);
            return validation;
        }
        if (!bootstrap && !idempotent && !compatibilityMode) {
            receipt.put("receiptType", "configuration_apply_rejected");
            receipt.put("accepted", false);
            receipt.put("operationClass", operationClass);
            receipt.put("rejectionReason", "material_update_requires_configuration_patch");
            receipt.put("configurationChanged", false);
            receipt.put("publicationEligibleForReconfigurationClaim", false);
            recordProtocolEvent("configuration_apply_rejected", receipt);
            return receipt;
        }
        candidate.worldVersion = worldVersion;
        if (!Double.isFinite(candidate.creationSimTimeSec)) candidate.creationSimTimeSec = now;
        if (!Double.isFinite(candidate.lastUpdateSimTimeSec)) candidate.lastUpdateSimTimeSec = now;
        if (Double.isFinite(candidate.configuredLifetimeSec)) candidate.expiresAtSimTimeSec = now + candidate.configuredLifetimeSec;
        Map<String, Object> before = currentConfiguration == null ? new LinkedHashMap<String, Object>() : currentConfiguration.toMap();
        currentConfiguration = candidate;
        if (!idempotent || !Double.isFinite(configurationAppliedAtSec)) {
            configurationAppliedAtSec = now;
        }
        bridge.setPersistentConfiguration(candidate);
        boolean resumedAfterControlEpoch = controlEpochPausedForActivation && simulation != null && simulation.isPaused();
        if (resumedAfterControlEpoch) {
            simulation.resume();
            controlEpochPausedForActivation = false;
        }
        configurationReceiptSequence += 1L;
        receipt.put("receiptType", "configuration_apply");
        receipt.put("accepted", true);
        receipt.put("idempotent", idempotent);
        receipt.put("operationClass", operationClass);
        receipt.put("receiptId", configurationReceiptSequence);
        receipt.put("configId", candidate.configId);
        receipt.put("version", candidate.version);
        receipt.put("simulationTimeSec", simulation == null ? 0.0 : simulation.clock());
        receipt.put("worldVersion", worldVersion);
        receipt.put("changed", !idempotent);
        receipt.put("configurationChanged", !idempotent);
        receipt.put("nativeExecutionChanged", false);
        receipt.put("nativeResourceActuationObserved", false);
        receipt.put("futureDispatchRuleChanged", !candidate.reusableRules.isEmpty());
        receipt.put("publicationEligibleForReconfigurationClaim", false);
        receipt.put("replanCountContribution", 0);
        receipt.put("previousConfiguration", before);
        receipt.put("configuration", candidate.toMap());
        receipt.put("reusableRuleCount", candidate.reusableRules.size());
        receipt.put("dispatchMode", "persistent_reusable_rule");
        receipt.put("resumedAfterControlEpoch", resumedAfterControlEpoch);
        receipt.put("containsFutureStochasticState", false);
        recordProtocolEvent("configuration_apply", receipt);
        refreshControlStateIdentity();
        return receipt;
    }

    /** Canonical intervention endpoint: apply one selective ΔΠ_k. */
    public synchronized Map<String, Object> applyConfigurationPatch(Map<String, Object> request) {
        ConfigurationPatch patch = ConfigurationPatch.fromRequest(request);
        refreshControlStateIdentity();
        ValidationReceipt receipt = null;
        if (strictPhysicalClaims && patch.hasMaterialChanges()) {
            receipt = verifyValidationReceipt(patch);
            if (receipt != null) {
                patch.attachServerValidationReceipt(receipt);
            }
        }
        double now = simulation == null ? 0.0 : simulation.clock();
        ReconfigurationExecutor executor = new ReconfigurationExecutor(simulationManager, now, worldVersion);
        PatchApplicationResult result = executor.apply(currentConfiguration, patch, strictPhysicalClaims);
        result.validationReceiptId = patch.validationReceiptId;
        result.validatedAfterPhysicalAdvance = receipt != null && receipt.validatedAfterPhysicalAdvance;
        result.physicalAdvanceReceiptId = patch.physicalAdvanceReceiptId;
        if (result.accepted && result.changed) {
            currentConfiguration = PersistentExecutionConfiguration.fromRequest(result.afterConfiguration);
            bridge.setPersistentConfiguration(currentConfiguration);
            configurationAppliedAtSec = now;
            result.afterConfiguration = currentConfiguration.toMap();
            refreshControlStateIdentity();
            if (receipt != null && receipt.singleUse) receipt.consumed = true;
        }
        result.evidenceId = sessionId + "-intervention-" + (++interventionEvidenceSequence);
        Map<String, Object> evidence = jsonSafeMap(result.toMap());
        boolean resumedAfterControlEpoch = controlEpochPausedForActivation && simulation != null && simulation.isPaused();
        if (resumedAfterControlEpoch) {
            simulation.resume();
            controlEpochPausedForActivation = false;
        }
        evidence.put("resumedAfterControlEpoch", resumedAfterControlEpoch);
        evidence.put("configurationId", currentConfiguration == null ? null : currentConfiguration.configId);
        evidence.put("configurationVersion", currentConfiguration == null ? 0L : currentConfiguration.version);
        evidence.put("physicalTaskStateSource", "CloudSim Cloudlet status + native transfer progression");
        evidence.put("migrationCapability", ReconfigurationExecutor.SUPPORTS_TASK_TARGET_MIGRATION);
        evidence.put("routeActuationCapability", ReconfigurationExecutor.SUPPORTS_ROUTE_ACTUATION);
        evidence.put("operationClass", result.operationClass);
        evidence.put("configurationChanged", result.configurationChanged);
        evidence.put("nativeExecutionChanged", result.nativeExecutionChanged);
        evidence.put("nativeResourceActuationObserved", result.nativeResourceActuationObserved);
        evidence.put("futureDispatchRuleChanged", result.futureDispatchRuleChanged);
        evidence.put("ruleEffectiveAtRuntime", result.ruleEffectiveAtRuntime);
        evidence.put("eligibleForConfigurationPatchClaim", result.accepted && result.configurationChanged);
        evidence.put("eligibleForSelectiveConfigurationChangeClaim",
                result.accepted && result.changed && result.scopeInvariantSatisfied);
        evidence.put("eligibleForImmediateNativeResourceActuationClaim",
                result.nativeResourceActuationObserved && !result.nativeAppliedChanges.isEmpty());
        evidence.put("eligibleForPersistentRuleRuntimeEffectClaim", result.ruleEffectiveAtRuntime);
        interventionEvidence.add(evidence);
        while (interventionEvidence.size() > 256) interventionEvidence.remove(0);
        recordProtocolEvent(result.accepted ? "configuration_patch_applied" : "configuration_patch_rejected", evidence);
        return evidence;
    }

    public synchronized Map<String, Object> getInterventionEvidence() {
        refreshControlStateIdentity();
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("receiptType", "intervention_evidence");
        result.put("simulationTimeSec", simulation == null ? 0.0 : simulation.clock());
        result.put("worldVersion", worldVersion);
        result.put("controlStateRevision", controlStateRevision);
        result.put("worldIdentityDigest", currentControlIdentityDigest());
        result.put("configuration", currentConfiguration == null ? null : currentConfiguration.toMap());
        result.put("evidence", new ArrayList<Map<String, Object>>(interventionEvidence));
        result.put("evidenceCount", interventionEvidence.size());
        if (simulationManager != null && simulationManager.getNetworkModel() != null) {
            result.put("nativeTransferEvidence", simulationManager.getNetworkModel().getTransferEvidence());
            result.put("nativeContactInterruptionEvidence",
                    simulationManager.getNetworkModel().getContactInterruptionEvidence());
            result.put("bandwidthConservationEvidence",
                    simulationManager.getNetworkModel().getBandwidthConservationEvidence());
        }
        result.put("persistentRuleRuntimeEffects", new ArrayList<Map<String, Object>>(persistentRuleRuntimeEffects));
        result.put("containsFutureStochasticState", false);
        return jsonSafeMap(result);
    }

    public synchronized Map<String, Object> getProtocolEvents() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("receiptType", "protocol_events");
        result.put("simulationTimeSec", simulation == null ? 0.0 : simulation.clock());
        result.put("worldVersion", worldVersion);
        result.put("events", new ArrayList<Map<String, Object>>(protocolEvents));
        result.put("eventCount", protocolEvents.size());
        result.put("containsFutureStochasticState", false);
        return jsonSafeMap(result);
    }

    public synchronized Map<String, Object> getDynamicValidationReport() {
        refreshControlStateIdentity();
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("receiptType", "dynamic_validation_report");
        result.put("simulationTimeSec", simulation == null ? 0.0 : simulation.clock());
        result.put("worldVersion", worldVersion);
        result.put("controlStateRevision", controlStateRevision);
        result.put("worldIdentityDigest", currentControlIdentityDigest());
        result.put("strictPhysicalClaims", strictPhysicalClaims);
        result.put("configurationVersion", currentConfiguration == null ? 0L : currentConfiguration.version);
        result.put("configurationExpired", currentConfiguration != null && simulation != null && currentConfiguration.isExpired(simulation.clock()));
        result.put("supportsTaskTargetMigration", ReconfigurationExecutor.SUPPORTS_TASK_TARGET_MIGRATION);
        result.put("supportsRouteActuation", ReconfigurationExecutor.SUPPORTS_ROUTE_ACTUATION);
        result.put("supportsDynamicPriorityActuation", ReconfigurationExecutor.SUPPORTS_DYNAMIC_PRIORITY_ACTUATION);
        result.put("interventionEvidenceCount", interventionEvidence.size());
        Map<String, Object> cpuConservation = RlNativeResourceBindingManager.runtimeConservationEvidence();
        Map<String, Object> bandwidthConservation = simulationManager == null || simulationManager.getNetworkModel() == null
                ? new LinkedHashMap<String, Object>()
                : simulationManager.getNetworkModel().getBandwidthConservationEvidence();
        List<Map<String, Object>> transferEvidence = simulationManager == null || simulationManager.getNetworkModel() == null
                ? new ArrayList<Map<String, Object>>()
                : simulationManager.getNetworkModel().getTransferEvidence();
        List<Map<String, Object>> contactEvidence = simulationManager == null || simulationManager.getNetworkModel() == null
                ? new ArrayList<Map<String, Object>>()
                : simulationManager.getNetworkModel().getContactInterruptionEvidence();
        boolean transferEvidenceConsistent = true;
        Set<Long> transferIds = new HashSet<Long>();
        for (Map<String, Object> item : transferEvidence) {
            long transferId = numberAsLong(item.get("transferId"), -1L);
            double total = numberAsDouble(item.get("totalKbits"), -1.0);
            double moved = numberAsDouble(item.get("transferredKbits"), -1.0);
            double remaining = numberAsDouble(item.get("remainingKbits"), -1.0);
            double wasted = numberAsDouble(item.get("wastedKbits"), 0.0);
            double failed = numberAsDouble(item.get("failedKbits"), 0.0);
            double retried = numberAsDouble(item.get("retriedKbits"), 0.0);
            boolean bytesValid = total >= 0.0 && moved >= 0.0 && remaining >= 0.0
                    && wasted >= 0.0 && failed >= 0.0 && retried >= 0.0
                    && Math.abs(moved + remaining + wasted + failed + retried - total) <= 1.0e-6;
            boolean unique = transferId >= 0L && transferIds.add(Long.valueOf(transferId));
            transferEvidenceConsistent = transferEvidenceConsistent && bytesValid && unique;
        }
        boolean contactEvidenceConsistent = true;
        Set<Long> contactTransferIds = new HashSet<Long>();
        for (Map<String, Object> item : contactEvidence) {
            long transferId = numberAsLong(item.get("transferId"), -1L);
            double total = numberAsDouble(item.get("totalKbits"), -1.0);
            double moved = numberAsDouble(item.get("transferredKbits"), -1.0);
            double remaining = numberAsDouble(item.get("remainingKbits"), -1.0);
            boolean qualifying = Boolean.TRUE.equals(item.get("qualifyingMidTransferInterruption"));
            double wasted = numberAsDouble(item.get("wastedKbits"), 0.0);
            double failed = numberAsDouble(item.get("failedKbits"), 0.0);
            double retried = numberAsDouble(item.get("retriedKbits"), 0.0);
            boolean bytesValid = total >= 0.0 && moved > 0.0 && remaining > 0.0
                    && wasted >= 0.0 && failed >= 0.0 && retried >= 0.0
                    && Math.abs(moved + remaining + wasted + failed + retried - total) <= 1.0e-6;
            boolean unique = transferId >= 0L && contactTransferIds.add(Long.valueOf(transferId));
            contactEvidenceConsistent = contactEvidenceConsistent && qualifying && bytesValid && unique;
        }
        boolean cpuObserved = Boolean.TRUE.equals(cpuConservation.get("observed"));
        boolean cpuConserved = Boolean.TRUE.equals(cpuConservation.get("conservationSatisfied"));
        boolean bandwidthObserved = Boolean.TRUE.equals(bandwidthConservation.get("observed"));
        boolean bandwidthConserved = Boolean.TRUE.equals(bandwidthConservation.get("conservationSatisfied"));
        boolean strictRuntimeValidation = !strictPhysicalClaims
                || (cpuObserved && cpuConserved && bandwidthObserved && bandwidthConserved
                        && transferEvidenceConsistent && contactEvidenceConsistent);
        result.put("cpuConservation", cpuConservation);
        result.put("bandwidthConservation", bandwidthConservation);
        result.put("nativeTransferEvidence", transferEvidence);
        result.put("nativeTransferEvidenceConsistent", transferEvidenceConsistent);
        result.put("bandwidthClaimScope", "shared_lan_domain_and_global_wan");
        result.put("perLinkBandwidthAllocationSupported", false);
        result.put("contactInterruptionEvidence", contactEvidence);
        result.put("contactEvidenceAvailable", !contactEvidence.isEmpty());
        result.put("contactEvidenceConsistency", contactEvidence.isEmpty()
                ? "UNKNOWN" : (contactEvidenceConsistent ? "CONSISTENT" : "INCONSISTENT"));
        result.put("nativeContactInterruptionObserved", !contactEvidence.isEmpty());
        result.put("nativeContactInterruptionEvidenceConsistent", contactEvidenceConsistent);
        result.put("strictRuntimeValidationPassed", strictRuntimeValidation);
        result.put("strictRuntimeValidationFailureReason", strictRuntimeValidation ? null
                : "missing_or_invalid_runtime_physics_evidence");
        boolean observedSelectiveReconfiguration = false;
        boolean observedNativeResourceActuation = false;
        for (Map<String, Object> evidence : interventionEvidence) {
            if (Boolean.TRUE.equals(evidence.get("accepted")) && Boolean.TRUE.equals(evidence.get("changed"))
                    && Boolean.TRUE.equals(evidence.get("scopeInvariantSatisfied"))
                    && evidence.get("actualChangedEntities") instanceof List
                    && !((List<?>) evidence.get("actualChangedEntities")).isEmpty()) {
                observedSelectiveReconfiguration = true;
            }
            Object volume = evidence.get("realizedReconfigurationVolume");
            if (volume instanceof Map && ((Map<?, ?>) volume).get("nativeBindingSnapshots") instanceof Map
                    && !((Map<?, ?>) ((Map<?, ?>) volume).get("nativeBindingSnapshots")).isEmpty()) {
                observedNativeResourceActuation = true;
            }
        }
        result.put("runtimeEvidenceCount", interventionEvidence.size());
        result.put("observedAtRuntime", !interventionEvidence.isEmpty());
        result.put("eligibleForSelectiveReconfigurationClaim", observedSelectiveReconfiguration);
        result.put("eligibleForNativeResourceActuationClaim", observedNativeResourceActuation);
        boolean observedConfigurationChange = false;
        boolean observedImmediateNativeResourceActuation = false;
        for (Map<String, Object> evidence : interventionEvidence) {
            if (Boolean.TRUE.equals(evidence.get("accepted")) && Boolean.TRUE.equals(evidence.get("configurationChanged"))) {
                observedConfigurationChange = true;
            }
            if (Boolean.TRUE.equals(evidence.get("nativeResourceActuationObserved"))) {
                observedImmediateNativeResourceActuation = true;
            }
        }
        result.put("eligibleForConfigurationPatchClaim", observedConfigurationChange);
        result.put("eligibleForSelectiveConfigurationChangeClaim", observedSelectiveReconfiguration);
        result.put("eligibleForImmediateNativeResourceActuationClaim", observedImmediateNativeResourceActuation);
        result.put("eligibleForPersistentRuleRuntimeEffectClaim", !persistentRuleRuntimeEffects.isEmpty());
        result.put("persistentRuleRuntimeEffects", new ArrayList<Map<String, Object>>(persistentRuleRuntimeEffects));
        result.put("eligibleForCpuConservationClaim", cpuObserved && cpuConserved);
        result.put("eligibleForBandwidthConservationClaim", bandwidthObserved && bandwidthConserved);
        result.put("eligibleForMidTransferContactInterruptionClaim",
                !contactEvidence.isEmpty() && contactEvidenceConsistent);
        result.put("eligibleForTaskTargetMigrationClaim", false);
        result.put("eligibleForRouteActuationClaim", false);
        result.put("eligibleForDynamicPriorityClaim", false);
        result.put("lastInterventionEvidence", interventionEvidence.isEmpty() ? null : interventionEvidence.get(interventionEvidence.size() - 1));
        result.put("validationSource", "satedgesim_native_runtime");
        result.put("containsFutureStochasticState", false);
        return jsonSafeMap(result);
    }

    private void recordProtocolEvent(String type, Map<String, Object> payload) {
        Map<String, Object> event = new LinkedHashMap<String, Object>();
        event.put("eventSequence", ++protocolEventSequence);
        event.put("eventType", type);
        event.put("simulationTimeSec", simulation == null ? 0.0 : simulation.clock());
        event.put("worldVersion", worldVersion);
        event.put("configurationVersion", currentConfiguration == null ? 0L : currentConfiguration.version);
        if (payload != null && payload.get("interventionId") != null) {
            event.put("interventionId", payload.get("interventionId"));
        }
        event.put("payload", payload);
        protocolEvents.add(event);
        while (protocolEvents.size() > 512) protocolEvents.remove(0);
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
        if (simulation != null && currentConfiguration.isExpired(simulation.clock())) {
            receipt.put("accepted", false);
            receipt.put("reason", "configuration_expired");
            receipt.put("simTimeExpiryAuthoritative", true);
            return receipt;
        }
        Map<String, Object> taskContext = request == null || !(request.get("task") instanceof Map)
                ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>((Map<String, Object>) request.get("task"));
        Map<String, Object> scalars = bridge.getCurrentDecisionScalars();
        if (!taskContext.containsKey("taskId")) taskContext.put("taskId", scalars.get("taskId"));
        if (!taskContext.containsKey("sourceId")) taskContext.put("sourceId", scalars.get("sourceDeviceId"));
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
        receipt.put("ruleEffectiveAtRuntime", execution.accepted);
        receipt.put("nativeResourceActuationObserved", execution.accepted
                && execution.resourceProfile != null && execution.resourceProfile.nativeSchedulerBound());
        boolean pendingNativeBinding = currentConfiguration.resourceAllocations.containsKey(String.valueOf(taskContext.get("taskId")));
        receipt.put("pendingNativeBindingMaterialized", pendingNativeBinding
                && execution.accepted && execution.nativeBindingApplied);
        if (execution.accepted) {
            receipt.put("runtimeEffectConfigurationVersion", currentConfiguration.version);
            persistentRuleRuntimeEffects.add(new LinkedHashMap<String, Object>(receipt));
            while (persistentRuleRuntimeEffects.size() > 256) persistentRuleRuntimeEffects.remove(0);
            for (Map<String, Object> evidence : interventionEvidence) {
                if (numberAsLong(evidence.get("configurationVersion"), -1L) == currentConfiguration.version
                        && Boolean.TRUE.equals(evidence.get("futureDispatchRuleChanged"))) {
                    evidence.put("ruleEffectiveAtRuntime", true);
                    evidence.put("eligibleForPersistentRuleRuntimeEffectClaim", true);
                    evidence.put("runtimeRuleEffect", receipt);
                }
                if (numberAsLong(evidence.get("configurationVersion"), -1L) == currentConfiguration.version
                        && deferredContainsTask(evidence.get("deferredChanges"), String.valueOf(taskContext.get("taskId")))) {
                    evidence.put("pendingNativeBinding", false);
                    evidence.put("deferredChangeMaterializedAtRuntime", execution.accepted && execution.nativeBindingApplied);
                    evidence.put("materializedNativeEvidence", receipt);
                }
            }
        }
        return receipt;
    }

    @SuppressWarnings("unchecked")
    private static boolean deferredContainsTask(Object raw, String taskId) {
        if (!(raw instanceof Map)) return false;
        Object resources = ((Map<?, ?>) raw).get("resourceChanges");
        return resources instanceof Map && ((Map<String, Object>) resources).containsKey(taskId);
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

    private void validateScalarAllocations(Map<String, Object> bindings, String dimension, List<String> reasons) {
        if (bindings == null) return;
        for (Object raw : bindings.values()) {
            if (!(raw instanceof Number)) continue;
            double value = ((Number) raw).doubleValue();
            if (Double.isNaN(value) || Double.isInfinite(value) || value <= 0.0 || value > 1.0) {
                reasons.add("invalid_" + dimension + "_allocation");
            }
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
        Map<String, Object> decisionPlaneStats = bridge.getDecisionPlaneStats();
        CheapMonitorState monitor = new CheapMonitorState();
        monitor.sessionId = sessionId;
        monitor.status = String.valueOf(scalars.get("status"));
        double now = simulation == null ? 0.0 : simulation.clock();
        monitor.simulationTimeSec = now;
        monitor.worldVersion = worldVersion;
        monitor.currentDecisionId = numberAsLong(scalars.get("decisionId"), -1L);
        monitor.currentTaskId = numberAsLong(scalars.get("taskId"), -1L);
        monitor.sourceDeviceId = numberAsInt(scalars.get("sourceDeviceId"), -1);
        monitor.currentConfigId = currentConfiguration == null ? null : currentConfiguration.configId;
        monitor.currentConfigVersion = currentConfiguration == null ? 0L : currentConfiguration.version;
        if (currentConfiguration != null) {
            double age = currentConfiguration.ageAt(now);
            monitor.configurationAgeSec = Double.isFinite(age) ? Double.valueOf(age) : null;
            monitor.cachedState.put("configurationExpired", currentConfiguration.isExpired(now));
            monitor.cachedState.put("configurationCreationSimTimeSec",
                    Double.isFinite(currentConfiguration.creationSimTimeSec) ? currentConfiguration.creationSimTimeSec : null);
            monitor.cachedState.put("configurationLastUpdateSimTimeSec",
                    Double.isFinite(currentConfiguration.lastUpdateSimTimeSec) ? currentConfiguration.lastUpdateSimTimeSec : null);
            monitor.cachedState.put("configurationExpiresAtSimTimeSec",
                    Double.isFinite(currentConfiguration.expiresAtSimTimeSec) ? currentConfiguration.expiresAtSimTimeSec : null);
            monitor.cachedState.put("worldVersion", worldVersion);
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
        long candidateEvaluationDelta = numberAsLong(
                decisionPlaneStats.get("candidateEvaluationsDeltaSinceDecisionContext"), 0L);
        long fullStateBuilderDelta = numberAsLong(
                decisionPlaneStats.get("fullStateBuilderDeltaSinceDecisionContext"), 0L);
        boolean legacyFullStateAccessObserved = Boolean.TRUE.equals(
                decisionPlaneStats.get("legacyFullStateAccessObserved"));
        boolean legacyFullStateMaterialized = Boolean.TRUE.equals(
                decisionPlaneStats.get("legacyFullStateMaterialized"));
        monitor.instrumentation.put("candidateEvaluations", candidateEvaluationDelta);
        monitor.instrumentation.put("candidateEvaluationsDeltaSinceDecisionContext", candidateEvaluationDelta);
        monitor.instrumentation.put("fullStateBuilderInvoked", fullStateBuilderDelta > 0L);
        monitor.instrumentation.put("fullStateBuilderDeltaSinceDecisionContext", fullStateBuilderDelta);
        monitor.instrumentation.put("legacyFullStateAccessObserved", legacyFullStateAccessObserved);
        monitor.instrumentation.put("legacyFullStateMaterialized", legacyFullStateMaterialized);
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
        Map<String, Object> result = monitor.toMap();
        result.put("legacyFullStateAccessObserved", legacyFullStateAccessObserved);
        result.put("legacyFullStateMaterialized", legacyFullStateMaterialized);
        result.put("fullStateBuilderDeltaSinceDecisionContext", fullStateBuilderDelta);
        result.put("candidateEvaluationsDeltaSinceDecisionContext", candidateEvaluationDelta);
        result.put("publicationEligibleForCheapMonitor",
                !legacyFullStateAccessObserved && !legacyFullStateMaterialized
                        && fullStateBuilderDelta == 0L && candidateEvaluationDelta == 0L);
        return result;
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
     * Unified planner-state endpoint.  POST requests acquire only the
     * identity-filtered and budget-retained candidates from the pending
     * decision context.  GET remains an explicit legacy full-state path.
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
        List<String> unsupportedBudget = unsupportedAcquisitionBudgetDimensions(budget);
        RlState state;
        if (!compatibilityFull && strictPhysicalClaims && !unsupportedBudget.isEmpty()) {
            state = bridge.rejectScopedPlannerAcquisition(scope, budget, unsupportedBudget,
                    "unsupported_acquisition_budget_dimension");
        } else {
            state = compatibilityFull
                    ? bridge.getState()
                    : bridge.buildScopedPlannerState(scope, budgetLimit, strictPhysicalClaims);
        }
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
        Map<String, Object> appliedBudget = new LinkedHashMap<String, Object>();
        if (budgetLimit >= 0) appliedBudget.put("max_candidate_count", budgetLimit);
        response.put("appliedBudget", appliedBudget);
        response.put("unsupportedAcquisitionBudgetDimensions", unsupportedBudget);
        response.put("fidelityHint", fidelityHint);
        response.put("candidateCountBeforeRestriction", sourceCount);
        response.put("candidateCountAfterRestriction", candidates.size());
        response.put("scopeRestrictionApplied", !scope.isEmpty());
        response.put("budgetRestrictionApplied", budgetLimit >= 0);
        response.put("budgetAppliedDuringAcquisition", !compatibilityFull && budgetLimit >= 0);
        response.put("postFilterOnly", false);
        response.put("fullStateEquivalent", compatibilityFull && scope.isEmpty() && budget.isEmpty());
        Map<String, Object> acquisition = bridge.getCurrentAcquisitionEvidence();
        if (acquisition.isEmpty()) {
            acquisition.put("decisionId", state.decisionId);
            acquisition.put("mode", compatibilityFull ? "legacy_full_state_compatibility" : "native_scoped_candidate_acquisition");
            acquisition.put("legacyFullStateMaterialized", compatibilityFull);
            acquisition.put("fullStateBuilderDeltaSinceDecisionContext", compatibilityFull ? 1L : 0L);
            acquisition.put("containsFutureStochasticState", false);
        }
        acquisition.put("requestedScopeAppliedAt", compatibilityFull ? "legacy_full_state" : "pre_evaluation_identity_filter");
        acquisition.put("responseCandidateCount", candidates.size());
        response.put("acquisition", acquisition);
        response.put("readEntities", acquisition.get("readEntityKinds"));
        response.put("publicationEligibleForScopedPlannerState", !compatibilityFull
                && Boolean.TRUE.equals(acquisition.get("scopeBudgetCausalityProven"))
                && unsupportedBudget.isEmpty()
                && !Boolean.TRUE.equals(acquisition.get("legacyFullStateAccessObserved"))
                && numberAsLong(acquisition.get("candidateEvaluatedCount"), 0L) > 0L
                && state.status != null && state.status.startsWith("WAITING"));
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
        result.put("cpuConservation", RlNativeResourceBindingManager.runtimeConservationEvidence());
        if (simulationManager != null && simulationManager.getNetworkModel() != null) {
            result.put("bandwidthConservation", simulationManager.getNetworkModel().getBandwidthConservationEvidence());
        result.put("nativeContactInterruptionObserved",
                    !simulationManager.getNetworkModel().getContactInterruptionEvidence().isEmpty());
            Map<String, Object> dynamicEvidence = getDynamicValidationReport();
            result.put("nativeContactInterruptionEvidenceConsistent",
                    Boolean.TRUE.equals(dynamicEvidence.get("nativeContactInterruptionEvidenceConsistent")));
        } else {
            result.put("bandwidthConservation", new LinkedHashMap<String, Object>());
            result.put("nativeContactInterruptionObserved", false);
            result.put("nativeContactInterruptionEvidenceConsistent", false);
        }
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

    /** JSON boundary sanitizer: non-finite runtime measurements are unknown, never favorable values. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> jsonSafeMap(Map<String, Object> source) {
        return (Map<String, Object>) jsonSafe(source);
    }

    @SuppressWarnings("unchecked")
    private static Object jsonSafe(Object value) {
        if (value instanceof Map) {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                out.put(String.valueOf(entry.getKey()), jsonSafe(entry.getValue()));
            }
            return out;
        }
        if (value instanceof List) {
            List<Object> out = new ArrayList<Object>();
            for (Object item : (List<Object>) value) out.add(jsonSafe(item));
            return out;
        }
        if (value instanceof Double && !Double.isFinite(((Double) value).doubleValue())) return null;
        if (value instanceof Float && !Float.isFinite(((Float) value).floatValue())) return null;
        return value;
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
                simulationThread.join(30000L);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Reset must not overlap two CloudSim runtimes.  The REST layer uses this
     * barrier after close() before publishing a replacement session.
     */
    public boolean isSimulationThreadAlive() {
        return simulationThread != null && simulationThread.isAlive();
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
