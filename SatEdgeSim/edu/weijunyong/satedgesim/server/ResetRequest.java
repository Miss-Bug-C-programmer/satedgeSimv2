package edu.weijunyong.satedgesim.server;

/** Request body for /reset. All fields are optional. */
public class ResetRequest {
    public int devicesCount = -1;
    public int algorithmIndex = 0;
    public int architectureIndex = 0;
    public long seed = 0L;
    public String scenarioProfile = "default";
    public String taskSourceMode = "current";
    public String successProfile = "default";
    public String actionMaskMode = "visible_only";
    public Double minLinkSurvivalMarginSec = null;
    public Integer maxDecisions = null;
    public Double simulationTimeMinutes = null;
    public Integer tasksGenerationRate = null;
    public Boolean waitForAllTasks = null;
    public boolean cleanOutputFolder = false;
    /** Fail closed on unsupported physical reconfiguration by default. */
    public boolean strictPhysicalClaims = true;
    public boolean waitForFirstDecision = true;
    public long waitTimeoutMs = 30000L;
}
