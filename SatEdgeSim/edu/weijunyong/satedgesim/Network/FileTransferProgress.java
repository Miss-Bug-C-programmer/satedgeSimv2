package edu.weijunyong.satedgesim.Network;

import java.util.LinkedHashMap;
import java.util.Map;

import edu.weijunyong.satedgesim.TasksGenerator.Task;

public class FileTransferProgress {
	public static enum Type {
		TASK, CONTAINER, RESULTS_TO_DEV, RESULTS_TO_ORCH, REQUEST
	} 
	private Task task;
	private double remainingFileSize; // in kbits
	private double wanBandwidth;// kbits/s
	private double lanBandwidth;// kbits/s
	private double wanNetworkUsage = 0; // seconds
	private double lanNetworkUsage = 0; // seconds
	private Type transferType;
	private double fileSize; // in kbits
	private double currentBandwidth; // kbits/s
	private double totalBandwidths=0; // kbits/s
	private int bwAllocationTimes=0;
	private double bandwidthShareRequested = 1.0;
	private double bandwidthShareValidated = 1.0;
	private double bandwidthShareClamped = 1.0;
	private double txPowerRatioRequested = 1.0;
	private double txPowerRatioValidated = 1.0;
	private double txPowerRatioClamped = 1.0;
	private boolean nativeNetworkBound = false;
	private boolean nativeTxPowerBound = false;
	private boolean contactRequired = false;
	private boolean contactEvidenceAvailable = false;
	private double contactEndSec = Double.NaN;
	private boolean contactInterrupted = false;
	private double contactInterruptionTime = Double.NaN;
	private String contactFailureReason = "none";
	private long transferId = -1L;
	private double startedAtSec = Double.NaN;
	private String sourceIdentifier = "unknown";
	private String destinationIdentifier = "unknown";
	private String contactIdentifier = "unknown";
	private double transferredFileSize = 0.0; // in kbits, derived from native remaining-size progression
	private boolean contactInterruptionQualified = false;
	private String postInterruptionAction = "none";
	private double effectiveLanBandwidth = 0.0;
	private double effectiveWanBandwidth = 0.0;
	private double lanCapacity = 0.0;
	private double wanCapacity = 0.0;
	private int lanContentionCount = 0;
	private int wanContentionCount = 0;
	private String lanContentionGroup = "unobserved";
	private String wanContentionGroup = "unobserved";
	private long allocationObservationCount = 0L;
	private double lastAllocationTimeSec = Double.NaN;

	public FileTransferProgress(Task task, double remainingFileSize, Type type) {
		this.task = task;
		this.remainingFileSize = remainingFileSize;
		this.fileSize = remainingFileSize;
		this.transferType = type;
	}

	public double getRemainingFileSize() {
		return remainingFileSize;
	}

	public void setRemainingFileSize(double remainingFileSize) {
		double next = Math.max(0.0, remainingFileSize);
		if (next < this.remainingFileSize) {
			this.transferredFileSize += this.remainingFileSize - next;
		}
		this.remainingFileSize = next;
	}

	public double getTransferredFileSize() {
		return Math.max(0.0, Math.min(fileSize, transferredFileSize));
	}

	public Task getTask() {
		return task;
	}

	public double getWanBandwidth() {
		return wanBandwidth;
	}

	public void setWanBandwidth(double wanBandwidth) {
		this.wanBandwidth = wanBandwidth;
	}

	public double getLanBandwidth() {
		return lanBandwidth;
	}

	public void setLanBandwidth(double lanBandwidth) {
		this.lanBandwidth = lanBandwidth;
	}

	public double getWanNetworkUsage() {
		return wanNetworkUsage;
	}

	public void setWanNetworkUsage(double wanNetworkUsage) {
		this.wanNetworkUsage = wanNetworkUsage;
	}

	public double getLanNetworkUsage() {
		return lanNetworkUsage;
	}

	public void setLanNetworkUsage(double lanNetworkUsage) {
		this.lanNetworkUsage = lanNetworkUsage;
	}

	public Type getTransferType() {
		return transferType;
	}

	public double getFileSize() {
		return fileSize;
	}

	public void setCurrentBandwidth(double bandwidth) {
		this.currentBandwidth = bandwidth;
		
		//these values used to get the average bandwidth
		totalBandwidths+=bandwidth;
		bwAllocationTimes++;
	}

	public double getCurrentBandwidth() {
		return currentBandwidth;
	}
	
	public double getAverageBandwidth() {
		return bwAllocationTimes == 0 ? 0.0 : totalBandwidths/bwAllocationTimes;
	}

	public long getTransferId() {
		return transferId;
	}

	public void setTransferId(long transferId) {
		this.transferId = transferId;
	}

	public double getStartedAtSec() {
		return startedAtSec;
	}

	public void setStartedAtSec(double startedAtSec) {
		this.startedAtSec = startedAtSec;
	}

	public String getSourceIdentifier() {
		return sourceIdentifier;
	}

	public void setSourceIdentifier(String sourceIdentifier) {
		this.sourceIdentifier = sourceIdentifier == null ? "unknown" : sourceIdentifier;
	}

