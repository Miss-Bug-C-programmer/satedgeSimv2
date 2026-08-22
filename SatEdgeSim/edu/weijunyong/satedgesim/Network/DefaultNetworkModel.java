package edu.weijunyong.satedgesim.Network;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.cloudbus.cloudsim.core.events.SimEvent;

import edu.weijunyong.satedgesim.DataCentersManager.DataCenter;
import edu.weijunyong.satedgesim.DataCentersManager.DefaultEnergyModel;
import edu.weijunyong.satedgesim.ScenarioManager.simulationParameters;
import edu.weijunyong.satedgesim.SimulationManager.SimLog;
import edu.weijunyong.satedgesim.SimulationManager.SimulationManager;
import edu.weijunyong.satedgesim.TasksGenerator.Task;
import edu.weijunyong.satedgesim.Topology.ContactForecast;
import edu.weijunyong.satedgesim.Topology.TopologyOracle;
import edu.weijunyong.satedgesim.server.RlNativeResourceBindingManager;

public class DefaultNetworkModel extends NetworkModel {
	private long transferSequence = 0L;
	private final List<Map<String, Object>> transferEvidence = new ArrayList<Map<String, Object>>();
	private final List<Map<String, Object>> contactInterruptionEvidence = new ArrayList<Map<String, Object>>();
	private Map<String, Object> lastBandwidthConservationEvidence = new LinkedHashMap<String, Object>();
	private long bandwidthObservationCount = 0L;

	public DefaultNetworkModel(SimulationManager simulationManager) {
		super(simulationManager);
	}

	@Override
	public void processEvent(SimEvent ev) {
		switch (ev.getTag()) {
		case SEND_REQUEST_FROM_DEVICE_TO_ORCH:
			// Send the offloading request to the orchestrator
			sendRequestFromDeviceToOrch((Task) ev.getData());
			break;
		case SEND_REQUEST_FROM_ORCH_TO_DESTINATION:
			// Forward the offloading request from orchestrator to offloading destination
			sendRequestFromOrchToDest((Task) ev.getData());
			break;
		case DOWNLOAD_CONTAINER:
			// Pull the container from the registry
			addContainer((Task) ev.getData());
			break;
		case SEND_RESULT_TO_ORCH:
			// Send the execution results to the orchestrator
			sendResultFromDevToOrch((Task) ev.getData());
			break;
		case SEND_RESULT_FROM_ORCH_TO_DEV:
			// Transfer the execution results from the orchestrators to the device
			sendResultFromOrchToDev((Task) ev.getData());
			break;
		case UPDATE_PROGRESS:
			// update the progress of the current transfers and their allocated bandwidth
			updateTasksProgress();
			schedule(this, simulationParameters.NETWORK_UPDATE_INTERVAL, UPDATE_PROGRESS);
			break;
		default:
			break;
		}
	}

	public List<FileTransferProgress> getTransferProgressList() {
		return transferProgressList;
	}

	@Override
	public List<Map<String, Object>> getTransferEvidence() {
		return new ArrayList<Map<String, Object>>(transferEvidence);
	}

	@Override
	public List<Map<String, Object>> getContactInterruptionEvidence() {
		return new ArrayList<Map<String, Object>>(contactInterruptionEvidence);
	}

	@Override
	public Map<String, Object> getBandwidthConservationEvidence() {
		return new LinkedHashMap<String, Object>(lastBandwidthConservationEvidence);
	}

	private FileTransferProgress newTransfer(Task task, double remainingFileSize, FileTransferProgress.Type type) {
		FileTransferProgress transfer = new FileTransferProgress(task, remainingFileSize, type);
		transfer.setTransferId(++transferSequence);
		transfer.setStartedAtSec(simulationManager.getSimulation().clock());
		DataCenter source = transferSource(transfer);
		DataCenter destination = transferDestination(transfer);
		String sourceId = endpointIdentifier(source);
		String destinationId = endpointIdentifier(destination);
		transfer.setSourceIdentifier(sourceId);
		transfer.setDestinationIdentifier(destinationId);
		transfer.setContactIdentifier(sourceId + "->" + destinationId);
		RlNativeResourceBindingManager.attachToTransfer(transfer);
		initializeContactState(transfer);
		return transfer;
	}

