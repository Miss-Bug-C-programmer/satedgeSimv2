package edu.weijunyong.satedgesim.TasksGenerator;

import org.cloudbus.cloudsim.cloudlets.Cloudlet;
import org.cloudbus.cloudsim.cloudlets.CloudletSimple;

import edu.weijunyong.satedgesim.DataCentersManager.DataCenter;

public class Task extends CloudletSimple {
	private double offloadingTime;
	private double taskfinishTime;
	private double maxLatency;
	private DataCenter device;
	private long containerSize;
	private DataCenter orchestrator;
	private double receptionTime = -1; // the time when the task, or the corresponding container has been received by
										// the offloading destination
	private DataCenter registry;
	private int applicationID;
	private Status failureReason;

	public static enum Status {
		FAILED_DUE_TO_LATENCY, FAILED_BECAUSE_DEVICE_DEAD, FAILED_DUE_TO_DEVICE_MOBILITY,
		NOT_GENERATED_BECAUSE_DEVICE_DEAD, FAILED_NO_RESSOURCES, FAILED_DUE_TO_CONTACT_INTERRUPTION, NULL
	}
	private boolean contactInterrupted = false;
	private double contactInterruptionTime = -1.0;
	private double contactRemainingBytes = 0.0;
	private String contactFailureReason = "none";

	public Task(int id, long cloudletLength, long pesNumber) {
		super(id, cloudletLength, pesNumber);
	}

	public void setTime(double time) {
		this.offloadingTime = time;
	}

	public double getTime() {
		return offloadingTime;
	}
	
	public void setTaskFinishTime(double time) {
		this.taskfinishTime = time;
	}

	public double getTaskFinishTime() {
		return taskfinishTime;
	}

	public double getMaxLatency() {
		return maxLatency;
	}

	public void setMaxLatency(double maxLatency) {
		this.maxLatency = maxLatency;
	}

	public DataCenter getEdgeDevice() {
		return device;
	}

	public void setEdgeDevice(DataCenter dev) {
		this.device = dev;
	}

	public void setContainerSize(long containerSize) {
		this.containerSize = containerSize;
	}

	public long getContainerSize() {
		return containerSize;
	}

	public void setOrchestrator(DataCenter orch) {
		this.orchestrator = orch;
	}

	public DataCenter getOrchestrator() {
		return orchestrator;
	}

	public double getReceptionTime() {
		return receptionTime;
	}

	public void setReceptionTime(double time) {
		receptionTime = time;
	}

	public DataCenter getRegistry() {
		return registry;
	}

	public void setRegistry(DataCenter registry) {
		this.registry = registry;
	}

	public int getApplicationID() {
		return applicationID;
	}

	public void setApplicationID(int applicationID) {
		this.applicationID = applicationID;
	}

	public Status getFailureReason() {
		return failureReason;
	}

	public void setFailureReason(Status status) {
		this.setStatus(Cloudlet.Status.FAILED);
		this.failureReason = status;
	}

	public boolean isContactInterrupted() {
		return contactInterrupted;
	}

	public void setContactInterrupted(boolean contactInterrupted) {
		this.contactInterrupted = contactInterrupted;
	}

	public double getContactInterruptionTime() {
		return contactInterruptionTime;
	}

	public void setContactInterruptionTime(double contactInterruptionTime) {
		this.contactInterruptionTime = contactInterruptionTime;
	}

	public double getContactRemainingBytes() {
		return contactRemainingBytes;
	}

	public void setContactRemainingBytes(double contactRemainingBytes) {
		this.contactRemainingBytes = Math.max(0.0, contactRemainingBytes);
	}

	public String getContactFailureReason() {
		return contactFailureReason;
	}

	public void setContactFailureReason(String contactFailureReason) {
		this.contactFailureReason = contactFailureReason == null ? "none" : contactFailureReason;
	}

}
