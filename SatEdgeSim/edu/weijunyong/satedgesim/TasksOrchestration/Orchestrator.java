package edu.weijunyong.satedgesim.TasksOrchestration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.cloudbus.cloudsim.vms.Vm;

import edu.weijunyong.satedgesim.DataCentersManager.DataCenter;
import edu.weijunyong.satedgesim.ScenarioManager.simulationParameters;
import edu.weijunyong.satedgesim.SimulationManager.SimLog;
import edu.weijunyong.satedgesim.SimulationManager.SimulationManager;
import edu.weijunyong.satedgesim.TasksGenerator.Task;

public abstract class Orchestrator {
	public static final int ACTION_LOCAL = 0;
	public static final int ACTION_NEIGHBOR = 1;
	public static final int ACTION_GEO = 2;
	public static final int ACTION_GROUND = 3;
	private static final int MIXED_V2_PHASE_COUNT = 6;
	private static final int MIXED_V2_PHASE_SPAN = 4;
	private static final int MIXED_V2_PHASE_BUCKETS = MIXED_V2_PHASE_COUNT * MIXED_V2_PHASE_SPAN;

	public static class FeasibilityInfo {
		public String logicalTier = "UNKNOWN";
		public int abstractAction = -1;
		public String abstractActionName = "unknown";
		public boolean isLocalToSource = false;
		public boolean isRemoteToSource = false;
		public boolean linkAvailable = false;
		public boolean linkAvailableNow = false;
		public double estimatedLinkLifetimeSec = 0.0;
		public double sourceDistance = 0.0;
		public double propagationDelaySec = 0.0;
		public double estimatedTransmissionRateMbps = 0.0;
		public double estimatedTransmissionDelaySec = 0.0;
		public double estimatedComputeCapacity = 0.0;
		public double estimatedComputeDelaySec = 0.0;
		public double estimatedQueueDelaySec = 0.0;
		public double estimatedTotalDelaySec = 0.0;
		public double estimatedTaskTransmissionTimeSec = 0.0;
		public double estimatedTaskComputeTimeSec = 0.0;
		public double estimatedTaskCompletionTimeSec = 0.0;
		public double linkSurvivalMarginSec = 0.0;
		public double linkSurvivalMarginToCompletionSec = 0.0;
		public boolean handoverRequired = false;
		public boolean handoverAvailable = false;
		public double mobilityRisk = 1.0;
		public String mobilityRiskSource = "unavailable";
		public boolean mobilitySafe = false;
		public boolean completionSafe = false;
		public int estimatedQueueLength = 0;
		public String queueEstimateSource = "unknown";
		public boolean isFeasible = false;
		public String infeasibleReason = "";
	}

	public static final class ControlledScenarioDescriptor {
		public String scenarioPhase = "default_phase";
		public String taskType = "generic_service";
		public String trafficPhase = "default_traffic";
		public double computeDemandScale = 1.0;
		public double dataDemandScale = 1.0;
		public double queueDelayScale = 1.0;
	}

	protected List<List<Integer>> orchestrationHistory;
	protected List<Vm> vmList;
	protected SimulationManager simulationManager;
	protected SimLog simLog;
	protected String algorithm;
	protected String architecture;

	public Orchestrator(SimulationManager simulationManager) {
		this.simulationManager = simulationManager;
		simLog = simulationManager.getSimulationLogger();
		orchestrationHistory = new ArrayList<>();
		vmList = simulationManager.getServersManager().getVmList();
		algorithm = simulationManager.getScenario().getStringOrchAlgorithm();
		architecture = simulationManager.getScenario().getStringOrchArchitecture();
		initHistoryList(vmList.size());
	}

	private void initHistoryList(int size) {
		for (int vm = 0; vm < size; vm++) {
			// Creating a list to store the orchestration history for each VM (virtual machine)
			orchestrationHistory.add(new ArrayList<>());
		}
	}

	public void initialize(Task task) {
		if ("CLOUD_ONLY".equals(architecture)) {
			cloudOnly(task);
		} else if ("MIST_ONLY".equals(architecture)) {
			mistOnly(task);
		} else if ("EDGE_AND_CLOUD".equals(architecture)) {
			edgeAndCloud(task);
		} else if ("ALL".equals(architecture)) {
			all(task);
		} else if ("EDGE_ONLY".equals(architecture)) {
			edgeOnly(task);
		} else if ("MIST_AND_CLOUD".equals(architecture)) {
			mistAndCloud(task);
		} else if ("MIST_AND_EDGE".equals(architecture)) {
			mistAndEdge(task);
		}
	}

	// If the orchestration scenario is MIST_ONLY send Tasks only to edge devices 
	private void mistOnly(Task task) {
		String[] Architecture = { "Mist" };
		sendTask(task, findVM(Architecture, task));
	}

	// If the orchestration scenario is ClOUD_ONLY send Tasks (cloudlets) only to cloud virtual machines (vms)
	private void cloudOnly(Task task) {
		String[] Architecture = { "Cloud" };
		sendTask(task, findVM(Architecture, task));
	}

	// If the orchestration scenario is EDGE_AND_CLOUD send Tasks only to edge data centers or cloud virtual machines (vms)
	private void edgeAndCloud(Task task) {
		String[] Architecture = { "Cloud", "Edge" };
		sendTask(task, findVM(Architecture, task));
	}

	// If the orchestration scenario is MIST_AND_CLOUD send Tasks only to edge devices or cloud virtual machines (vms)
	private void mistAndCloud(Task task) {
		String[] Architecture = { "Cloud", "Mist" };
		sendTask(task, findVM(Architecture, task));
	}

	// If the orchestration scenario is EDGE_ONLY send Tasks only to edge data centers 
	private void edgeOnly(Task task) {
		String[] Architecture = { "Edge" };
		sendTask(task, findVM(Architecture, task));
	}

	// If the orchestration scenario is ALL send Tasks to any virtual machine (vm) or device
	private void all(Task task) {
		String[] Architecture = { "Cloud", "Edge", "Mist" };
		sendTask(task, findVM(Architecture, task));
	}
	
	private void mistAndEdge(Task task) {
		String[] Architecture = { "Edge", "Mist" };
		sendTask(task, findVM(Architecture, task));
	}

