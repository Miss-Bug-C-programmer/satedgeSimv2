package edu.weijunyong.satedgesim.server;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.cloudbus.cloudsim.vms.Vm;

import edu.weijunyong.satedgesim.DataCentersManager.DataCenter;
import edu.weijunyong.satedgesim.LocationManager.Location;
import edu.weijunyong.satedgesim.SimulationManager.SimulationManager;
import edu.weijunyong.satedgesim.ScenarioManager.simulationParameters;
import edu.weijunyong.satedgesim.TasksGenerator.Task;
import edu.weijunyong.satedgesim.TasksOrchestration.CandidateCostEstimator;
import edu.weijunyong.satedgesim.TasksOrchestration.Orchestrator;

public final class RlStateBuilder {
    private RlStateBuilder() {
    }

    public static RlState build(
            String sessionId,
            String status,
            long decisionId,
            SimulationManager simulationManager,
            String[] architecture,
            Task task,
            List<Vm> vmList,
            List<List<Integer>> orchestrationHistory,
            RlDecisionBridge.FeasibilityChecker checker,
            Map<String, Object> metrics,
            String message) {
        RlState state = new RlState();
        state.sessionId = sessionId;
        state.status = status;
        state.message = message;
        state.decisionId = decisionId;
        state.requestId = decisionId;
        state.taskId = task == null ? -1L : task.getId();
        state.simulationTime = simulationManager.getSimulation().clock();
        state.scenarioProfile = simulationParameters.RL_SCENARIO_PROFILE;
        state.scenarioPhase = Orchestrator.scenarioPhaseForTask(task);
        state.taskType = Orchestrator.taskTypeForTask(task);
        state.trafficPhase = Orchestrator.trafficPhaseForTask(task);
        state.costEstimatorVersion = CandidateCostEstimator.VERSION;
        state.taskSourceMode = simulationParameters.RL_TASK_SOURCE_MODE;
        state.actionMaskMode = normalizeActionMaskMode(simulationParameters.RL_ACTION_MASK_MODE);
        state.minLinkSurvivalMarginSec = Math.max(0.0, simulationParameters.RL_MIN_LINK_SURVIVAL_MARGIN_SEC);
        state.isControlledRlScenario = simulationParameters.RL_IS_CONTROLLED_SCENARIO;
        state.metrics = metrics;
        DataCenter effectiveSource = Orchestrator.resolveEffectiveSource(simulationManager, task);
        state.task = buildTask(task, effectiveSource);
        if (effectiveSource != null) {
            state.sourceDeviceId = effectiveSource.getDeviceID();
            state.sourceLeoId = effectiveSource.getDeviceID();
        }

        int[] abstractMaskVisible = new int[] { 0, 0, 0, 0 };
        int[] abstractMaskMobilitySafe = new int[] { 0, 0, 0, 0 };
        int[] abstractMaskCompletionSafe = new int[] { 0, 0, 0, 0 };
        for (int i = 0; i < vmList.size(); i++) {
            Vm vm = vmList.get(i);
            Orchestrator.FeasibilityInfo info = Orchestrator.evaluateOffloading(simulationManager, task, vm, architecture, orchestrationHistory, i);
            boolean visibleFeasible = info.isFeasible && checker.isFeasible(architecture, task, vm);
            if (!visibleFeasible && (info.infeasibleReason == null || "".equals(info.infeasibleReason))) {
                info.isFeasible = false;
                info.infeasibleReason = "checker_rejected";
            } else {
                info.isFeasible = visibleFeasible;
            }
            RlState.VmView view = buildVm(i, vm, effectiveSource, task, info);
            state.candidateVms.add(view);
            boolean activeFeasible = isCandidateAllowedForMode(view, state.actionMaskMode);
            state.actionMask.add(activeFeasible ? 1 : 0);
            if (view.isFeasible && view.abstractAction >= 0 && view.abstractAction < abstractMaskVisible.length) {
                abstractMaskVisible[view.abstractAction] = 1;
            }
            if (view.mobilitySafe && view.abstractAction >= 0 && view.abstractAction < abstractMaskMobilitySafe.length) {
                abstractMaskMobilitySafe[view.abstractAction] = 1;
            }
            if (view.completionSafe && view.abstractAction >= 0 && view.abstractAction < abstractMaskCompletionSafe.length) {
                abstractMaskCompletionSafe[view.abstractAction] = 1;
            }
        }
        int[] activeMask = selectActiveAbstractMask(
                state.actionMaskMode,
                abstractMaskVisible,
                abstractMaskMobilitySafe,
                abstractMaskCompletionSafe);
        for (int i = 0; i < activeMask.length; i++) {
            state.abstractActionMask.add(activeMask[i]);
            state.abstractActionMaskVisible.add(abstractMaskVisible[i]);
            state.abstractActionMaskMobilitySafe.add(abstractMaskMobilitySafe[i]);
            state.abstractActionMaskCompletionSafe.add(abstractMaskCompletionSafe[i]);
        }
        state.denseCoverageMode = "source_projection";
        state.denseSourceSummaries = buildDenseSourceSummaries(
                simulationManager,
                architecture,
                task,
                vmList,
                orchestrationHistory);

        List<? extends DataCenter> dcs = simulationManager.getServersManager().getDatacenterList();
        for (DataCenter dc : dcs) {
            state.datacenters.add(buildDataCenter(dc));
        }
        return state;
    }

