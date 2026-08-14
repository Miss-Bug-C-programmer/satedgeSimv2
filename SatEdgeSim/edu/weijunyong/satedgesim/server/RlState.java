package edu.weijunyong.satedgesim.server;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** State snapshot returned to the Python RL controller. */
public class RlState {
    public String sessionId;
    public String status;
    public String message;
    public long decisionId = -1L;
    public long requestId = -1L;
    public long taskId = -1L;
    public int sourceDeviceId = -1;
    public int sourceLeoId = -1;
    public double simulationTime = 0.0;
    public String scenarioProfile = "default";
    public String scenarioPhase = "default_phase";
    public String taskType = "unknown_task";
    public String trafficPhase = "default_traffic";
    public String costEstimatorVersion = "unknown";
    public String taskSourceMode = "current";
    public String actionMaskMode = "visible_only";
    public double minLinkSurvivalMarginSec = 0.0;
    public boolean isControlledRlScenario = false;
    public TaskView task;
    public List<VmView> candidateVms = new ArrayList<VmView>();
    /**
     * Per-VM feasibility mask consumed by targetVmIndex actions.  This is the
     * original SatEdgeSim action space.
     */
    public List<Integer> actionMask = new ArrayList<Integer>();
    /**
     * Four-action abstract mask aligned with TriSatFlow:
     * 0=local LEO/mist, 1=neighbor LEO/mist, 2=GEO/cloud, 3=ground/edge.
     */
    public List<Integer> abstractActionMask = new ArrayList<Integer>();
    public List<Integer> abstractActionMaskVisible = new ArrayList<Integer>();
    public List<Integer> abstractActionMaskMobilitySafe = new ArrayList<Integer>();
    public List<Integer> abstractActionMaskCompletionSafe = new ArrayList<Integer>();
    public List<String> abstractActionNames = Arrays.asList("local", "neighbor", "geo", "ground");
    public String denseCoverageMode = "none";
    public List<DenseSourceSummary> denseSourceSummaries = new ArrayList<DenseSourceSummary>();
    public List<DataCenterView> datacenters = new ArrayList<DataCenterView>();
    public String configurationViabilityMode = "report_only";
    public int viableCandidateCount;
    public int inviableCandidateCount;
    public int uncertainCandidateCount;
    public String viabilitySummarySource = "unavailable";
    public Map<String, Object> metrics;
    public Map<String, Object> lastDecision;

    public static class TaskView {
        public long id;
        public int applicationId;
        public long length;
        public long pesNumber;
        public long fileSize;
        public long outputSize;
        public double generatedTime;
        public double maxLatency;
        public int sourceDeviceId;
        public long sourceDatacenterId;
        public String sourceType;
        public String scenarioProfile = "default";
        public String scenarioPhase = "default_phase";
        public String taskType = "unknown_task";
        public String trafficPhase = "default_traffic";
        public String costEstimatorVersion = "unknown";
        public String taskSourceMode = "current";
        public boolean isControlledRlScenario = false;
    }

    public static class VmView {
        public String id;
        public int vmIndex;
        public long vmId;
        public long hostId;
        public double mips;
        public long pesNumber;
        public long ram;
        public long bw;
        public long size;
        public long datacenterId;
        public int datacenterDeviceId;
        public String datacenterType;
        /** Canonical tier label used by TriSatFlow replay and trace export. */
        public String logicalTier;
        /** 0=local, 1=neighbor, 2=geo, 3=ground; -1=unknown. */
        public int abstractAction = -1;
        public String abstractActionName;
        public boolean isLocalToSource;
        public boolean isRemoteToSource;
        public boolean linkAvailable;
        public boolean linkAvailableNow;
        public double estimatedLinkLifetimeSec;
        public String linkLifetimeSource;
        public boolean linkLifetimeCensored;
        public Double currentContactEndSec;
        public Double nextContactStartSec;
        public Double nextContactEndSec;
        public double contactForecastHorizonSec;
        public boolean contactForecastSufficient;
        public double distanceToSource;
        public double sourceDistance;
        public double propagationDelaySec;
        public double estimatedTransmissionRateMbps;
        public double estimatedTransmissionDelaySec;
        public double estimatedComputeCapacity;
        public double estimatedComputeDelaySec;
        public double estimatedQueueDelaySec;
        public double estimatedTotalDelaySec;
        public double estimatedTaskTransmissionTimeSec;
        public double estimatedTaskComputeTimeSec;
        public double estimatedTaskCompletionTimeSec;
        public double linkSurvivalMarginSec;
        public double linkSurvivalMarginToCompletionSec;
        public boolean handoverRequired;
        public boolean handoverAvailable;
        public double mobilityRisk;
        public String mobilityRiskSource = "unavailable";
        public boolean mobilitySafe;
        public boolean completionSafe;
        public String viabilityStatus;
        public String viabilityReason;
        public String viabilitySource;
        public boolean viabilityEvaluated;
        public boolean viabilityContactEndCensored;
        public double viabilityAvailableContactSec;
        public double viabilityRequiredContactSec;
        public double viabilityServiceMarginSec;
        public int estimatedQueueLength;
        public String queueEstimateSource = "unknown";
        public boolean isFeasible;
        public boolean feasible;
        public String infeasibleReason;
        public int assignedTasks;
        public double datacenterCpuUtilization;
        public double datacenterBatteryPercent;
        public boolean datacenterDead;
    }