	protected abstract int findVM(String[] architecture, Task task);

	protected void sendTask(Task task, int vm) {
		// assign the tasks to the vm found
		assignTaskToVm(vm, task);

		// Offload it only if resources are available (i.e. the offloading destination is available)
		if (task.getVm() != Vm.NULL) // Send the task to execute it
			task.getEdgeDevice().getVmTaskMap().add(new VmTaskMapItem((Vm) task.getVm(), task));
	}

	protected void assignTaskToVm(int vmIndex, Task task) {
		if (vmIndex == -1) {
			simLog.incrementTasksFailedLackOfRessources(task);
		} else {
			task.setVm(vmList.get(vmIndex)); // send this task to this vm
			simLog.deepLog(simulationManager.getSimulation().clock() + " : EdgeOrchestrator, Task: " + task.getId()
					+ " assigned to " + ((DataCenter)vmList.get(vmIndex).getHost().getDatacenter()).getType() + " vm: " + vmList.get(vmIndex).getId());

			// update history
			orchestrationHistory.get(vmIndex).add((int) task.getId());
		}
	}

	protected boolean sameLocation(DataCenter device1, DataCenter device2, int RANGE) {
		double distance = SimulationManager.getdistance(device1, device2);
		if (distance < RANGE && SimulationManager.issetlink(device1, device2)) {
			return true;
		}
		else {
			return false;
		}
	}

	protected boolean arrayContains(String[] Architecture, String value) {
		for (String s : Architecture) {
			if (s.equals(value))
				return true;
		}
		return false;
	}

	protected boolean offloadingIsPossible(Task task, Vm vm, String[] architecture) {
		return evaluateOffloading(simulationManager, task, vm, architecture, orchestrationHistory, resolveVmIndex(vmList, vm)).isFeasible;
	}

	public static FeasibilityInfo evaluateOffloading(
			Task task,
			Vm vm,
			String[] architecture,
			List<List<Integer>> orchestrationHistory,
			int vmIndex) {
		return evaluateOffloadingInternal(null, task, task == null ? null : task.getEdgeDevice(), vm, architecture, orchestrationHistory, vmIndex);
	}

	public static FeasibilityInfo evaluateOffloading(
			SimulationManager simulationManager,
			Task task,
			Vm vm,
			String[] architecture,
			List<List<Integer>> orchestrationHistory,
			int vmIndex) {
		DataCenter source = resolveEffectiveSource(simulationManager, task);
		return evaluateOffloadingInternal(simulationManager, task, source, vm, architecture, orchestrationHistory, vmIndex);
	}

	public static FeasibilityInfo evaluateOffloadingForSource(
			Task task,
			DataCenter sourceOverride,
			SimulationManager simulationManager,
			Vm vm,
			String[] architecture,
			List<List<Integer>> orchestrationHistory,
			int vmIndex) {
		return evaluateOffloadingInternal(simulationManager, task, sourceOverride, vm, architecture, orchestrationHistory, vmIndex);
	}

	private static FeasibilityInfo evaluateOffloadingInternal(
			SimulationManager simulationManager,
			Task task,
			DataCenter sourceOverride,
			Vm vm,
			String[] architecture,
			List<List<Integer>> orchestrationHistory,
			int vmIndex) {
		FeasibilityInfo info = new FeasibilityInfo();
		info.estimatedQueueLength = queueLength(vm, orchestrationHistory, vmIndex);
		info.queueEstimateSource = "actual";
		if (task == null || vm == null || vm.getHost() == null || !(vm.getHost().getDatacenter() instanceof DataCenter)) {
			info.infeasibleReason = "invalid_candidate";
			return info;
		}

		DataCenter destination = (DataCenter) vm.getHost().getDatacenter();
		DataCenter source = sourceOverride;
		info.abstractAction = determineAbstractAction(destination, source);
		info.logicalTier = logicalTierFromAction(info.abstractAction);
		info.abstractActionName = abstractActionName(info.abstractAction);
		info.isLocalToSource = source != null && destination.getId() == source.getId();
		info.isRemoteToSource = !info.isLocalToSource;
		if (info.abstractAction < 0) {
			info.infeasibleReason = "unknown_tier";
			return info;
		}
		if (!architectureAllows(architecture, info.abstractAction)) {
			info.infeasibleReason = "tier_not_enabled";
			return info;
		}
		if (source == null) {
			info.infeasibleReason = "missing_source";
			return info;
		}
		if (source.isDead()) {
			info.infeasibleReason = "dead_source";
			return info;
		}
		if (destination.isDead()) {
			info.infeasibleReason = "dead_destination";
			return info;
		}

		if (simulationParameters.RL_IS_CONTROLLED_SCENARIO) {
			applyControlledScenario(simulationManager, task, source, destination, info);
			finalizeMobilityRisk(simulationManager, task, source, destination, info, true);
			return info;
		}

		CandidateCostEstimator.populateActual(
				info,
				task,
				vm,
				source,
				destination,
				info.estimatedQueueLength,
				info.queueEstimateSource);
		finalizeMobilityRisk(simulationManager, task, source, destination, info, false);

		if (info.estimatedComputeCapacity <= 0.0) {
			info.infeasibleReason = "no_capacity";
			return info;
		}
		if (info.isLocalToSource) {
			info.isFeasible = true;
			info.infeasibleReason = "";
			return info;
		}
		if (!info.linkAvailable) {
			info.infeasibleReason = "no_link";
			return info;
		}
		if (info.sourceDistance >= tierRange(destination)) {
			info.infeasibleReason = "out_of_coverage";
			return info;
		}
		info.isFeasible = true;
		info.infeasibleReason = "";
		return info;
	}

	public static DataCenter resolveEffectiveSource(SimulationManager simulationManager, Task task) {
		if (task == null || task.getEdgeDevice() == null || simulationManager == null || !simulationParameters.RL_IS_CONTROLLED_SCENARIO) {
			return task == null ? null : task.getEdgeDevice();
		}
		String mode = simulationParameters.RL_TASK_SOURCE_MODE == null ? "current" : simulationParameters.RL_TASK_SOURCE_MODE.trim().toLowerCase();
		if ("current".equals(mode)) {
			return task.getEdgeDevice();
		}
		List<DataCenter> sources = controlledSources(simulationManager);
		if (sources.isEmpty()) {
			return task.getEdgeDevice();
		}
		if ("round_robin_leo".equals(mode)) {
			int index = (int) Math.floorMod(task.getId(), sources.size());
			return sources.get(index);
		}
		if ("random_leo".equals(mode)) {
			long mix = mix64(task.getId() * 1315423911L + simulationParameters.RL_SERVER_SEED * 2654435761L);
			int index = (int) Math.floorMod(mix, sources.size());
			return sources.get(index);
		}
		return task.getEdgeDevice();
	}