	private static String endpointIdentifier(DataCenter dataCenter) {
		return dataCenter == null ? "unknown" : dataCenter.getClass().getSimpleName() + ":" + dataCenter.getId();
	}

	public void sendRequestFromOrchToDest(Task task) {
		transferProgressList
				.add(newTransfer(task, task.getFileSize() * 8, FileTransferProgress.Type.TASK));
	}

	public void sendResultFromOrchToDev(Task task) {
		if (task.getOrchestrator() != task.getEdgeDevice())
			transferProgressList.add(
					newTransfer(task, task.getOutputSize() * 8, FileTransferProgress.Type.RESULTS_TO_DEV));
		else
			scheduleNow(simulationManager, SimulationManager.RESULT_RETURN_FINISHED, task);
	}

	public void sendResultFromDevToOrch(Task task) {
		//if (task.getOrchestrator() != task.getEdgeDevice())
		if (task.getOrchestrator() != (DataCenter)task.getVm().getHost().getDatacenter())
			transferProgressList.add(newTransfer(task, task.getOutputSize() * 8,
					FileTransferProgress.Type.RESULTS_TO_ORCH));
		else
			scheduleNow(this, DefaultNetworkModel.SEND_RESULT_FROM_ORCH_TO_DEV, task);
	}

	public void addContainer(Task task) {
		transferProgressList
				.add(newTransfer(task, task.getContainerSize() * 8, FileTransferProgress.Type.CONTAINER));
	}

	public void sendRequestFromDeviceToOrch(Task task) {
		if (task.getOrchestrator() != task.getEdgeDevice())  //协调器非本设备
			transferProgressList
					.add(newTransfer(task, task.getFileSize() * 8, FileTransferProgress.Type.REQUEST));
		else // The device orchestrate its tasks by itself, so, send the request directly to
				// destination
			scheduleNow(simulationManager, SimulationManager.SEND_TASK_FROM_ORCH_TO_DESTINATION, task);
	}

	protected void updateTasksProgress() {
		List<FileTransferProgress> active = new ArrayList<FileTransferProgress>();
		for (FileTransferProgress transfer : new ArrayList<FileTransferProgress>(transferProgressList)) {
			if (transfer == null || transfer.getRemainingFileSize() <= 0.0) continue;
			if (contactClosed(transfer)) {
				failTransferForContact(transfer);
				continue;
			}
			active.add(transfer);
		}

		List<List<FileTransferProgress>> lanGroups = buildLanGroups(active);
		List<FileTransferProgress> wanGroup = new ArrayList<FileTransferProgress>();
		for (FileTransferProgress transfer : active) {
			if (wanIsUsed(transfer)) wanGroup.add(transfer);
		}
		Map<String, Double> lanTotals = new LinkedHashMap<String, Double>();
		Map<String, Double> wanTotals = new LinkedHashMap<String, Double>();
		for (int groupIndex = 0; groupIndex < lanGroups.size(); groupIndex++) {
			List<FileTransferProgress> group = lanGroups.get(groupIndex);
			String groupId = "lan-domain-" + groupIndex;
			double total = 0.0;
			for (FileTransferProgress transfer : group) total += requestedBandwidthWeight(transfer);
			lanTotals.put(groupId, Double.valueOf(total));
		}
		if (!wanGroup.isEmpty()) {
			double total = 0.0;
			for (FileTransferProgress transfer : wanGroup) total += requestedBandwidthWeight(transfer);
			wanTotals.put("wan-global", Double.valueOf(total));
		}

		for (FileTransferProgress transfer : active) {
			List<FileTransferProgress> lanGroup = findLanGroup(lanGroups, transfer);
			String lanGroupId = "lan-domain-" + lanGroups.indexOf(lanGroup);
			double lanTotal = lanTotals.get(lanGroupId).doubleValue();
			double effectiveLan = weightedCapacity(simulationParameters.BANDWIDTH_WLAN,
				requestedBandwidthWeight(transfer), lanTotal);
			double effectiveWan = wanIsUsed(transfer)
				? weightedCapacity(simulationParameters.WAN_BANDWIDTH,
						requestedBandwidthWeight(transfer), wanTotals.get("wan-global").doubleValue())
				: 0.0;
			transfer.setLanBandwidth(effectiveLan);
			transfer.setWanBandwidth(effectiveWan);
			transfer.recordEffectiveAllocation(effectiveLan, effectiveWan,
					simulationParameters.BANDWIDTH_WLAN, simulationParameters.WAN_BANDWIDTH,
					lanGroup.size(), wanGroup.size(), lanGroupId, wanIsUsed(transfer) ? "wan-global" : "not-used",
					simulationManager.getSimulation().clock());
			updateBandwidth(transfer);
		}

		if (!active.isEmpty()) {
			lastBandwidthConservationEvidence = buildBandwidthConservationEvidence(active, lanGroups, wanGroup);
			lastBandwidthConservationEvidence.put("simulationTimeSec", simulationManager.getSimulation().clock());
			bandwidthObservationCount += 1L;
			lastBandwidthConservationEvidence.put("observationCount", bandwidthObservationCount);
		}
		for (FileTransferProgress transfer : active) updateTransfer(transfer);
	}