	public String getDestinationIdentifier() {
		return destinationIdentifier;
	}

	public void setDestinationIdentifier(String destinationIdentifier) {
		this.destinationIdentifier = destinationIdentifier == null ? "unknown" : destinationIdentifier;
	}

	public String getContactIdentifier() {
		return contactIdentifier;
	}

	public void setContactIdentifier(String contactIdentifier) {
		this.contactIdentifier = contactIdentifier == null ? "unknown" : contactIdentifier;
	}

	public double getBandwidthShareClamped() {
		return bandwidthShareClamped;
	}

	public double getBandwidthShareRequested() {
		return bandwidthShareRequested;
	}

	public double getBandwidthShareValidated() {
		return bandwidthShareValidated;
	}

	public void setBandwidthShareClamped(double bandwidthShareClamped) {
		this.bandwidthShareClamped = clampShare(bandwidthShareClamped);
		this.bandwidthShareValidated = this.bandwidthShareClamped;
	}

	public void setBandwidthShareProfile(double requested, double validated) {
		this.bandwidthShareRequested = requested;
		this.bandwidthShareValidated = clampShare(validated);
		this.bandwidthShareClamped = this.bandwidthShareValidated;
	}

	public double getTxPowerRatioClamped() {
		return txPowerRatioClamped;
	}

	public double getTxPowerRatioRequested() {
		return txPowerRatioRequested;
	}

	public double getTxPowerRatioValidated() {
		return txPowerRatioValidated;
	}

	public void setTxPowerRatioClamped(double txPowerRatioClamped) {
		this.txPowerRatioClamped = clampShare(txPowerRatioClamped);
		this.txPowerRatioValidated = this.txPowerRatioClamped;
	}

	public void setTxPowerRatioProfile(double requested, double validated) {
		this.txPowerRatioRequested = requested;
		this.txPowerRatioValidated = clampShare(validated);
		this.txPowerRatioClamped = this.txPowerRatioValidated;
	}

	public boolean isNativeNetworkBound() {
		return nativeNetworkBound;
	}

	public void setNativeNetworkBound(boolean nativeNetworkBound) {
		this.nativeNetworkBound = nativeNetworkBound;
	}

	public boolean isNativeTxPowerBound() {
		return nativeTxPowerBound;
	}

	public void setNativeTxPowerBound(boolean nativeTxPowerBound) {
		this.nativeTxPowerBound = nativeTxPowerBound;
	}

	public boolean isContactRequired() {
		return contactRequired;
	}

	public void setContactRequired(boolean contactRequired) {
		this.contactRequired = contactRequired;
	}

	public boolean isContactEvidenceAvailable() {
		return contactEvidenceAvailable;
	}

	public void setContactEvidenceAvailable(boolean contactEvidenceAvailable) {
		this.contactEvidenceAvailable = contactEvidenceAvailable;
	}

	public double getContactEndSec() {
		return contactEndSec;
	}