	private static int queueLength(Vm vm, List<List<Integer>> orchestrationHistory, int vmIndex) {
		if (vm == null) {
			return 0;
		}
		int assigned = 0;
		if (orchestrationHistory != null && vmIndex >= 0 && vmIndex < orchestrationHistory.size()) {
			assigned = orchestrationHistory.get(vmIndex).size();
		}
		int finished = vm.getCloudletScheduler() == null ? 0 : vm.getCloudletScheduler().getCloudletFinishedList().size();
		int outstanding = Math.max(0, assigned - finished);
		if (outstanding > 0) {
			return outstanding;
		}
		if (assigned > 0) {
			return assigned;
		}
		if (orchestrationHistory == null || vmIndex < 0 || vmIndex >= orchestrationHistory.size()) {
			return 0;
		}
		return orchestrationHistory.get(vmIndex).size();
	}

	private static int resolveVmIndex(List<Vm> vmList, Vm vm) {
		if (vmList == null || vm == null) {
			return -1;
		}
		for (int i = 0; i < vmList.size(); i++) {
			if (vmList.get(i) == vm) {
				return i;
			}
		}
		return -1;
	}

	private static void applyControlledScenario(
			SimulationManager simulationManager,
			Task task,
			DataCenter source,
			DataCenter destination,
			FeasibilityInfo info) {
		String profile = simulationParameters.RL_SCENARIO_PROFILE == null ? "default" : simulationParameters.RL_SCENARIO_PROFILE.trim().toLowerCase();
		ControlledScenarioDescriptor descriptor = describeControlledScenario(profile, task);
		double availabilityThreshold = tierAvailabilityThreshold(profile, descriptor, info.abstractAction);
		double visibilityScore = normalizedScore(task, source, destination, 11L + info.abstractAction);
		double metricScore = normalizedScore(task, source, destination, 37L + info.abstractAction);
		double phaseScore = normalizedScore(task, source, destination, 71L + info.abstractAction);

		info.sourceDistance = controlledDistanceMeters(profile, descriptor, info.abstractAction, visibilityScore, phaseScore);
		info.propagationDelaySec = CandidateCostEstimator.propagationDelaySec(info.sourceDistance);
		info.estimatedQueueLength = profileQueueLength(profile, descriptor, info.abstractAction, metricScore, phaseScore, info.estimatedQueueLength, task);
		info.queueEstimateSource = "controlled_estimate";
		info.estimatedComputeCapacity = profileComputeCapacity(profile, descriptor, info.abstractAction, metricScore, info.estimatedComputeCapacity, task);
		info.estimatedTransmissionRateMbps = profileRateMbps(profile, descriptor, info.abstractAction, metricScore, task);
		if ("local_pressure".equals(profile) && info.abstractAction == ACTION_LOCAL) {
			info.estimatedQueueLength += 6;
			info.estimatedComputeCapacity /= 1.8;
		}
		CandidateCostEstimator.populateControlled(
				info,
				task,
				descriptor.dataDemandScale,
				descriptor.computeDemandScale,
				descriptor.queueDelayScale);

		if (info.abstractAction == ACTION_LOCAL) {
			info.linkAvailable = true;
			info.isFeasible = true;
			info.infeasibleReason = "";
			return;
		}
		if ("remote_unavailable".equals(profile)) {
			info.linkAvailable = false;
			info.isFeasible = false;
			info.infeasibleReason = "profile_remote_unavailable";
			return;
		}

		boolean available = visibilityScore <= availabilityThreshold;
		info.linkAvailable = available;
		info.isFeasible = available;
		info.infeasibleReason = available ? "" : "profile_not_visible";
	}

	private static void finalizeMobilityRisk(
			SimulationManager simulationManager,
			Task task,
			DataCenter source,
			DataCenter destination,
			FeasibilityInfo info,
			boolean controlledEstimate) {
		info.linkAvailableNow = info.linkAvailable;
		info.estimatedTaskTransmissionTimeSec = Math.max(0.0, info.estimatedTransmissionDelaySec);
		info.estimatedTaskComputeTimeSec = Math.max(0.0, info.estimatedComputeDelaySec);
		info.estimatedTaskCompletionTimeSec = Math.max(0.0, info.estimatedTotalDelaySec);
		info.estimatedLinkLifetimeSec = estimateLinkLifetimeSec(simulationManager, source, destination, info, controlledEstimate);
		info.linkSurvivalMarginSec = info.estimatedLinkLifetimeSec - info.estimatedTaskTransmissionTimeSec;
		info.linkSurvivalMarginToCompletionSec = info.estimatedLinkLifetimeSec - info.estimatedTaskCompletionTimeSec;
		info.handoverRequired = !info.isLocalToSource && info.linkSurvivalMarginSec < 0.0;
		info.handoverAvailable = false;
		double minMargin = Math.max(0.0, simulationParameters.RL_MIN_LINK_SURVIVAL_MARGIN_SEC);
		info.mobilitySafe = info.isLocalToSource || (info.linkAvailableNow && info.linkSurvivalMarginSec >= minMargin);
		info.completionSafe = info.isLocalToSource || (info.linkAvailableNow && info.linkSurvivalMarginToCompletionSec >= minMargin);
		if (info.isLocalToSource) {
			info.mobilityRisk = 0.0;
		} else if (!info.linkAvailableNow) {
			info.mobilityRisk = 1.0;
		} else if (info.linkSurvivalMarginToCompletionSec < minMargin || info.linkSurvivalMarginSec < minMargin) {
			info.mobilityRisk = 1.0;
		} else if (info.linkSurvivalMarginToCompletionSec < (minMargin + 5.0) || info.linkSurvivalMarginSec < (minMargin + 5.0)) {
			info.mobilityRisk = 0.5;
		} else {
			info.mobilityRisk = 0.0;
		}
		info.mobilityRiskSource = controlledEstimate ? "controlled_estimate" : "actual";
	}