    private static RlState.TaskView buildTask(Task task, DataCenter effectiveSource) {
        RlState.TaskView view = new RlState.TaskView();
        view.id = task.getId();
        view.applicationId = task.getApplicationID();
        view.length = task.getLength();
        view.pesNumber = task.getNumberOfPes();
        view.fileSize = task.getFileSize();
        view.outputSize = task.getOutputSize();
        view.generatedTime = task.getTime();
        view.maxLatency = task.getMaxLatency();
        if (effectiveSource != null) {
            view.sourceDeviceId = effectiveSource.getDeviceID();
            view.sourceDatacenterId = effectiveSource.getId();
            view.sourceType = String.valueOf(effectiveSource.getType());
        }
        view.scenarioProfile = simulationParameters.RL_SCENARIO_PROFILE;
        view.scenarioPhase = Orchestrator.scenarioPhaseForTask(task);
        view.taskType = Orchestrator.taskTypeForTask(task);
        view.trafficPhase = Orchestrator.trafficPhaseForTask(task);
        view.costEstimatorVersion = CandidateCostEstimator.VERSION;
        view.taskSourceMode = simulationParameters.RL_TASK_SOURCE_MODE;
        view.isControlledRlScenario = simulationParameters.RL_IS_CONTROLLED_SCENARIO;
        return view;
    }