	static double requestedBandwidthWeight(FileTransferProgress transfer) {
		return Math.max(0.10, Math.min(1.0, transfer.getBandwidthShareClamped()));
	}

	static double weightedCapacity(double capacity, double weight, double totalWeight) {
		if (capacity <= 0.0 || totalWeight <= 0.0) return 0.0;
		return capacity * weight / totalWeight;
	}

	private List<List<FileTransferProgress>> buildLanGroups(List<FileTransferProgress> active) {
		List<List<FileTransferProgress>> groups = new ArrayList<List<FileTransferProgress>>();
		for (FileTransferProgress transfer : active) {
			List<List<FileTransferProgress>> matchingGroups = new ArrayList<List<FileTransferProgress>>();
			for (List<FileTransferProgress> group : groups) {
				for (FileTransferProgress member : group) {
					if (sameLanIsUsed(transfer.getTask(), member.getTask())) {
						matchingGroups.add(group);
						break;
					}
				}
			}
			if (matchingGroups.isEmpty()) {
				List<FileTransferProgress> group = new ArrayList<FileTransferProgress>();
				group.add(transfer);
				groups.add(group);
				continue;
			}
			List<FileTransferProgress> matching = matchingGroups.get(0);
			for (int index = 1; index < matchingGroups.size(); index++) {
				List<FileTransferProgress> merged = matchingGroups.get(index);
				matching.addAll(merged);
				groups.remove(merged);
			}
			matching.add(transfer);
		}
		return groups;
	}

	private static List<FileTransferProgress> findLanGroup(List<List<FileTransferProgress>> groups,
			FileTransferProgress transfer) {
		for (List<FileTransferProgress> group : groups) {
			if (group.contains(transfer)) return group;
		}
		return new ArrayList<FileTransferProgress>();
	}

	private static Map<String, Object> buildBandwidthConservationEvidence(List<FileTransferProgress> active,
			List<List<FileTransferProgress>> lanGroups, List<FileTransferProgress> wanGroup) {
		Map<String, Object> evidence = new LinkedHashMap<String, Object>();
		boolean satisfied = true;
		List<Map<String, Object>> groups = new ArrayList<Map<String, Object>>();
		for (int index = 0; index < lanGroups.size(); index++) {
			List<FileTransferProgress> group = lanGroups.get(index);
			double sum = 0.0;
			for (FileTransferProgress transfer : group) sum += transfer.getEffectiveLanBandwidth();
			boolean ok = sum <= simulationParameters.BANDWIDTH_WLAN + 1.0e-9;
			satisfied = satisfied && ok;
			Map<String, Object> item = new LinkedHashMap<String, Object>();
			item.put("resource", "lan");
			item.put("group", "lan-domain-" + index);
			item.put("flowCount", group.size());
			item.put("capacity", simulationParameters.BANDWIDTH_WLAN);
			item.put("effectiveSum", sum);
			item.put("conserved", ok);
			groups.add(item);
		}
		if (!wanGroup.isEmpty()) {
			double sum = 0.0;
			for (FileTransferProgress transfer : wanGroup) sum += transfer.getEffectiveWanBandwidth();
			boolean ok = sum <= simulationParameters.WAN_BANDWIDTH + 1.0e-9;
			satisfied = satisfied && ok;
			Map<String, Object> item = new LinkedHashMap<String, Object>();
			item.put("resource", "wan_global");
			item.put("group", "wan-global");
			item.put("flowCount", wanGroup.size());
			item.put("capacity", simulationParameters.WAN_BANDWIDTH);
			item.put("effectiveSum", sum);
			item.put("conserved", ok);
			groups.add(item);
		}
		evidence.put("observed", !active.isEmpty());
		evidence.put("conservationSatisfied", satisfied);
		evidence.put("scope", "shared_lan_domain_and_global_wan");
		evidence.put("perLinkAllocationSupported", false);
		evidence.put("groups", groups);
		return evidence;
	}