	private static double estimateLinkLifetimeSec(
			SimulationManager simulationManager,
			DataCenter source,
			DataCenter destination,
			FeasibilityInfo info,
			boolean controlledEstimate) {
		if (info.isLocalToSource) {
			return 1.0e9;
		}
		if (source == null || destination == null) {
			return 0.0;
		}
		int range = tierRange(destination);
		double distance = Math.max(0.0, info.sourceDistance);
		double marginDistance = Math.max(0.0, range - distance);
		if (!info.linkAvailableNow) {
			return 0.0;
		}
		double relSpeed = estimatedRelativeSpeedMps(simulationManager, source, destination, info.abstractAction, controlledEstimate);
		return marginDistance / Math.max(1.0, relSpeed);
	}

	private static double estimatedRelativeSpeedMps(
			SimulationManager simulationManager,
			DataCenter source,
			DataCenter destination,
			int abstractAction,
			boolean controlledEstimate) {
		double floor = Math.max(10.0, simulationParameters.UPDATE_INTERVAL <= 0.0 ? 100.0 : (50.0 / simulationParameters.UPDATE_INTERVAL));
		double base;
		if (abstractAction == ACTION_NEIGHBOR) {
			base = 3800.0;
		} else if (abstractAction == ACTION_GEO) {
			base = 900.0;
		} else if (abstractAction == ACTION_GROUND) {
			base = 2900.0;
		} else {
			base = 100.0;
		}
		if (simulationManager == null || source == null || destination == null) {
			return Math.max(floor, base);
		}
		double dynamic = Math.max(0.0, SimulationManager.getdistance(source, destination));
		double scaled = dynamic / Math.max(10.0, simulationParameters.EDGE_DEVICES_RANGE);
		double estimate = base * Math.max(0.5, Math.min(2.0, scaled));
		if (controlledEstimate) {
			estimate *= 1.10;
		}
		return Math.max(floor, estimate);
	}

	private static List<DataCenter> controlledSources(SimulationManager simulationManager) {
		List<DataCenter> sources = new ArrayList<>();
		if (simulationManager == null || simulationManager.getServersManager() == null) {
			return sources;
		}
		for (DataCenter dc : simulationManager.getServersManager().getDatacenterList()) {
			if (dc.getType() == simulationParameters.TYPES.EDGE_DEVICE && dc.getVmList() != null && !dc.getVmList().isEmpty()) {
				sources.add(dc);
			}
		}
		sources.sort(Comparator.comparingInt(DataCenter::getDeviceID));
		return sources;
	}

	public static ControlledScenarioDescriptor describeControlledScenario(Task task) {
		String profile = simulationParameters.RL_SCENARIO_PROFILE == null ? "default" : simulationParameters.RL_SCENARIO_PROFILE.trim().toLowerCase();
		return describeControlledScenario(profile, task);
	}

	public static String scenarioPhaseForTask(Task task) {
		return describeControlledScenario(task).scenarioPhase;
	}

	public static String taskTypeForTask(Task task) {
		return describeControlledScenario(task).taskType;
	}

	public static String trafficPhaseForTask(Task task) {
		return describeControlledScenario(task).trafficPhase;
	}

	private static ControlledScenarioDescriptor describeControlledScenario(String profile, Task task) {
		ControlledScenarioDescriptor descriptor = new ControlledScenarioDescriptor();
		if (!"mixed_cost_landscape_v2".equals(profile)) {
			descriptor.scenarioPhase = profile + "_phase";
			descriptor.trafficPhase = profile + "_traffic";
			double compute = taskComputeIntensity(task);
			double data = taskDataIntensity(task);
			if (compute > 0.65 && data < 0.55) {
				descriptor.taskType = "compute_intensive_service";
				descriptor.computeDemandScale = 1.18;
				descriptor.dataDemandScale = 0.92;
			} else if (data > 0.68 && compute < 0.58) {
				descriptor.taskType = "data_heavy_service";
				descriptor.computeDemandScale = 0.96;
				descriptor.dataDemandScale = 1.14;
			} else {
				descriptor.taskType = "mixed_interactive_service";
				descriptor.computeDemandScale = 1.02;
				descriptor.dataDemandScale = 1.00;
			}
			return descriptor;
		}

		int phaseBucket = (int) Math.floorMod(task == null ? 0L : task.getId(), MIXED_V2_PHASE_BUCKETS);
		int phaseIndex = Math.max(0, Math.min(MIXED_V2_PHASE_COUNT - 1, phaseBucket / MIXED_V2_PHASE_SPAN));
		switch (phaseIndex) {
		case 0:
			descriptor.scenarioPhase = "local_favorable_phase";
			descriptor.taskType = "latency_critical_micro";
			descriptor.trafficPhase = "remote_pressure_window";
			descriptor.computeDemandScale = 0.46;
			descriptor.dataDemandScale = 0.50;
			break;
		case 1:
			descriptor.scenarioPhase = "neighbor_favorable_phase";
			descriptor.taskType = "cooperative_sync";
			descriptor.trafficPhase = "peer_mesh_clear_window";
			descriptor.computeDemandScale = 0.90;
			descriptor.dataDemandScale = 0.78;
			break;
		case 2:
			descriptor.scenarioPhase = "geo_favorable_phase";
			descriptor.taskType = "compute_intensive_batch";
			descriptor.trafficPhase = "geo_compute_clear_window";
			descriptor.computeDemandScale = 1.22;
			descriptor.dataDemandScale = 0.86;
			break;
		case 3:
			descriptor.scenarioPhase = "ground_favorable_phase";
			descriptor.taskType = "ground_service_pipeline";
			descriptor.trafficPhase = "ground_backhaul_clear_window";
			descriptor.computeDemandScale = 1.06;
			descriptor.dataDemandScale = 1.03;
			break;
		case 4:
			descriptor.scenarioPhase = "remote_congested_phase";
			descriptor.taskType = "resilient_fallback_mix";
			descriptor.trafficPhase = "remote_congestion_wave";
			descriptor.computeDemandScale = 1.14;
			descriptor.dataDemandScale = 1.00;
			break;
		default:
			descriptor.scenarioPhase = "balanced_contention_phase";
			descriptor.taskType = "balanced_sensor_fusion";
			descriptor.trafficPhase = "balanced_tradeoff_window";
			descriptor.computeDemandScale = 1.00;
			descriptor.dataDemandScale = 1.00;
			break;
		}
		return descriptor;
	}

