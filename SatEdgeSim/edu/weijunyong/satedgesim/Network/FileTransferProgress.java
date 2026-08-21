package edu.weijunyong.satedgesim.Network;

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
	private double bandwidthShareClamped = 1.0;
	private double txPowerRatioClamped = 1.0;
	private boolean nativeNetworkBound = false;
	private boolean nativeTxPowerBound = false;
	private boolean contactRequired = false;
	private boolean contactEvidenceAvailable = false;
	private double contactEndSec = Double.NaN;
	private boolean contactInterrupted = false;
	private double contactInterruptionTime = Double.NaN;
	private String contactFailureReason = "none";

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
		this.remainingFileSize = remainingFileSize;
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
		return totalBandwidths/bwAllocationTimes;
	}

	public double getBandwidthShareClamped() {
		return bandwidthShareClamped;
	}

	public void setBandwidthShareClamped(double bandwidthShareClamped) {
		this.bandwidthShareClamped = clampShare(bandwidthShareClamped);
	}

	public double getTxPowerRatioClamped() {
		return txPowerRatioClamped;
	}

	public void setTxPowerRatioClamped(double txPowerRatioClamped) {
		this.txPowerRatioClamped = clampShare(txPowerRatioClamped);
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

	private double clampShare(double value) {
		if (Double.isNaN(value) || Double.isInfinite(value)) {
			return 1.0;
		}
		return Math.max(0.10, Math.min(1.0, value));
	}

}