	private void initializeContactState(FileTransferProgress transfer) {
		DataCenter source = transferSource(transfer);
		DataCenter destination = transferDestination(transfer);
		if (source == null || destination == null || source == destination) return;
		transfer.setContactRequired(true);
		if (simulationManager.getContactPlan() == null) return;
		try {
			ContactForecast forecast = simulationManager.getContactPlan().getContactForecast(
					TopologyOracle.toRef(source), TopologyOracle.toRef(destination),
					simulationManager.getSimulation().clock(),
					simulationParameters.TOPOLOGY_FORECAST_HORIZON_SEC);
			if (forecast.availableNow && forecast.currentContactEndSec != null
					&& !forecast.remainingLifetimeCensored) {
				transfer.setContactEvidenceAvailable(true);
				transfer.setContactEndSec(forecast.currentContactEndSec.doubleValue());
			}
		} catch (RuntimeException unavailable) {
			// A required contact without physical evidence is fail-closed below.
		}
	}

	private boolean contactClosed(FileTransferProgress transfer) {
		return ContactEnforcementPolicy.shouldInterrupt(transfer.isContactRequired(),
				transfer.isContactEvidenceAvailable(), simulationManager.getSimulation().clock(),
				transfer.getContactEndSec());
	}

	private void failTransferForContact(FileTransferProgress transfer) {
		double now = simulationManager.getSimulation().clock();
		String reason = ContactEnforcementPolicy.failureReason(transfer.isContactEvidenceAvailable());
		boolean qualifying = transfer.isContactEvidenceAvailable()
				&& ContactEnforcementPolicy.isQualifyingMidTransfer(
						transfer.getTransferredFileSize(), transfer.getRemainingFileSize());
		String action = qualifying ? "fail_task_after_partial_transfer" : "fail_task_before_positive_progress";
		transfer.setContactInterrupted(true);
		transfer.setContactInterruptionTime(now);
		transfer.setContactFailureReason(reason);
		transfer.setContactInterruptionQualified(qualifying);
		transfer.setPostInterruptionAction(action);
		Task task = transfer.getTask();
		if (task != null) {
			task.setContactInterrupted(true);
			task.setContactInterruptionTime(now);
			task.setContactRemainingBytes(transfer.getRemainingFileSize());
			task.setContactFailureReason(reason);
			task.setContactInterruptionQualified(qualifying);
			task.setContactTransferredBytes(transfer.getTransferredBytes());
			task.setFailureReason(Task.Status.FAILED_DUE_TO_CONTACT_INTERRUPTION);
		}
		Map<String, Object> evidence = transfer.toRuntimeEvidence("FAILED", action, now);
		transferEvidence.add(evidence);
		if (qualifying) contactInterruptionEvidence.add(evidence);
		trimEvidence();
		transferProgressList.remove(transfer);
		if (task != null) simulationManager.failTaskDueToContact(task);
	}

	private void trimEvidence() {
		while (transferEvidence.size() > 4096) transferEvidence.remove(0);
		while (contactInterruptionEvidence.size() > 1024) contactInterruptionEvidence.remove(0);
	}

