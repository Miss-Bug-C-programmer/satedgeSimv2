package edu.weijunyong.satedgesim.server;

import java.lang.reflect.Constructor;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudsimplus.util.Log;

import ch.qos.logback.classic.Level;
import edu.weijunyong.satedgesim.DataCentersManager.DataCenter;
import edu.weijunyong.satedgesim.DataCentersManager.DefaultDataCenter;
import edu.weijunyong.satedgesim.DataCentersManager.DefaultEnergyModel;
import edu.weijunyong.satedgesim.DataCentersManager.EnergyModel;
import edu.weijunyong.satedgesim.DataCentersManager.ServersManager;
import edu.weijunyong.satedgesim.LocationManager.DefaultMobilityModel;
import edu.weijunyong.satedgesim.LocationManager.Mobility;
import edu.weijunyong.satedgesim.Network.DefaultNetworkModel;
import edu.weijunyong.satedgesim.Network.NetworkModel;
import edu.weijunyong.satedgesim.ScenarioManager.FilesParser;
import edu.weijunyong.satedgesim.ScenarioManager.Scenario;
import edu.weijunyong.satedgesim.ScenarioManager.simulationParameters;
import edu.weijunyong.satedgesim.SimulationManager.SimLog;
import edu.weijunyong.satedgesim.SimulationManager.SimulationManager;
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
        int devicesCount = resetRequest.devicesCount > 0 ? resetRequest.devicesCount : simulationParameters.MAX_NUM_OF_EDGE_DEVICES;
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