	public void setContactEndSec(double contactEndSec) {
		this.contactEndSec = contactEndSec;
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

	public String getContactFailureReason() {
		return contactFailureReason;
	}

	public void setContactFailureReason(String contactFailureReason) {
		this.contactFailureReason = contactFailureReason == null ? "none" : contactFailureReason;
	}

	public boolean isContactInterruptionQualified() {
		return contactInterruptionQualified;
	}

	public void setContactInterruptionQualified(boolean contactInterruptionQualified) {
		this.contactInterruptionQualified = contactInterruptionQualified;
	}

	public String getPostInterruptionAction() {
		return postInterruptionAction;
	}

	public void setPostInterruptionAction(String postInterruptionAction) {
		this.postInterruptionAction = postInterruptionAction == null ? "none" : postInterruptionAction;
	}

	public double getEffectiveLanBandwidth() {
		return effectiveLanBandwidth;
	}

	public double getEffectiveWanBandwidth() {
		return effectiveWanBandwidth;
	}

	public double getLanCapacity() {
		return lanCapacity;
	}

	public double getWanCapacity() {
		return wanCapacity;
	}

	public int getLanContentionCount() {
		return lanContentionCount;
	}

	public int getWanContentionCount() {
		return wanContentionCount;
	}

	public String getLanContentionGroup() {
		return lanContentionGroup;
	}

	public String getWanContentionGroup() {
		return wanContentionGroup;
	}

	public double getLastAllocationTimeSec() {
		return lastAllocationTimeSec;
	}

	public long getAllocationObservationCount() {
		return allocationObservationCount;
	}

	public void recordEffectiveAllocation(double effectiveLanBandwidth, double effectiveWanBandwidth,
			double lanCapacity, double wanCapacity, int lanContentionCount, int wanContentionCount,
			String lanContentionGroup, String wanContentionGroup) {
		recordEffectiveAllocation(effectiveLanBandwidth, effectiveWanBandwidth, lanCapacity, wanCapacity,
				lanContentionCount, wanContentionCount, lanContentionGroup, wanContentionGroup, Double.NaN);
	}

	public void recordEffectiveAllocation(double effectiveLanBandwidth, double effectiveWanBandwidth,
			double lanCapacity, double wanCapacity, int lanContentionCount, int wanContentionCount,
			String lanContentionGroup, String wanContentionGroup, double allocationTimeSec) {
		this.effectiveLanBandwidth = Math.max(0.0, effectiveLanBandwidth);
		this.effectiveWanBandwidth = Math.max(0.0, effectiveWanBandwidth);
		this.lanCapacity = Math.max(0.0, lanCapacity);
		this.wanCapacity = Math.max(0.0, wanCapacity);
		this.lanContentionCount = Math.max(0, lanContentionCount);
		this.wanContentionCount = Math.max(0, wanContentionCount);
		this.lanContentionGroup = lanContentionGroup == null ? "unknown" : lanContentionGroup;
		this.wanContentionGroup = wanContentionGroup == null ? "unknown" : wanContentionGroup;
		this.lastAllocationTimeSec = allocationTimeSec;
		this.allocationObservationCount += 1L;
	}

	/** Existing simulator units are kbits; expose an explicit SI-byte conversion for evidence. */
	public double getTotalBytes() {
		return Math.max(0.0, fileSize) * 125.0;
	}

	public double getTransferredBytes() {
		return getTransferredFileSize() * 125.0;
	}

	public double getRemainingBytes() {
		return getRemainingFileSize() * 125.0;
	}

	public Map<String, Object> toRuntimeEvidence(String terminalStatus, String terminalAction, double timestampSec) {
		Map<String, Object> out = new LinkedHashMap<String, Object>();
		out.put("transferId", transferId);
		out.put("taskId", task == null ? -1L : task.getId());
		out.put("transferType", transferType == null ? null : transferType.name());
		out.put("source", sourceIdentifier);
		out.put("destination", destinationIdentifier);
		out.put("contactIdentifier", contactIdentifier);
		out.put("startTimeSec", Double.isFinite(startedAtSec) ? startedAtSec : null);
		out.put("contactEndTimeSec", Double.isFinite(contactEndSec) ? contactEndSec : null);
		out.put("eventTimeSec", timestampSec);
		out.put("totalKbits", fileSize);
		out.put("transferredKbits", getTransferredFileSize());
		out.put("remainingKbits", getRemainingFileSize());
		out.put("totalBytes", getTotalBytes());
		out.put("bytesMovedBeforeInterruption", getTransferredBytes());
		out.put("remainingBytes", getRemainingBytes());
		out.put("wastedKbits", 0.0);
		out.put("failedKbits", 0.0);
		out.put("retriedKbits", 0.0);
		out.put("accountingBasis", "moved_plus_remaining_plus_explicit_terminal_amount");
		out.put("byteConversion", "1_kbit=125_SI_bytes");
		out.put("contactRequired", contactRequired);
		out.put("contactEvidenceAvailable", contactEvidenceAvailable);
		out.put("contactInterrupted", contactInterrupted);
		out.put("qualifyingMidTransferInterruption", contactInterruptionQualified);
		out.put("contact_interruption_native", contactInterruptionQualified);
		out.put("postInterruptionAction", terminalAction == null ? postInterruptionAction : terminalAction);
		out.put("terminalStatus", terminalStatus == null ? "ACTIVE" : terminalStatus);
		out.put("failureReason", contactFailureReason);
		out.put("nativeNetworkBound", nativeNetworkBound);
		out.put("requestedBandwidthShare", bandwidthShareValidated);
		out.put("rawRequestedBandwidthShare", bandwidthShareRequested);
		out.put("validatedRequestedBandwidthShare", bandwidthShareValidated);
		out.put("effectiveLanBandwidthShare", lanCapacity > 0.0 ? effectiveLanBandwidth / lanCapacity : null);
		out.put("effectiveWanBandwidthShare", wanCapacity > 0.0 ? effectiveWanBandwidth / wanCapacity : null);
		out.put("requestedTxPowerRatio", txPowerRatioValidated);
		out.put("rawRequestedTxPowerRatio", txPowerRatioRequested);
		out.put("validatedRequestedTxPowerRatio", txPowerRatioValidated);
		out.put("effectiveTxPowerRatio", txPowerRatioValidated);
		out.put("txPowerExecutionConsumer", nativeTxPowerBound ? "DefaultEnergyModel.wireless_transmission" : "unbound");
		out.put("effectiveLanBandwidth", effectiveLanBandwidth);
		out.put("effectiveWanBandwidth", effectiveWanBandwidth);
		out.put("lanCapacity", lanCapacity);
		out.put("wanCapacity", wanCapacity);
		out.put("lanContentionCount", lanContentionCount);
		out.put("wanContentionCount", wanContentionCount);
		out.put("lanContentionGroup", lanContentionGroup);
		out.put("wanContentionGroup", wanContentionGroup);
		out.put("allocationObservationCount", allocationObservationCount);
		out.put("allocationTimestampSec", Double.isFinite(lastAllocationTimeSec) ? lastAllocationTimeSec : null);
		return out;
	}

	private double clampShare(double value) {
		if (Double.isNaN(value) || Double.isInfinite(value)) {
			return 1.0;
		}
		return Math.max(0.10, Math.min(1.0, value));
	}

}