	private static DataCenter transferSource(FileTransferProgress transfer) {
		Task task = transfer.getTask();
		if (task == null) return null;
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
		if (task == null) return null;
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
		if (task == null || task.getVm() == null || task.getVm().getHost() == null
				|| !(task.getVm().getHost().getDatacenter() instanceof DataCenter)) return null;
		return (DataCenter) task.getVm().getHost().getDatacenter();
	}

	protected void updateTransfer(FileTransferProgress transfer) {

		double oldRemainingSize = transfer.getRemainingFileSize();
		double bandwidth = Math.max(0.0, transfer.getCurrentBandwidth());
		double progress = Math.min(oldRemainingSize,
				simulationParameters.NETWORK_UPDATE_INTERVAL * bandwidth);
		if (progress <= 0.0) return;

		// Update progress (remaining file size)
		transfer.setRemainingFileSize(oldRemainingSize - progress);

		// Update LAN network usage delay
		transfer.setLanNetworkUsage(transfer.getLanNetworkUsage()
				+ progress / bandwidth);

		// Update WAN network usage delay
		if (wanIsUsed(transfer))
			transfer.setWanNetworkUsage(transfer.getWanNetworkUsage()
					+ progress / bandwidth);
		if (transfer.getRemainingFileSize() <= 0) {// Transfer finished
			transfer.setRemainingFileSize(0);
			transferFinished(transfer);
		}
	}

	protected void updateEnergyConsumption(FileTransferProgress transfer, String type) {
		// update energy consumption
		if ("Orchestrator".equals(type)) {
			calculateEnergyConsumption(transfer.getTask().getEdgeDevice(), transfer.getTask().getOrchestrator(),
					transfer);
		} else if ("Destination".equals(type)) {
			calculateEnergyConsumption(transfer.getTask().getOrchestrator(),
					((DataCenter) transfer.getTask().getVm().getHost().getDatacenter()), transfer);
		} else if ("Container".equals(type)) {
			// update the energy consumption of the registry and the device
			calculateEnergyConsumption(transfer.getTask().getRegistry(),
					transfer.getTask().getEdgeDevice(), transfer);
		} else if ("Result_Orchestrator".equals(type)) {
			calculateEnergyConsumption(((DataCenter) transfer.getTask().getVm().getHost().getDatacenter()),
					transfer.getTask().getOrchestrator(), transfer);
		} else if ("Result_Origin".equals(type)) {
			calculateEnergyConsumption(transfer.getTask().getOrchestrator(), transfer.getTask().getEdgeDevice(),
					transfer);
		}

	}

	private void calculateEnergyConsumption(DataCenter origin, DataCenter destination,
			FileTransferProgress transfer) {
		if (origin != null) {
			origin.getEnergyModel().updatewirelessEnergyConsumption(transfer, origin, destination,
					DefaultEnergyModel.TRANSMISSION);
		}
		destination.getEnergyModel().updatewirelessEnergyConsumption(transfer, origin, destination,
				DefaultEnergyModel.RECEPTION);
	}