	private static double tierAvailabilityThreshold(String profile, ControlledScenarioDescriptor descriptor, int action) {
		if ("mixed_cost_landscape_v2".equals(profile)) {
			String phase = descriptor.scenarioPhase;
			if ("local_favorable_phase".equals(phase)) {
				return thresholdForAction(action, 1.0, 0.82, 0.72, 0.74);
			}
			if ("neighbor_favorable_phase".equals(phase)) {
				return thresholdForAction(action, 1.0, 0.96, 0.70, 0.76);
			}
			if ("geo_favorable_phase".equals(phase)) {
				return thresholdForAction(action, 1.0, 0.82, 0.86, 0.74);
			}
			if ("ground_favorable_phase".equals(phase)) {
				return thresholdForAction(action, 1.0, 0.78, 0.68, 0.94);
			}
			if ("remote_congested_phase".equals(phase)) {
				return thresholdForAction(action, 1.0, 0.80, 0.72, 0.76);
			}
			return thresholdForAction(action, 1.0, 0.90, 0.78, 0.86);
		}
		if ("balanced_four_tier".equals(profile)) {
			return thresholdForAction(action, 1.0, 0.72, 0.60, 0.60);
		}
		if ("mixed_cost_landscape".equals(profile)) {
			return thresholdForAction(action, 1.0, 0.75, 0.70, 0.72);
		}
		if ("geo_favorable".equals(profile)) {
			return thresholdForAction(action, 1.0, 0.60, 0.92, 0.45);
		}
		if ("local_favorable".equals(profile)) {
			return thresholdForAction(action, 1.0, 0.48, 0.42, 0.38);
		}
		if ("ground_congested".equals(profile)) {
			return thresholdForAction(action, 1.0, 0.72, 0.68, 0.82);
		}
		if ("neighbor_favorable".equals(profile)) {
			return thresholdForAction(action, 1.0, 0.95, 0.52, 0.52);
		}
		if ("remote_congested".equals(profile)) {
			return thresholdForAction(action, 1.0, 0.60, 0.55, 0.55);
		}
		if ("ground_favorable".equals(profile)) {
			return thresholdForAction(action, 1.0, 0.45, 0.45, 0.90);
		}
		if ("local_pressure".equals(profile)) {
			return thresholdForAction(action, 1.0, 0.78, 0.65, 0.65);
		}
		if ("remote_unavailable".equals(profile)) {
			return thresholdForAction(action, 1.0, 0.0, 0.0, 0.0);
		}
		return 1.0;
	}

	private static double thresholdForAction(int action, double local, double neighbor, double geo, double ground) {
		if (action == ACTION_LOCAL) {
			return local;
		}
		if (action == ACTION_NEIGHBOR) {
			return neighbor;
		}
		if (action == ACTION_GEO) {
			return geo;
		}
		if (action == ACTION_GROUND) {
			return ground;
		}
		return 0.0;
	}

	private static double controlledDistanceMeters(String profile, ControlledScenarioDescriptor descriptor, int action, double score, double phaseScore) {
		if ("mixed_cost_landscape_v2".equals(profile)) {
			String phase = descriptor.scenarioPhase;
			if (action == ACTION_LOCAL) {
				return 0.0;
			}
			if (action == ACTION_NEIGHBOR) {
				if ("neighbor_favorable_phase".equals(phase)) {
					return interpolatedDistance(420000.0, 920000.0, score, phaseScore);
				}
				if ("local_favorable_phase".equals(phase)) {
					return interpolatedDistance(860000.0, 1680000.0, score, phaseScore);
				}
				if ("ground_favorable_phase".equals(phase)) {
					return interpolatedDistance(760000.0, 1480000.0, score, phaseScore);
				}
				if ("remote_congested_phase".equals(phase)) {
					return interpolatedDistance(680000.0, 1420000.0, score, phaseScore);
				}
				return interpolatedDistance(560000.0, 1280000.0, score, phaseScore);
			}
			if (action == ACTION_GEO) {
				if ("geo_favorable_phase".equals(phase)) {
					return interpolatedDistance(26000000.0, 30000000.0, score, phaseScore);
				}
				if ("ground_favorable_phase".equals(phase)) {
					return interpolatedDistance(29000000.0, 34000000.0, score, phaseScore);
				}
				if ("local_favorable_phase".equals(phase)) {
					return interpolatedDistance(28500000.0, 33200000.0, score, phaseScore);
				}
				if ("remote_congested_phase".equals(phase)) {
					return interpolatedDistance(27500000.0, 33200000.0, score, phaseScore);
				}
				return interpolatedDistance(27000000.0, 32000000.0, score, phaseScore);
			}
			if ("ground_favorable_phase".equals(phase)) {
				return interpolatedDistance(900000.0, 1600000.0, score, phaseScore);
			}
			if ("neighbor_favorable_phase".equals(phase)) {
				return interpolatedDistance(1100000.0, 2000000.0, score, phaseScore);
			}
			if ("geo_favorable_phase".equals(phase)) {
				return interpolatedDistance(1500000.0, 2900000.0, score, phaseScore);
			}
			if ("remote_congested_phase".equals(phase)) {
				return interpolatedDistance(1300000.0, 2600000.0, score, phaseScore);
			}
			return interpolatedDistance(1200000.0, 2300000.0, score, phaseScore);
		}
		if (action == ACTION_LOCAL) {
			return 0.0;
		}
		if (action == ACTION_NEIGHBOR) {
			return 600000.0 + 1800000.0 * score;
		}
		if (action == ACTION_GEO) {
			return 28000000.0 + 8000000.0 * score;
		}
		return 900000.0 + 3500000.0 * score;
	}

