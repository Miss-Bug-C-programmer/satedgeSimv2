package edu.weijunyong.satedgesim.server;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded, decision-time monitor DTO.  This class deliberately contains no
 * candidate VM list and is built without invoking RlStateBuilder.
 */
public final class CheapMonitorState {
    public String contractVersion = "2.1";
    public String payloadKind = "cheap_monitor";
    public String status = "RUNNING";
    public String sessionId;
    public double simulationTimeSec;
    public String currentConfigId;
    public long currentConfigVersion;
    /** Null means that no active configuration has been applied in this session. */
    public Double configurationAgeSec;
    /** Current scheduler-derived observation; it is not a future guarantee. */
    public Double serviceRateObserved;
    /** Non-null only when a separately proven conservative lower bound exists. */
    public Double serviceRateLowerBound;
    public boolean serviceBoundCertified = false;
    public String serviceRateSource = "unavailable_at_cheap_monitor_cost";
    public String serviceBoundSemantics = "not_certified";
    public String serviceHorizonSource = "unavailable_at_cheap_monitor_cost";
    public String serviceEvidenceStatus = "UNAVAILABLE";
    public boolean serviceEvidenceApplicable = true;
    /** Null means that a service horizon was not observable at monitor cost. */
    public Double serviceHorizonSec;
    public long currentDecisionId = -1L;
    public long currentTaskId = -1L;
    public int sourceDeviceId = -1;
    public Map<String, Double> queueSummary = new LinkedHashMap<String, Double>();
    public Map<String, Double> loadSummary = new LinkedHashMap<String, Double>();
    public Map<String, Double> remainingWorkload = new LinkedHashMap<String, Double>();
    public Map<String, Double> deadlineSlack = new LinkedHashMap<String, Double>();
    public Map<String, Double> remainingContactLifetime = new LinkedHashMap<String, Double>();
    public Map<String, Object> nextContact = new LinkedHashMap<String, Object>();
    public Map<String, Double> contactSlack = new LinkedHashMap<String, Double>();
    public String contactEvidenceStatus = "UNAVAILABLE";
    public boolean contactApplicabilityKnown = false;
    public boolean contactEvidenceRequired = false;
    public String deadlineEvidenceStatus = "UNAVAILABLE";
    public boolean deadlineEvidenceApplicable = true;
    public boolean deadlineEvidenceAvailable = false;
    public String uncertaintyEvidenceStatus = "UNAVAILABLE";
    public boolean uncertaintyEvidenceApplicable = true;
    public boolean uncertaintyEvidenceAvailable = false;
    public Double computeReadyWorkloadMi;
    public Double executingWorkloadMi;
    public Double waitingDispatchWorkloadMi;
    public Double networkRemainingBits;
    public boolean phaseStateUncertain = false;
    public Map<String, Object> smallNeighborhood = new LinkedHashMap<String, Object>();
    public Map<String, Object> cachedState = new LinkedHashMap<String, Object>();
    public Map<String, Double> predictionUncertainty = new LinkedHashMap<String, Double>();
    public Map<String, Double> degradationIndicators = new LinkedHashMap<String, Double>();
    public boolean containsFutureStochasticState = false;
    public Map<String, Object> instrumentation = new LinkedHashMap<String, Object>();

    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("contractVersion", contractVersion);
        result.put("payloadKind", payloadKind);
        result.put("status", status);
        result.put("sessionId", sessionId);
        result.put("simulationTimeSec", simulationTimeSec);
        result.put("configId", currentConfigId);
        result.put("configVersion", currentConfigVersion);
        result.put("configurationAgeSec", configurationAgeSec);
        result.put("serviceRateObserved", serviceRateObserved);
        result.put("serviceRateLowerBound", serviceRateLowerBound);
        result.put("serviceBoundCertified", serviceBoundCertified);
        result.put("serviceRateSource", serviceRateSource);
        result.put("serviceBoundSemantics", serviceBoundSemantics);
        result.put("serviceHorizonSource", serviceHorizonSource);
        result.put("serviceEvidenceStatus", serviceEvidenceStatus);
        result.put("serviceEvidenceApplicable", serviceEvidenceApplicable);
        result.put("serviceHorizonSec", serviceHorizonSec);
        result.put("currentDecisionId", currentDecisionId);
        result.put("currentTaskId", currentTaskId);
        result.put("sourceDeviceId", sourceDeviceId);
        result.put("queueSummary", queueSummary);
        result.put("loadSummary", loadSummary);
        result.put("remainingWorkload", remainingWorkload);
        result.put("deadlineSlack", deadlineSlack);
        result.put("remainingContactLifetime", remainingContactLifetime);
        result.put("nextContact", nextContact);
        result.put("contactSlack", contactSlack);
        result.put("contactEvidenceStatus", contactEvidenceStatus);
        result.put("contactApplicabilityKnown", contactApplicabilityKnown);
        result.put("contactEvidenceRequired", contactEvidenceRequired);
        result.put("deadlineEvidenceStatus", deadlineEvidenceStatus);
        result.put("deadlineEvidenceApplicable", deadlineEvidenceApplicable);
        result.put("deadlineEvidenceAvailable", deadlineEvidenceAvailable);
        result.put("uncertaintyEvidenceStatus", uncertaintyEvidenceStatus);
        result.put("uncertaintyEvidenceApplicable", uncertaintyEvidenceApplicable);
        result.put("uncertaintyEvidenceAvailable", uncertaintyEvidenceAvailable);
        result.put("computeReadyWorkloadMi", computeReadyWorkloadMi);
        result.put("executingWorkloadMi", executingWorkloadMi);
        result.put("waitingDispatchWorkloadMi", waitingDispatchWorkloadMi);
        result.put("networkRemainingBits", networkRemainingBits);
        result.put("phaseStateUncertain", phaseStateUncertain);
        result.put("smallNeighborhood", smallNeighborhood);
        result.put("cachedState", cachedState);
        result.put("predictionUncertainty", predictionUncertainty);
        result.put("degradationIndicators", degradationIndicators);
        result.put("containsFutureStochasticState", containsFutureStochasticState);
        result.put("instrumentation", instrumentation);
        return result;
    }
}