	protected void transferFinished(FileTransferProgress transfer) {
		transferEvidence.add(transfer.toRuntimeEvidence("COMPLETED", "complete", simulationManager.getSimulation().clock()));
		trimEvidence();
		// Update logger parameters
		simulationManager.getSimulationLogger().updateNetworkUsage(transfer);

		// Delete the transfer from the queue
		transferProgressList.remove(transfer);

		// If it is an offlaoding request that is sent to the orchestrator
		if (transfer.getTransferType() == FileTransferProgress.Type.REQUEST) {
			offloadingRequestRecievedByOrchestrator(transfer);
			//transfer.getTask().getEdgeDevice(), transfer.getTask().getOrchestrator()
			updateEnergyConsumption(transfer, "Orchestrator");
		}
		// If it is an task (or offloading request) that is sent to the destination
		else if (transfer.getTransferType() == FileTransferProgress.Type.TASK) {
			transfer.getTask().setReceptionTime(simulationManager.getSimulation().clock());
			executeTaskOrDownloadContainer(transfer);
			//transfer.getTask().getOrchestrator(),((DataCenter) transfer.getTask().getVm().getHost().getDatacenter())
			updateEnergyConsumption(transfer, "Destination");
		}
		// If the container has been downloaded, then execute the task now
		else if (transfer.getTransferType() == FileTransferProgress.Type.CONTAINER) { 
			transfer.getTask().setReceptionTime(simulationManager.getSimulation().clock());
			containerDownloadFinished(transfer);
			//transfer.getTask().getRegistry(),transfer.getTask().getEdgeDevice()
			updateEnergyConsumption(transfer, "Container");
		}
		// If the transfer of execution results to the orchestrator has finished
		else if (transfer.getTransferType() == FileTransferProgress.Type.RESULTS_TO_ORCH) {
			returnResultToDevice(transfer);
			//(DataCenter) transfer.getTask().getVm().getHost().getDatacenter()),transfer.getTask().getOrchestrator()
			updateEnergyConsumption(transfer, "Result_Orchestrator");
		}
		// Results transferred to the device
		else {		//transfer.getTransferType() == FileTransferProgress.Type.RESULTS_TO_DEV
			resultsReturnedToDevice(transfer);
			//transfer.getTask().getOrchestrator(), transfer.getTask().getEdgeDevice()
			updateEnergyConsumption(transfer, "Result_Origin");
		}

	}

	protected void containerDownloadFinished(FileTransferProgress transfer) {
		scheduleNow(simulationManager, SimulationManager.EXECUTE_TASK, transfer.getTask());
	}

	protected void resultsReturnedToDevice(FileTransferProgress transfer) {
		// if the results are returned from different location, consider the wan propagation delay
		if (transfer.getTask().getOrchestrator() != transfer.getTask().getEdgeDevice()) {
			double WAN_PROPAGATION_DELAY = Getpropagationdelay(transfer.getTask().getOrchestrator()
					,transfer.getTask().getEdgeDevice());
			schedule(simulationManager, WAN_PROPAGATION_DELAY, SimulationManager.RESULT_RETURN_FINISHED,
					transfer.getTask());
		}
		else
			scheduleNow(simulationManager, SimulationManager.RESULT_RETURN_FINISHED, transfer.getTask());
	}

	protected void returnResultToDevice(FileTransferProgress transfer) {
		// if the results are returned from different location, consider the wan propagation delay
		if (transfer.getTask().getOrchestrator() != ((DataCenter) transfer.getTask().getVm().getHost().getDatacenter())) {
			double WAN_PROPAGATION_DELAY = Getpropagationdelay((DataCenter) transfer.getTask().getVm().getHost().getDatacenter()
					, transfer.getTask().getOrchestrator());
			schedule(this, WAN_PROPAGATION_DELAY, DefaultNetworkModel.SEND_RESULT_FROM_ORCH_TO_DEV,
					transfer.getTask());
		}
		else
			scheduleNow(this, DefaultNetworkModel.SEND_RESULT_FROM_ORCH_TO_DEV, transfer.getTask());
	}