	private static double interpolatedDistance(double minDistance, double maxDistance, double score, double phaseScore) {
		double blend = Math.max(0.0, Math.min(1.0, 0.65 * score + 0.35 * phaseScore));
		return minDistance + (maxDistance - minDistance) * blend;
	}

	private static int controlledQueueLength(int action, double score, int baseQueue) {
		int tierBias = action == ACTION_LOCAL ? 1 : (action == ACTION_NEIGHBOR ? 2 : (action == ACTION_GEO ? 3 : 4));
		return Math.max(0, Math.max(baseQueue, tierBias + (int) Math.round(5.0 * score)));
	}

	private static double controlledComputeCapacity(int action, double score, double baseCapacity) {
		double base = Math.max(1.0, baseCapacity);
		double multiplier;
		if (action == ACTION_LOCAL) {
			multiplier = 1.00 + 0.15 * score;
		} else if (action == ACTION_NEIGHBOR) {
			multiplier = 0.85 + 0.30 * score;
		} else if (action == ACTION_GEO) {
			multiplier = 1.20 + 0.50 * score;
		} else {
			multiplier = 1.00 + 0.40 * score;
		}
		return base * multiplier;
	}

	private static double controlledRateMbps(int action, double score) {
		if (action == ACTION_LOCAL) {
			return 1000.0;
		}
		if (action == ACTION_NEIGHBOR) {
			return 220.0 + 480.0 * score;
		}
		if (action == ACTION_GEO) {
			return 90.0 + 180.0 * score;
		}
		return 130.0 + 260.0 * score;
	}

	private static double taskComputeIntensity(Task task) {
		double length = task == null ? 0.0 : Math.max(0.0, task.getLength());
		return Math.min(1.0, Math.log10(1.0 + length) / 7.0);
	}

	private static double taskDataIntensity(Task task) {
		double fileSize = task == null ? 0.0 : Math.max(0.0, task.getFileSize());
		return Math.min(1.0, Math.log10(1.0 + fileSize) / 7.0);
	}

	private static int preferredTier(String profile, double score, Task task) {
		double compute = taskComputeIntensity(task);
		double data = taskDataIntensity(task);
		if ("geo_favorable".equals(profile)) {
			return ACTION_GEO;
		}
		if ("neighbor_favorable".equals(profile)) {
			return ACTION_NEIGHBOR;
		}
		if ("local_favorable".equals(profile)) {
			return ACTION_LOCAL;
		}
		if ("ground_congested".equals(profile)) {
			return compute > 0.60 ? ACTION_GEO : ACTION_NEIGHBOR;
		}
		if ("remote_congested".equals(profile)) {
			return ACTION_LOCAL;
		}
		if ("mixed_cost_landscape".equals(profile)) {
			int bucket = (int) Math.floor(score * 4.0);
			if (bucket <= 0) {
				return ACTION_LOCAL;
			}
			if (bucket == 1) {
				return ACTION_NEIGHBOR;
			}
			if (bucket == 2) {
				return ACTION_GEO;
			}
			return ACTION_GROUND;
		}
		return ACTION_GROUND;
	}

	private static int profileQueueLength(String profile, ControlledScenarioDescriptor descriptor, int action, double score, double phaseScore, int baseQueue, Task task) {
		if ("mixed_cost_landscape_v2".equals(profile)) {
			String phase = descriptor.scenarioPhase;
			int minQueue = 0;
			int maxQueue = 4;
			if ("local_favorable_phase".equals(phase)) {
				if (action == ACTION_LOCAL) {
					minQueue = 0;
					maxQueue = 2;
				} else if (action == ACTION_NEIGHBOR) {
					minQueue = 2;
					maxQueue = 6;
				} else if (action == ACTION_GEO) {
					minQueue = 5;
					maxQueue = 10;
				} else {
					minQueue = 4;
					maxQueue = 9;
				}
			} else if ("neighbor_favorable_phase".equals(phase)) {
				if (action == ACTION_LOCAL) {
					minQueue = 2;
					maxQueue = 5;
				} else if (action == ACTION_NEIGHBOR) {
					minQueue = 0;
					maxQueue = 4;
				} else if (action == ACTION_GEO) {
					minQueue = 3;
					maxQueue = 7;
				} else {
					minQueue = 3;
					maxQueue = 6;
				}
			} else if ("geo_favorable_phase".equals(phase)) {
				if (action == ACTION_LOCAL) {
					minQueue = 2;
					maxQueue = 5;
				} else if (action == ACTION_NEIGHBOR) {
					minQueue = 3;
					maxQueue = 6;
				} else if (action == ACTION_GEO) {
					minQueue = 1;
					maxQueue = 7;
				} else {
					minQueue = 1;
					maxQueue = 5;
				}
			} else if ("ground_favorable_phase".equals(phase)) {
				if (action == ACTION_LOCAL) {
					minQueue = 2;
					maxQueue = 5;
				} else if (action == ACTION_NEIGHBOR) {
					minQueue = 3;
					maxQueue = 6;
				} else if (action == ACTION_GEO) {
					minQueue = 1;
					maxQueue = 5;
				} else {
					minQueue = 0;
					maxQueue = 5;
				}
			} else if ("remote_congested_phase".equals(phase)) {
				if (action == ACTION_LOCAL) {
					minQueue = 1;
					maxQueue = 4;
				} else if (action == ACTION_NEIGHBOR) {
					minQueue = 8;
					maxQueue = 14;
				} else if (action == ACTION_GEO) {
					minQueue = 10;
					maxQueue = 16;
				} else {
					minQueue = 9;
					maxQueue = 15;
				}
			} else {
				if (action == ACTION_LOCAL) {
					minQueue = 2;
					maxQueue = 6;
				} else if (action == ACTION_NEIGHBOR) {
					minQueue = 2;
					maxQueue = 7;
				} else if (action == ACTION_GEO) {
					minQueue = 3;
					maxQueue = 8;
				} else {
					minQueue = 2;
					maxQueue = 7;
				}
			}
			double blend = Math.max(0.0, Math.min(1.0, 0.55 * score + 0.45 * phaseScore));
			int dynamic = minQueue + (int) Math.round((maxQueue - minQueue) * blend);
			int actualComponent = (int) Math.round(Math.min(maxQueue + 3, Math.max(0, baseQueue)) * (action == ACTION_LOCAL ? 0.70 : 0.45));
			return Math.max(0, Math.min(maxQueue + 6, dynamic + actualComponent));
		}
		int queue = controlledQueueLength(action, score, baseQueue);
		int preferred = preferredTier(profile, score, task);
		if ("mixed_cost_landscape".equals(profile)) {
			if (action == preferred) {
				int reduction = action == ACTION_GEO ? 7 : (action == ACTION_GROUND ? 7 : 5);
				return Math.max(0, queue - reduction);
			}
			if (action == ACTION_GROUND && preferred != ACTION_GROUND) {
				return queue + 5;
			}
			if (action == ACTION_GEO && preferred == ACTION_LOCAL) {
				return queue + 4;
			}
			if (action == ACTION_LOCAL && preferred != ACTION_LOCAL) {
				return queue + 3;
			}
			if (action == ACTION_NEIGHBOR && preferred == ACTION_GEO) {
				return queue + 4;
			}
			if (action == ACTION_NEIGHBOR && preferred == ACTION_GROUND) {
				return queue + 4;
			}
			return queue + 1;
		}
		if ("ground_congested".equals(profile) && action == ACTION_GROUND) {
			return queue + 7;
		}
		if ("remote_congested".equals(profile) && action != ACTION_LOCAL) {
			return queue + 6;
		}
		if ("local_favorable".equals(profile) && action == ACTION_LOCAL) {
			return Math.max(0, queue - 2);
		}
		if ("neighbor_favorable".equals(profile) && action == ACTION_NEIGHBOR) {
			return Math.max(0, queue - 2);
		}
		if ("geo_favorable".equals(profile) && action == ACTION_GEO) {
			return Math.max(0, queue - 2);
		}
		return queue;
	}