    private static RlState.VmView buildVm(int vmIndex, Vm vm, DataCenter effectiveSource, Task task, Orchestrator.FeasibilityInfo info) {
        RlState.VmView view = new RlState.VmView();
        view.id = "vm-" + vm.getId() + "@candidate-" + vmIndex;
        view.vmIndex = vmIndex;
        view.vmId = vm.getId();
        view.hostId = vm.getHost() == null ? -1L : vm.getHost().getId();
        view.mips = vm.getMips();
        view.pesNumber = vm.getNumberOfPes();
        view.ram = vm.getRam().getCapacity();
        view.bw = vm.getBw().getCapacity();
        view.size = vm.getStorage().getCapacity();
        view.isFeasible = info.isFeasible;
        view.feasible = info.isFeasible;
        view.infeasibleReason = info.infeasibleReason == null ? "" : info.infeasibleReason;
        view.assignedTasks = info.estimatedQueueLength;
        view.estimatedQueueLength = info.estimatedQueueLength;
        view.queueEstimateSource = info.queueEstimateSource;
        view.estimatedComputeCapacity = info.estimatedComputeCapacity;
        view.estimatedComputeDelaySec = info.estimatedComputeDelaySec;
        view.estimatedQueueDelaySec = info.estimatedQueueDelaySec;
        view.estimatedTotalDelaySec = info.estimatedTotalDelaySec;

        if (vm.getHost() != null && vm.getHost().getDatacenter() instanceof DataCenter) {
            DataCenter dc = (DataCenter) vm.getHost().getDatacenter();
            view.datacenterId = dc.getId();
            view.datacenterDeviceId = dc.getDeviceID();
            view.datacenterType = String.valueOf(dc.getType());
            view.logicalTier = info.logicalTier;
            view.abstractAction = info.abstractAction;
            view.abstractActionName = info.abstractActionName;
            view.isLocalToSource = info.isLocalToSource;
            view.isRemoteToSource = info.isRemoteToSource;
            view.linkAvailable = info.linkAvailable;
            view.linkAvailableNow = info.linkAvailableNow;
            view.estimatedLinkLifetimeSec = info.estimatedLinkLifetimeSec;
            view.datacenterCpuUtilization = dc.getCurrentCpuUtilization();
            view.datacenterBatteryPercent = dc.isBattery() ? dc.getBatteryLevelPercentage() : 100.0;
            view.datacenterDead = dc.isDead();
            if (effectiveSource != null) {
                view.distanceToSource = info.sourceDistance;
                view.sourceDistance = info.sourceDistance;
                view.propagationDelaySec = info.propagationDelaySec;
                view.estimatedTransmissionRateMbps = info.estimatedTransmissionRateMbps;
                view.estimatedTransmissionDelaySec = info.estimatedTransmissionDelaySec;
            }
            view.estimatedTaskTransmissionTimeSec = info.estimatedTaskTransmissionTimeSec;
            view.estimatedTaskComputeTimeSec = info.estimatedTaskComputeTimeSec;
            view.estimatedTaskCompletionTimeSec = info.estimatedTaskCompletionTimeSec;
            view.linkSurvivalMarginSec = info.linkSurvivalMarginSec;
            view.linkSurvivalMarginToCompletionSec = info.linkSurvivalMarginToCompletionSec;
            view.handoverRequired = info.handoverRequired;
            view.handoverAvailable = info.handoverAvailable;
            view.mobilityRisk = info.mobilityRisk;
            view.mobilityRiskSource = info.mobilityRiskSource == null ? "unavailable" : info.mobilityRiskSource;
            view.mobilitySafe = info.mobilitySafe;
            view.completionSafe = info.completionSafe;
        } else {
            view.logicalTier = "UNKNOWN";
            view.abstractAction = -1;
            view.abstractActionName = "unknown";
        }
        return view;
    }

    private static RlState.DataCenterView buildDataCenter(DataCenter dc) {
        RlState.DataCenterView view = new RlState.DataCenterView();
        view.id = dc.getId();
        view.deviceId = dc.getDeviceID();
        view.type = String.valueOf(dc.getType());
        view.logicalTier = datacenterLogicalTier(dc);
        Location location = dc.getLocation();
        if (location != null) {
            view.x = location.getXPos();
            view.y = location.getYPos();
            view.z = location.getZPos();
        }
        view.mobile = dc.isMobile();
        view.batteryPowered = dc.isBattery();
        view.batteryLevel = dc.getBatteryLevel();
        view.batteryPercent = dc.isBattery() ? dc.getBatteryLevelPercentage() : 100.0;
        view.dead = dc.isDead();
        view.ram = dc.getRam();
        view.storage = dc.getStorageMemory();
        view.availableStorage = dc.getAvailableMemory();
        view.currentCpuUtilization = dc.getCurrentCpuUtilization();
        view.averageCpuUtilization = dc.getTotalCpuUtilization();
        view.vmCount = dc.getVmList().size();
        view.orchestrator = dc.isOrchestrator();
        return view;
    }

    private static List<RlState.DenseSourceSummary> buildDenseSourceSummaries(
            SimulationManager simulationManager,
            String[] architecture,
            Task task,
            List<Vm> vmList,
            List<List<Integer>> orchestrationHistory) {
        List<RlState.DenseSourceSummary> summaries = new ArrayList<RlState.DenseSourceSummary>();
        if (simulationManager == null || simulationManager.getServersManager() == null || task == null) {
            return summaries;
        }
        List<DataCenter> sources = new ArrayList<DataCenter>();
        for (DataCenter dc : simulationManager.getServersManager().getDatacenterList()) {
            if (dc.getType() == simulationParameters.TYPES.EDGE_DEVICE && dc.getVmList() != null && !dc.getVmList().isEmpty()) {
                sources.add(dc);
            }
        }
        sources.sort(Comparator.comparingInt(DataCenter::getDeviceID));
        for (DataCenter source : sources) {
            summaries.add(buildDenseSourceSummary(simulationManager, architecture, task, source, vmList, orchestrationHistory));
        }
        return summaries;
    }