    public static class DataCenterView {
        public long id;
        public int deviceId;
        public String type;
        /** LEO, GEO or GROUND after mapping SatEdgeSim resource classes. */
        public String logicalTier;
        public double x;
        public double y;
        public double z;
        public boolean mobile;
        public boolean batteryPowered;
        public double batteryLevel;
        public double batteryPercent;
        public boolean dead;
        public long ram;
        public long storage;
        public long availableStorage;
        public double currentCpuUtilization;
        public double averageCpuUtilization;
        public int vmCount;
        public boolean orchestrator;
    }

    public static class DenseSourceSummary {
        public int sourceDeviceId;
        public long sourceDatacenterId;
        public String sourceLogicalTier;
        public double simulationTime;
        public String scenarioProfile = "default";
        public String scenarioPhase = "default_phase";
        public String taskType = "unknown_task";
        public String trafficPhase = "default_traffic";
        public String taskSourceMode = "current";
        public boolean isControlledRlScenario = false;
        public boolean localVisible;
        public boolean neighborVisible;
        public boolean geoVisible;
        public boolean groundVisible;
        public boolean localMobilitySafe;
        public boolean neighborMobilitySafe;
        public boolean geoMobilitySafe;
        public boolean groundMobilitySafe;
        public boolean localCompletionSafe;
        public boolean neighborCompletionSafe;
        public boolean geoCompletionSafe;
        public boolean groundCompletionSafe;
        public double localRate;
        public double neighborRate;
        public double geoRate;
        public double groundRate;
        public int localCandidateCount;
        public int neighborCandidateCount;
        public int geoCandidateCount;
        public int groundCandidateCount;
        public Double neighborMinDistance;
        public Double geoMinDistance;
        public Double groundMinDistance;
        public Double localBestQueue;
        public Double neighborBestQueue;
        public Double geoBestQueue;
        public Double groundBestQueue;
        public Double localBestDelay;
        public Double neighborBestDelay;
        public Double geoBestDelay;
        public Double groundBestDelay;
        public Double localPropDelay;
        public Double neighborPropDelay;
        public Double geoPropDelay;
        public Double groundPropDelay;
        public Double localTxDelay;
        public Double neighborTxDelay;
        public Double geoTxDelay;
        public Double groundTxDelay;
        public Double localComputeDelay;
        public Double neighborComputeDelay;
        public Double geoComputeDelay;
        public Double groundComputeDelay;
        public Double localComputeCapacity;
        public Double neighborComputeCapacity;
        public Double geoComputeCapacity;
        public Double groundComputeCapacity;
        public Double localQueueDelay;
        public Double neighborQueueDelay;
        public Double geoQueueDelay;
        public Double groundQueueDelay;
        public Double localTotalDelay;
        public Double neighborTotalDelay;
        public Double geoTotalDelay;
        public Double groundTotalDelay;
        public String traceGenerationMode = "dense_projection";
        public String costEstimatorVersion = "unknown";
        public String queueEstimateSource = "unknown";
        public List<Integer> abstractActionMask = new ArrayList<Integer>();
        public List<Integer> abstractActionMaskVisible = new ArrayList<Integer>();
        public List<Integer> abstractActionMaskMobilitySafe = new ArrayList<Integer>();
        public List<Integer> abstractActionMaskCompletionSafe = new ArrayList<Integer>();
        public String actionMaskMode = "visible_only";
        public double minLinkSurvivalMarginSec = 0.0;
        public double localMobilityRiskMean = 0.0;
        public double neighborMobilityRiskMean = 0.0;
        public double geoMobilityRiskMean = 0.0;
        public double groundMobilityRiskMean = 0.0;
        public Double localBestLinkLifetimeSec;
        public Double neighborBestLinkLifetimeSec;
        public Double geoBestLinkLifetimeSec;
        public Double groundBestLinkLifetimeSec;
        public Double localBestLinkSurvivalMarginSec;
        public Double neighborBestLinkSurvivalMarginSec;
        public Double geoBestLinkSurvivalMarginSec;
        public Double groundBestLinkSurvivalMarginSec;
        public String mobilityRiskSource = "unavailable";
    }
}