	private static double profileComputeCapacity(String profile, ControlledScenarioDescriptor descriptor, int action, double score, double baseCapacity, Task task) {
		if ("mixed_cost_landscape_v2".equals(profile)) {
			double jitter = 0.92 + 0.16 * score;
			double base;
			if (action == ACTION_LOCAL) {
				base = 1.18;
			} else if (action == ACTION_NEIGHBOR) {
				base = 1.28;
			} else if (action == ACTION_GEO) {
				base = 1.92;
			} else {
				base = 1.58;
			}
			String phase = descriptor.scenarioPhase;
			double phaseMultiplier = 1.0;
			if ("local_favorable_phase".equals(phase)) {
				phaseMultiplier = action == ACTION_LOCAL ? 1.45 : (action == ACTION_NEIGHBOR ? 1.02 : 0.92);
			} else if ("neighbor_favorable_phase".equals(phase)) {
				phaseMultiplier = action == ACTION_NEIGHBOR ? 1.18 : (action == ACTION_LOCAL ? 1.00 : 0.98);
			} else if ("geo_favorable_phase".equals(phase)) {
				phaseMultiplier = action == ACTION_GEO ? 1.05 : (action == ACTION_GROUND ? 1.06 : 1.00);
			} else if ("ground_favorable_phase".equals(phase)) {
				phaseMultiplier = action == ACTION_GROUND ? 1.06 : (action == ACTION_GEO ? 1.04 : 1.00);
			} else if ("remote_congested_phase".equals(phase)) {
				phaseMultiplier = action == ACTION_LOCAL ? 1.08 : 0.84;
			} else {
				phaseMultiplier = action == ACTION_GEO ? 1.02 : (action == ACTION_GROUND ? 1.02 : 1.00);
			}
			double compute = taskComputeIntensity(task);
			double computeShape = 1.0 + 0.12 * compute * (action == ACTION_GEO ? 1.0 : (action == ACTION_GROUND ? 0.6 : 0.3));
			return Math.max(0.55, base * phaseMultiplier * jitter * computeShape);
		}
		double capacity = controlledComputeCapacity(action, score, baseCapacity);
		int preferred = preferredTier(profile, score, task);
		double compute = taskComputeIntensity(task);
		if ("mixed_cost_landscape".equals(profile)) {
			if (action == preferred) {
				if (action == ACTION_GEO) {
					return capacity * (1.42 + 0.38 * compute);
				}
				if (action == ACTION_GROUND) {
					return capacity * (1.38 + 0.22 * compute);
				}
				return capacity * (1.28 + 0.30 * compute);
			}
			if (action == ACTION_GROUND && preferred != ACTION_GROUND) {
				return capacity * 0.76;
			}
			if (action == ACTION_LOCAL && preferred == ACTION_LOCAL) {
				return capacity * 1.30;
			}
			if (action == ACTION_NEIGHBOR && preferred == ACTION_GEO) {
				return capacity * 0.86;
			}
			return capacity * 0.90;
		}
		if ("ground_congested".equals(profile) && action == ACTION_GROUND) {
			return capacity * 0.75;
		}
		if ("neighbor_favorable".equals(profile) && action == ACTION_NEIGHBOR) {
			return capacity * 1.08;
		}
		if ("geo_favorable".equals(profile) && action == ACTION_GEO) {
			return capacity * 1.15;
		}
		if ("local_favorable".equals(profile) && action == ACTION_LOCAL) {
			return capacity * 1.10;
		}
		return capacity;
	}