    private static RlState.DenseSourceSummary buildDenseSourceSummary(
            SimulationManager simulationManager,
            String[] architecture,
            Task task,
            DataCenter source,
            List<Vm> vmList,
            List<List<Integer>> orchestrationHistory) {
        RlState.DenseSourceSummary summary = new RlState.DenseSourceSummary();
        summary.sourceDeviceId = source.getDeviceID();
        summary.sourceDatacenterId = source.getId();
        summary.sourceLogicalTier = "LEO";
        summary.simulationTime = simulationManager.getSimulation().clock();
        summary.scenarioProfile = simulationParameters.RL_SCENARIO_PROFILE;
        summary.scenarioPhase = Orchestrator.scenarioPhaseForTask(task);
        summary.taskType = Orchestrator.taskTypeForTask(task);
        summary.trafficPhase = Orchestrator.trafficPhaseForTask(task);
        summary.taskSourceMode = simulationParameters.RL_TASK_SOURCE_MODE;
        summary.isControlledRlScenario = simulationParameters.RL_IS_CONTROLLED_SCENARIO;
        summary.traceGenerationMode = "dense_projection";
        summary.costEstimatorVersion = CandidateCostEstimator.VERSION;

        int[] counts = new int[] { 0, 0, 0, 0 };
        int[] safeCounts = new int[] { 0, 0, 0, 0 };
        int[] completionCounts = new int[] { 0, 0, 0, 0 };
        double[] rates = new double[] { 0.0, 0.0, 0.0, 0.0 };
        double[] riskSums = new double[] { 0.0, 0.0, 0.0, 0.0 };
        Double[] minDistance = new Double[] { null, null, null, null };
        Double[] bestQueue = new Double[] { null, null, null, null };
        Double[] bestDelay = new Double[] { null, null, null, null };
        Double[] bestPropDelay = new Double[] { null, null, null, null };
        Double[] bestTxDelay = new Double[] { null, null, null, null };
        Double[] bestComputeDelay = new Double[] { null, null, null, null };
        Double[] bestComputeCapacity = new Double[] { null, null, null, null };
        Double[] bestQueueDelay = new Double[] { null, null, null, null };
        Double[] bestTotalDelay = new Double[] { null, null, null, null };
        Double[] bestLinkLifetime = new Double[] { null, null, null, null };
        Double[] bestLinkMargin = new Double[] { null, null, null, null };
        String queueEstimateSource = null;
        String mobilityRiskSource = null;

        for (int i = 0; i < vmList.size(); i++) {
            Vm vm = vmList.get(i);
            Orchestrator.FeasibilityInfo info = Orchestrator.evaluateOffloadingForSource(task, source, simulationManager, vm, architecture, orchestrationHistory, i);
            if (!info.isFeasible || info.abstractAction < 0 || info.abstractAction > 3) {
                continue;
            }
            if (queueEstimateSource == null || "".equals(queueEstimateSource)) {
                queueEstimateSource = info.queueEstimateSource;
            } else if (info.queueEstimateSource != null && !"".equals(info.queueEstimateSource) && !queueEstimateSource.equals(info.queueEstimateSource)) {
                queueEstimateSource = "mixed";
            }
            int action = info.abstractAction;
            counts[action] += 1;
            if (info.mobilitySafe) {
                safeCounts[action] += 1;
            }
            if (info.completionSafe) {
                completionCounts[action] += 1;
            }
            riskSums[action] += Math.max(0.0, info.mobilityRisk);
            rates[action] = Math.max(rates[action], Math.max(0.0, info.estimatedTransmissionRateMbps));
            minDistance[action] = minDouble(minDistance[action], info.sourceDistance);
            if (bestLinkLifetime[action] == null || info.estimatedLinkLifetimeSec < bestLinkLifetime[action].doubleValue()) {
                bestLinkLifetime[action] = info.estimatedLinkLifetimeSec;
            }
            if (bestLinkMargin[action] == null || info.linkSurvivalMarginSec > bestLinkMargin[action].doubleValue()) {
                bestLinkMargin[action] = info.linkSurvivalMarginSec;
            }
            if (mobilityRiskSource == null || "".equals(mobilityRiskSource)) {
                mobilityRiskSource = info.mobilityRiskSource;
            } else if (info.mobilityRiskSource != null && !"".equals(info.mobilityRiskSource) && !mobilityRiskSource.equals(info.mobilityRiskSource)) {
                mobilityRiskSource = "mixed";
            }
            double totalDelay = Math.max(0.0, info.estimatedTotalDelaySec);
            if (bestTotalDelay[action] == null || totalDelay < bestTotalDelay[action].doubleValue()) {
                bestQueue[action] = (double) info.estimatedQueueLength;
                bestDelay[action] = totalDelay;
                bestPropDelay[action] = Math.max(0.0, info.propagationDelaySec);
                bestTxDelay[action] = Math.max(0.0, info.estimatedTransmissionDelaySec);
                bestComputeDelay[action] = Math.max(0.0, info.estimatedComputeDelaySec);
                bestComputeCapacity[action] = Math.max(0.0, info.estimatedComputeCapacity);
                bestQueueDelay[action] = Math.max(0.0, info.estimatedQueueDelaySec);
                bestTotalDelay[action] = totalDelay;
            }
        }

        summary.localVisible = counts[Orchestrator.ACTION_LOCAL] > 0;
        summary.neighborVisible = counts[Orchestrator.ACTION_NEIGHBOR] > 0;
        summary.geoVisible = counts[Orchestrator.ACTION_GEO] > 0;
        summary.groundVisible = counts[Orchestrator.ACTION_GROUND] > 0;
        summary.localMobilitySafe = safeCounts[Orchestrator.ACTION_LOCAL] > 0;
        summary.neighborMobilitySafe = safeCounts[Orchestrator.ACTION_NEIGHBOR] > 0;
        summary.geoMobilitySafe = safeCounts[Orchestrator.ACTION_GEO] > 0;
        summary.groundMobilitySafe = safeCounts[Orchestrator.ACTION_GROUND] > 0;
        summary.localCompletionSafe = completionCounts[Orchestrator.ACTION_LOCAL] > 0;
        summary.neighborCompletionSafe = completionCounts[Orchestrator.ACTION_NEIGHBOR] > 0;
        summary.geoCompletionSafe = completionCounts[Orchestrator.ACTION_GEO] > 0;
        summary.groundCompletionSafe = completionCounts[Orchestrator.ACTION_GROUND] > 0;
        summary.localRate = rates[Orchestrator.ACTION_LOCAL];
        summary.neighborRate = rates[Orchestrator.ACTION_NEIGHBOR];
        summary.geoRate = rates[Orchestrator.ACTION_GEO];
        summary.groundRate = rates[Orchestrator.ACTION_GROUND];
        summary.localCandidateCount = counts[Orchestrator.ACTION_LOCAL];
        summary.neighborCandidateCount = counts[Orchestrator.ACTION_NEIGHBOR];
        summary.geoCandidateCount = counts[Orchestrator.ACTION_GEO];
        summary.groundCandidateCount = counts[Orchestrator.ACTION_GROUND];
        summary.neighborMinDistance = minDistance[Orchestrator.ACTION_NEIGHBOR];
        summary.geoMinDistance = minDistance[Orchestrator.ACTION_GEO];
        summary.groundMinDistance = minDistance[Orchestrator.ACTION_GROUND];
        summary.localBestQueue = bestQueue[Orchestrator.ACTION_LOCAL];
        summary.neighborBestQueue = bestQueue[Orchestrator.ACTION_NEIGHBOR];
        summary.geoBestQueue = bestQueue[Orchestrator.ACTION_GEO];
        summary.groundBestQueue = bestQueue[Orchestrator.ACTION_GROUND];
        summary.localBestDelay = bestDelay[Orchestrator.ACTION_LOCAL];
        summary.neighborBestDelay = bestDelay[Orchestrator.ACTION_NEIGHBOR];
        summary.geoBestDelay = bestDelay[Orchestrator.ACTION_GEO];
        summary.groundBestDelay = bestDelay[Orchestrator.ACTION_GROUND];
        summary.localPropDelay = bestPropDelay[Orchestrator.ACTION_LOCAL];
        summary.neighborPropDelay = bestPropDelay[Orchestrator.ACTION_NEIGHBOR];
        summary.geoPropDelay = bestPropDelay[Orchestrator.ACTION_GEO];
        summary.groundPropDelay = bestPropDelay[Orchestrator.ACTION_GROUND];
        summary.localTxDelay = bestTxDelay[Orchestrator.ACTION_LOCAL];
        summary.neighborTxDelay = bestTxDelay[Orchestrator.ACTION_NEIGHBOR];
        summary.geoTxDelay = bestTxDelay[Orchestrator.ACTION_GEO];
        summary.groundTxDelay = bestTxDelay[Orchestrator.ACTION_GROUND];
        summary.localComputeDelay = bestComputeDelay[Orchestrator.ACTION_LOCAL];
        summary.neighborComputeDelay = bestComputeDelay[Orchestrator.ACTION_NEIGHBOR];
        summary.geoComputeDelay = bestComputeDelay[Orchestrator.ACTION_GEO];
        summary.groundComputeDelay = bestComputeDelay[Orchestrator.ACTION_GROUND];
        summary.localComputeCapacity = bestComputeCapacity[Orchestrator.ACTION_LOCAL];
        summary.neighborComputeCapacity = bestComputeCapacity[Orchestrator.ACTION_NEIGHBOR];
        summary.geoComputeCapacity = bestComputeCapacity[Orchestrator.ACTION_GEO];
        summary.groundComputeCapacity = bestComputeCapacity[Orchestrator.ACTION_GROUND];
        summary.localQueueDelay = bestQueueDelay[Orchestrator.ACTION_LOCAL];
        summary.neighborQueueDelay = bestQueueDelay[Orchestrator.ACTION_NEIGHBOR];
        summary.geoQueueDelay = bestQueueDelay[Orchestrator.ACTION_GEO];
        summary.groundQueueDelay = bestQueueDelay[Orchestrator.ACTION_GROUND];
        summary.localTotalDelay = bestTotalDelay[Orchestrator.ACTION_LOCAL];
        summary.neighborTotalDelay = bestTotalDelay[Orchestrator.ACTION_NEIGHBOR];
        summary.geoTotalDelay = bestTotalDelay[Orchestrator.ACTION_GEO];
        summary.groundTotalDelay = bestTotalDelay[Orchestrator.ACTION_GROUND];
        summary.localBestLinkLifetimeSec = bestLinkLifetime[Orchestrator.ACTION_LOCAL];
        summary.neighborBestLinkLifetimeSec = bestLinkLifetime[Orchestrator.ACTION_NEIGHBOR];
        summary.geoBestLinkLifetimeSec = bestLinkLifetime[Orchestrator.ACTION_GEO];
        summary.groundBestLinkLifetimeSec = bestLinkLifetime[Orchestrator.ACTION_GROUND];
        summary.localBestLinkSurvivalMarginSec = bestLinkMargin[Orchestrator.ACTION_LOCAL];
        summary.neighborBestLinkSurvivalMarginSec = bestLinkMargin[Orchestrator.ACTION_NEIGHBOR];
        summary.geoBestLinkSurvivalMarginSec = bestLinkMargin[Orchestrator.ACTION_GEO];
        summary.groundBestLinkSurvivalMarginSec = bestLinkMargin[Orchestrator.ACTION_GROUND];
        summary.localMobilityRiskMean = counts[Orchestrator.ACTION_LOCAL] <= 0 ? 0.0 : riskSums[Orchestrator.ACTION_LOCAL] / counts[Orchestrator.ACTION_LOCAL];
        summary.neighborMobilityRiskMean = counts[Orchestrator.ACTION_NEIGHBOR] <= 0 ? 0.0 : riskSums[Orchestrator.ACTION_NEIGHBOR] / counts[Orchestrator.ACTION_NEIGHBOR];
        summary.geoMobilityRiskMean = counts[Orchestrator.ACTION_GEO] <= 0 ? 0.0 : riskSums[Orchestrator.ACTION_GEO] / counts[Orchestrator.ACTION_GEO];
        summary.groundMobilityRiskMean = counts[Orchestrator.ACTION_GROUND] <= 0 ? 0.0 : riskSums[Orchestrator.ACTION_GROUND] / counts[Orchestrator.ACTION_GROUND];
        summary.queueEstimateSource = queueEstimateSource == null || "".equals(queueEstimateSource) ? "unknown" : queueEstimateSource;
        summary.mobilityRiskSource = mobilityRiskSource == null || "".equals(mobilityRiskSource) ? "unavailable" : mobilityRiskSource;
        summary.actionMaskMode = normalizeActionMaskMode(simulationParameters.RL_ACTION_MASK_MODE);
        summary.minLinkSurvivalMarginSec = Math.max(0.0, simulationParameters.RL_MIN_LINK_SURVIVAL_MARGIN_SEC);
        int[] visibleMask = new int[] { summary.localVisible ? 1 : 0, summary.neighborVisible ? 1 : 0, summary.geoVisible ? 1 : 0, summary.groundVisible ? 1 : 0 };
        int[] mobilitySafeMask = new int[] { summary.localMobilitySafe ? 1 : 0, summary.neighborMobilitySafe ? 1 : 0, summary.geoMobilitySafe ? 1 : 0, summary.groundMobilitySafe ? 1 : 0 };
        int[] completionSafeMask = new int[] { summary.localCompletionSafe ? 1 : 0, summary.neighborCompletionSafe ? 1 : 0, summary.geoCompletionSafe ? 1 : 0, summary.groundCompletionSafe ? 1 : 0 };
        int[] activeMask = selectActiveAbstractMask(summary.actionMaskMode, visibleMask, mobilitySafeMask, completionSafeMask);
        for (int i = 0; i < activeMask.length; i++) {
            summary.abstractActionMask.add(activeMask[i]);
            summary.abstractActionMaskVisible.add(visibleMask[i]);
            summary.abstractActionMaskMobilitySafe.add(mobilitySafeMask[i]);
            summary.abstractActionMaskCompletionSafe.add(completionSafeMask[i]);
        }
        return summary;
    }

