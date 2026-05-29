package edu.weijunyong.satedgesim.server;

import java.util.HashMap;
import java.util.Map;

/**
 * Action sent by the Python RL controller.
 *
 * targetVmIndex is the main discrete offloading decision consumed by SatEdgeSim.
 * The continuous fields are accepted so that a MAPPO + MADDPG controller can keep
 * one unified action schema. They are exposed in the server logs/state and can be
 * wired to a custom CPU/network model later without changing the REST contract.
 */
public class RlAction {
    public long decisionId = -1L;
    public long taskId = -1L;
    public long requestId = -1L;
    public int targetVmIndex = -1;
    public long targetVmId = -1L;
    public long selectedVmId = -1L;
    public int policyUpperAction = -1;
    public String policyUpperActionName = "";
    public int abstractAction = -1;
    public String abstractActionName = "";
    public double cpuShare = 1.0;
    public double bandwidthShare = 1.0;
    public double txPowerRatio = 1.0;
    public double queuePriority = 1.0;
    public Map<String, Object> extra = new HashMap<String, Object>();
}