	protected void executeTaskOrDownloadContainer(FileTransferProgress transfer) {
		//get the orchestration deploy
		simulationParameters.TYPES type = null;
		if ("".equals(simulationParameters.registry_mode)
				|| ("CLOUD".equals(simulationParameters.registry_mode))) {
			type = simulationParameters.TYPES.CLOUD;
		} else if ("EDGE".equals(simulationParameters.registry_mode)) {
			type = simulationParameters.TYPES.EDGE_DATACENTER;
		} else if ("MIST".equals(simulationParameters.registry_mode)) {
			type = simulationParameters.TYPES.EDGE_DEVICE;
		} else {	//simulationParameters.registry_mode 可以继续添加自定义类型
			SimLog.println("");
			SimLog.println("SimulationManager- Unknnown orchestration deploy '" + simulationParameters.DEPLOY_ORCHESTRATOR
					+ "', please check the simulation parameters file...");
			// Cancel the simulation
			simulationParameters.abort("SatEdgeSim requested termination");
		}
		double WAN_PROPAGATION_DELAY_TASK = Getpropagationdelay(transfer.getTask().getOrchestrator()
				,((DataCenter) transfer.getTask().getVm().getHost().getDatacenter()));
		if (simulationParameters.ENABLE_REGISTRY 
				&& !((DataCenter) transfer.getTask().getVm().getHost().getDatacenter()).getType().equals(type)){
			// if the registry is enabled and the node where task offloaded(Type) is different with the registry_mode(Type), 
			//then download the container
			if (((DataCenter) transfer.getTask().getVm().getHost().getDatacenter()) != transfer.getTask().getOrchestrator()
					&& ((DataCenter) transfer.getTask().getVm().getHost().getDatacenter()) != transfer.getTask().getEdgeDevice()) {
				//find the closest registry_mode
				double min = -1;
				int selected = 0;
				double distance;
				for (int i = 0; i < datacentersList.size(); i++) {
					if (datacentersList.get(i).getType() == type 
							&& SimulationManager.issetlink((DataCenter) transfer.getTask().getVm().getHost().getDatacenter(),datacentersList.get(i))) {
						distance = SimulationManager.getdistance((DataCenter) transfer.getTask().getVm().getHost().getDatacenter(),datacentersList.get(i));
						if (min == -1 || min > distance) {
							min = distance;
							selected = i;
						}
					}
				}
				transfer.getTask().setRegistry(datacentersList.get(selected));
				double WAN_PROPAGATION_DELAY_DOWNLOAD_CONTAINER = Getpropagationdelay((DataCenter) transfer.getTask().getVm().getHost().getDatacenter()
						,transfer.getTask().getRegistry());
				double WAN_PROPAGATION_DELAY = WAN_PROPAGATION_DELAY_DOWNLOAD_CONTAINER + WAN_PROPAGATION_DELAY_TASK;
				schedule(this, WAN_PROPAGATION_DELAY, DefaultNetworkModel.DOWNLOAD_CONTAINER, transfer.getTask());
			}
			else {
				//scheduleNow(this, DefaultNetworkModel.DOWNLOAD_CONTAINER, transfer.getTask());
				//schedule(this, WAN_PROPAGATION_DELAY_TASK, DefaultNetworkModel.DOWNLOAD_CONTAINER, transfer.getTask());
				scheduleNow(simulationManager, SimulationManager.EXECUTE_TASK, transfer.getTask());
			}
		} 
		else {// if the registry is disabled, execute directly the request, as it represents
				// the offloaded task in this case
			//task.getEdgeDevice().getId() != task.getVm().getHost().getDatacenter().getId()
			if (((DataCenter) transfer.getTask().getVm().getHost().getDatacenter()) != transfer.getTask().getOrchestrator()
					&& ((DataCenter) transfer.getTask().getVm().getHost().getDatacenter()) != transfer.getTask().getEdgeDevice()) {
				schedule(simulationManager, WAN_PROPAGATION_DELAY_TASK, SimulationManager.EXECUTE_TASK,
						transfer.getTask());
			}
			else
				scheduleNow(simulationManager, SimulationManager.EXECUTE_TASK, transfer.getTask());
		}
	}

	protected void offloadingRequestRecievedByOrchestrator(FileTransferProgress transfer) {
		// Find the offloading destination and execute the task
		if (transfer.getTask().getOrchestrator() != transfer.getTask().getEdgeDevice()) {
			double WAN_PROPAGATION_DELAY = Getpropagationdelay(transfer.getTask().getEdgeDevice()
					, transfer.getTask().getOrchestrator());
			schedule(simulationManager, WAN_PROPAGATION_DELAY,
					SimulationManager.SEND_TASK_FROM_ORCH_TO_DESTINATION, transfer.getTask());
		}
		else
			scheduleNow(simulationManager, SimulationManager.SEND_TASK_FROM_ORCH_TO_DESTINATION, transfer.getTask());
	}

	@Override
	protected void startEntity() {
		schedule(this, 1, UPDATE_PROGRESS);
	}
	
	public double Getpropagationdelay(DataCenter origin, DataCenter destination) { //计算传播时间
		double distance = SimulationManager.getdistance(origin,destination);
		double propagationdelay = distance / simulationParameters.WAN_PROPAGATION_SPEED;
		return propagationdelay;
	}

}