    private static Double minDouble(Double current, double candidate) {
        if (current == null) {
            return candidate;
        }
        return Math.min(current.doubleValue(), candidate);
    }

    private static String normalizeActionMaskMode(String raw) {
        String mode = raw == null ? "visible_only" : raw.trim().toLowerCase();
        if ("visible_only".equals(mode) || "mobility_safe".equals(mode) || "completion_safe".equals(mode)) {
            return mode;
        }
        return "visible_only";
    }

    private static boolean isCandidateAllowedForMode(RlState.VmView view, String mode) {
        if (!view.isFeasible) {
            return false;
        }
        if ("mobility_safe".equals(mode)) {
            return view.mobilitySafe;
        }
        if ("completion_safe".equals(mode)) {
            return view.completionSafe;
        }
        return true;
    }

    private static int[] selectActiveAbstractMask(String mode, int[] visibleMask, int[] mobilitySafeMask, int[] completionSafeMask) {
        if ("mobility_safe".equals(mode)) {
            return mobilitySafeMask;
        }
        if ("completion_safe".equals(mode)) {
            return completionSafeMask;
        }
        return visibleMask;
    }

    private static String datacenterLogicalTier(DataCenter dc) {
        if (dc.getType() == simulationParameters.TYPES.CLOUD) {
            return "GEO";
        }
        if (dc.getType() == simulationParameters.TYPES.EDGE_DATACENTER) {
            return "GROUND";
        }
        if (dc.getType() == simulationParameters.TYPES.EDGE_DEVICE) {
            return "LEO";
        }
        return "UNKNOWN";
    }

}