	private static double profileRateMbps(String profile, ControlledScenarioDescriptor descriptor, int action, double score, Task task) {
		if ("mixed_cost_landscape_v2".equals(profile)) {
			double jitter = 0.90 + 0.18 * score;
			double base;
			if (action == ACTION_LOCAL) {
				base = 980.0;
			} else if (action == ACTION_NEIGHBOR) {
				base = 430.0;
			} else if (action == ACTION_GEO) {
				base = 255.0;
			} else {
				base = 365.0;
			}
			String phase = descriptor.scenarioPhase;
			double phaseMultiplier = 1.0;
			if ("local_favorable_phase".equals(phase)) {
				if (action == ACTION_NEIGHBOR) {
					phaseMultiplier = 0.92;
				} else if (action == ACTION_GEO) {
					phaseMultiplier = 0.78;
				} else if (action == ACTION_GROUND) {
					phaseMultiplier = 0.84;
				}
			} else if ("neighbor_favorable_phase".equals(phase)) {
				phaseMultiplier = action == ACTION_NEIGHBOR ? 1.26 : (action == ACTION_GEO ? 0.92 : (action == ACTION_GROUND ? 0.96 : 1.0));
			} else if ("geo_favorable_phase".equals(phase)) {
				phaseMultiplier = action == ACTION_GEO ? 1.01 : (action == ACTION_GROUND ? 1.00 : 1.0);
			} else if ("ground_favorable_phase".equals(phase)) {
				phaseMultiplier = action == ACTION_GROUND ? 1.04 : (action == ACTION_GEO ? 0.99 : 1.0);
			} else if ("remote_congested_phase".equals(phase)) {
				phaseMultiplier = action == ACTION_LOCAL ? 1.0 : 0.72;
			} else {
				phaseMultiplier = action == ACTION_NEIGHBOR ? 1.04 : (action == ACTION_GROUND ? 1.06 : (action == ACTION_GEO ? 0.98 : 1.0));
			}
			double data = taskDataIntensity(task);
			double dataShape = 1.0;
			if (action == ACTION_NEIGHBOR) {
				dataShape = 1.0 + 0.08 * (1.0 - data);
			} else if (action == ACTION_GEO) {
				dataShape = 1.0 - 0.04 * data;
			} else if (action == ACTION_GROUND) {
				dataShape = 1.0 + 0.10 * data;
			}
			return Math.max(10.0, base * phaseMultiplier * jitter * dataShape);
		}
		double rate = controlledRateMbps(action, score);
		int preferred = preferredTier(profile, score, task);
		double data = taskDataIntensity(task);
		if ("mixed_cost_landscape".equals(profile)) {
			if (action == preferred) {
				if (action == ACTION_GEO) {
					return rate * (1.32 + 0.18 * (1.0 - data));
				}
				if (action == ACTION_GROUND) {
					return rate * (1.20 + 0.18 * data);
				}
				return rate * (1.25 + 0.20 * (1.0 - data));
			}
			if (action == ACTION_GROUND && preferred != ACTION_GROUND) {
				return rate * 0.70;
			}
			if (action == ACTION_LOCAL && preferred == ACTION_LOCAL) {
				return rate * 1.18;
			}
			if (action == ACTION_GEO && preferred == ACTION_GEO) {
				return rate * 1.24;
			}
			if (action == ACTION_NEIGHBOR && preferred == ACTION_GEO) {
				return rate * 0.82;
			}
			return rate * 0.88;
		}
		if ("ground_congested".equals(profile) && action == ACTION_GROUND) {
			return rate * 0.65;
		}
		if ("remote_congested".equals(profile) && action != ACTION_LOCAL) {
			return rate * 0.72;
		}
		if ("neighbor_favorable".equals(profile) && action == ACTION_NEIGHBOR) {
			return rate * 1.15;
		}
		if ("geo_favorable".equals(profile) && action == ACTION_GEO) {
			return rate * 1.12;
		}
		return rate;
	}

	private static double normalizedScore(Task task, DataCenter source, DataCenter destination, long salt) {
		long sourceId = source == null ? 0L : source.getDeviceID();
		long destinationId = destination == null ? 0L : destination.getId();
		long taskId = task == null ? 0L : task.getId();
		long mixed = mix64(
				taskId * 6364136223846793005L
				+ sourceId * 1442695040888963407L
				+ destinationId * 2862933555777941757L
				+ simulationParameters.RL_SERVER_SEED
				+ salt);
		long positive = mixed & Long.MAX_VALUE;
		return (positive % 1000000L) / 1000000.0;
	}

	private static long mix64(long z) {
		z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
		z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L;
		return z ^ (z >>> 33);
	}

	public static int determineAbstractAction(DataCenter destination, DataCenter source) {
		if (destination == null) {
			return -1;
		}
		if (destination.getType() == simulationParameters.TYPES.CLOUD) {
			return ACTION_GEO;
		}
		if (destination.getType() == simulationParameters.TYPES.EDGE_DATACENTER) {
			return ACTION_GROUND;
		}
		if (destination.getType() == simulationParameters.TYPES.EDGE_DEVICE) {
			return source != null && destination.getId() == source.getId() ? ACTION_LOCAL : ACTION_NEIGHBOR;
		}
		return -1;
	}

	public static String logicalTierFromAction(int action) {
		if (action == ACTION_LOCAL) {
			return "LOCAL";
		}
		if (action == ACTION_NEIGHBOR) {
			return "NEIGHBOR";
		}
		if (action == ACTION_GEO) {
			return "GEO";
		}
		if (action == ACTION_GROUND) {
			return "GROUND";
		}
		return "UNKNOWN";
	}

	public static String abstractActionName(int action) {
		if (action == ACTION_LOCAL) {
			return "local";
		}
		if (action == ACTION_NEIGHBOR) {
			return "neighbor";
		}
		if (action == ACTION_GEO) {
			return "geo";
		}
		if (action == ACTION_GROUND) {
			return "ground";
		}
		return "unknown";
	}

	private static boolean architectureAllows(String[] architecture, int abstractAction) {
		if (abstractAction == ACTION_LOCAL || abstractAction == ACTION_NEIGHBOR) {
			return containsArchitecture(architecture, "Mist");
		}
		if (abstractAction == ACTION_GEO) {
			return containsArchitecture(architecture, "Cloud");
		}
		if (abstractAction == ACTION_GROUND) {
			return containsArchitecture(architecture, "Edge");
		}
		return false;
	}

	private static boolean containsArchitecture(String[] architecture, String value) {
		if (architecture == null) {
			return false;
		}
		for (String item : architecture) {
			if (value.equals(item)) {
				return true;
			}
		}
		return false;
	}

	private static int tierRange(DataCenter destination) {
		if (destination.getType() == simulationParameters.TYPES.CLOUD) {
			return simulationParameters.CLOUD_RANGE;
		}
		if (destination.getType() == simulationParameters.TYPES.EDGE_DATACENTER) {
			return simulationParameters.EDGE_DATACENTERS_RANGE;
		}
		return simulationParameters.EDGE_DEVICES_RANGE;
	}

	//{A && B && [C ||(D && E)]} || {A && B && [C ||(D && E)]} || {A && B && [C ||(D && E) && F]}

	public abstract void resultsReturned(Task task);

}
