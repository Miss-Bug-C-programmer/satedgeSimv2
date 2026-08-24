package edu.weijunyong.satedgesim.server;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.cloudbus.cloudsim.vms.Vm;

import edu.weijunyong.satedgesim.ScenarioManager.simulationParameters;
import edu.weijunyong.satedgesim.SimulationManager.SimulationManager;
import edu.weijunyong.satedgesim.TasksGenerator.Task;
import edu.weijunyong.satedgesim.TasksOrchestration.Orchestrator;

/**
 * Identity-only state held while the native orchestration thread waits for a
 * controller decision.  It deliberately contains references and scalar task
 * metadata only; candidate feasibility and candidate views are materialized
 * later by the requested acquisition path.
 */
public final class PendingDecisionContext {
    public final String sessionId;
    public final long decisionId;
    public final SimulationManager simulationManager;
    public final String[] architecture;
    public final Task task;
    public final List<Vm> vmList;
    public final List<List<Integer>> orchestrationHistory;
    public final RlDecisionBridge.FeasibilityChecker checker;
    public final Map<String, Object> metrics;
    public final double simulationTimeSec;
    public final long createdWallClockMs;
    public final long fullStateBuilderInvocationsBeforeDecision;
    public final long candidateEvaluationsBeforeDecision;

    public boolean legacyFullStateMaterialized;
    public RlState plannerState;
    public Map<String, Object> lastAcquisitionEvidence = new LinkedHashMap<String, Object>();

    public PendingDecisionContext(
            String sessionId,
            long decisionId,
            SimulationManager simulationManager,
            String[] architecture,
            Task task,
            List<Vm> vmList,
            List<List<Integer>> orchestrationHistory,
            RlDecisionBridge.FeasibilityChecker checker,
            Map<String, Object> metrics,
            long fullStateBuilderInvocationsBeforeDecision,
            long candidateEvaluationsBeforeDecision) {
        this.sessionId = sessionId;
        this.decisionId = decisionId;
        this.simulationManager = simulationManager;
        this.architecture = architecture;
        this.task = task;
        this.vmList = vmList;
        this.orchestrationHistory = orchestrationHistory;
        this.checker = checker;
        this.metrics = metrics;
        this.simulationTimeSec = simulationManager == null || simulationManager.getSimulation() == null
                ? 0.0 : simulationManager.getSimulation().clock();
        this.createdWallClockMs = System.currentTimeMillis();
        this.fullStateBuilderInvocationsBeforeDecision = fullStateBuilderInvocationsBeforeDecision;
        this.candidateEvaluationsBeforeDecision = candidateEvaluationsBeforeDecision;
    }

    /** Build only scalar/task identity fields; this never evaluates a candidate. */
    public RlState lightweightState(String status, String message, Map<String, Object> currentMetrics,
            Map<String, Object> lastDecision) {
        RlState state = new RlState();
        state.sessionId = sessionId;
        state.status = status;
        state.message = message;
        state.decisionId = decisionId;
        state.requestId = decisionId;
        state.taskId = task == null ? -1L : task.getId();
        state.simulationTime = simulationTimeSec;
        state.scenarioProfile = simulationParameters.RL_SCENARIO_PROFILE;
        state.scenarioPhase = Orchestrator.scenarioPhaseForTask(task);
        state.taskType = Orchestrator.taskTypeForTask(task);
        state.trafficPhase = Orchestrator.trafficPhaseForTask(task);
        state.taskSourceMode = simulationParameters.RL_TASK_SOURCE_MODE;
        state.actionMaskMode = simulationParameters.RL_ACTION_MASK_MODE;
        state.minLinkSurvivalMarginSec = Math.max(0.0, simulationParameters.RL_MIN_LINK_SURVIVAL_MARGIN_SEC);
        state.isControlledRlScenario = simulationParameters.RL_IS_CONTROLLED_SCENARIO;
        state.configurationViabilityMode = simulationParameters.CONFIGURATION_VIABILITY_MODE;
        state.metrics = currentMetrics == null ? metrics : currentMetrics;
        state.lastDecision = lastDecision;
        if (task != null) {
            state.task = new RlState.TaskView();
            state.task.id = task.getId();
            state.task.applicationId = task.getApplicationID();
            state.task.length = task.getLength();
            state.task.pesNumber = task.getNumberOfPes();
            state.task.fileSize = task.getFileSize();
            state.task.outputSize = task.getOutputSize();
            state.task.generatedTime = task.getTime();
            state.task.maxLatency = task.getMaxLatency();
            state.task.scenarioProfile = state.scenarioProfile;
            state.task.scenarioPhase = state.scenarioPhase;
            state.task.taskType = state.taskType;
            state.task.trafficPhase = state.trafficPhase;
            state.task.taskSourceMode = state.taskSourceMode;
            state.task.isControlledRlScenario = state.isControlledRlScenario;
            if (task.getEdgeDevice() != null) {
                state.sourceDeviceId = task.getEdgeDevice().getDeviceID();
                state.sourceLeoId = state.sourceDeviceId;
                state.task.sourceDeviceId = state.sourceDeviceId;
                state.task.sourceType = String.valueOf(task.getEdgeDevice().getType());
            }
        }
        return state;
    }
}
