# SatEdgeSim Long-Running RL Server

This patch adds a long-running REST server wrapper around SatEdgeSim so that a Python RL controller can drive task-orchestration decisions:

```text
Python RL controller  <---- REST/JSON ---->  SatEdgeSim Java simulation core
```

Implemented endpoints:

| Endpoint | Method | Purpose |
|---|---|---|
| `/reset` | POST | Start a new SatEdgeSim session and block until the first RL decision point or finish. |
| `/get_state` | GET | Return the current decision state, candidate VMs, action mask, topology/device snapshot, and live metrics. |
| `/step` | POST | Submit one RL action and release the blocked simulation thread until the next decision point. |
| `/get_metrics` | GET | Return current aggregate metrics from `SimLog`. |
| `/close` | POST | Close the active session. |
| `/health` | GET | Server health check. |
| `/capabilities` | GET | Contract v2 capability declaration. |
| `/get_monitor_state` | GET | Bounded cheap monitor with zero candidate evaluation. |
| `/get_planner_state` | POST | Unified scoped/budgeted planner acquisition. |
| `/get_planner_state` | GET | Full-state compatibility route. |
| `/topology/current` | GET | Current physical topology snapshot. |
| `/topology/contact_plan` | POST | Deterministic contact forecast. |
| `/configuration/current` | GET | Active reusable configuration. |
| `/configuration/validate` | POST | Version, target, contact and resource validation. |
| `/configuration/apply` | POST | Apply reusable selector rules. |
| `/configuration/dispatch` | POST | Dispatch a pending task under an active rule. |
| `/advance_world` | POST | CloudSim physical-time advancement with before/after receipt. |
| `/debug/decision_plane_stats` | GET | Cheap/scoped/full acquisition instrumentation. |

## Design

CloudSim/SatEdgeSim is not manually advanced one tick at a time. Instead, the simulation runs in a Java background thread and blocks inside `ExternalRLOrchestrator.findVM(...)` whenever a task needs an offloading target. Python calls `/get_state`, computes an action, then calls `/step`. This keeps the original SatEdgeSim task lifecycle, network model, mobility model, and result logging intact.

Contract v2 adds a native scoped planner builder, reusable persistent execution
rules, and CloudSim `pause(target)` based physical delay. `/advance_world`
returns a verified before/target clock receipt and resumes the simulation after
the receipt; it rejects calls made while an external decision is still pending.
Mid-transfer contact enforcement is intentionally advertised as unsupported
until the transfer path has a verified interruption receipt.

## Start server

```bash
mvn -DskipTests compile
mvn -DskipTests compile exec:java \
  -Dexec.mainClass=edu.weijunyong.satedgesim.server.SatEdgeSimRestServer \
  -Dexec.args="--port 8088"
```

or:

```bash
bash scripts/run_rl_server.sh 8088
```

## Minimal Python client

```bash
pip install requests
python examples/python_client/rl_rest_client.py --base-url http://127.0.0.1:8088
```

## `/reset` example

```bash
curl -X POST http://127.0.0.1:8088/reset \
  -H 'Content-Type: application/json' \
  -d '{"devicesCount": 20, "algorithmIndex": 0, "architectureIndex": 0, "waitForFirstDecision": true}'
```

## `/step` example

```bash
curl -X POST http://127.0.0.1:8088/step \
  -H 'Content-Type: application/json' \
  -d '{"action":{"requestId":1,"targetVmIndex":0,"cpuShare":1.0,"bandwidthShare":1.0,"txPowerRatio":1.0,"queuePriority":1.0},"waitTimeoutMs":30000}'
```

## Notes for MAPPO + MADDPG

- MAPPO should consume `candidateVms`, `datacenters`, `task`, and `actionMask` and output `targetVmIndex`.
- MADDPG can send `cpuShare`, `bandwidthShare`, `txPowerRatio`, and `queuePriority` through the same `/step` schema.
- In this patch, SatEdgeSim's native VM/network schedulers still control actual CPU and bandwidth service. The continuous fields are preserved in the API contract so that a later custom resource model can bind them to VM MIPS, link bandwidth, or power allocation without changing Python-side code.

## Files added

```text
SatEdgeSim/edu/weijunyong/satedgesim/server/SatEdgeSimRestServer.java
SatEdgeSim/edu/weijunyong/satedgesim/server/SatEdgeSimSession.java
SatEdgeSim/edu/weijunyong/satedgesim/server/RlDecisionBridge.java
SatEdgeSim/edu/weijunyong/satedgesim/server/RlDecisionBridgeRegistry.java
SatEdgeSim/edu/weijunyong/satedgesim/server/RlState.java
SatEdgeSim/edu/weijunyong/satedgesim/server/RlStateBuilder.java
SatEdgeSim/edu/weijunyong/satedgesim/server/RlAction.java
SatEdgeSim/edu/weijunyong/satedgesim/server/ResetRequest.java
SatEdgeSim/edu/weijunyong/satedgesim/server/ServerConfig.java
SatEdgeSim/edu/weijunyong/satedgesim/TasksOrchestration/ExternalRLOrchestrator.java
examples/python_client/rl_rest_client.py
scripts/run_rl_server.sh
```

## Files modified

```text
SatEdgeSim/edu/weijunyong/satedgesim/SimulationManager/SimLog.java
```

`SimLog.java` now exposes `getMetricsSnapshot()` and no longer calls `Runtime.exit(0)` when `save_log_file=false`, which is required for server mode.

## TriSatFlow alignment fields

This aligned version exposes a stable four-action abstraction for external MARL controllers:

```text
0 = local LEO / source EDGE_DEVICE
1 = neighboring LEO / non-source feasible EDGE_DEVICE
2 = GEO / CLOUD
3 = ground gateway / EDGE_DATACENTER
```

`GET /get_state` now returns top-level:

```json
"abstractActionMask": [1, 1, 1, 1],
"abstractActionNames": ["local", "neighbor", "geo", "ground"]
```

Each `candidateVms[]` entry includes:

```json
{
  "logicalTier": "LOCAL|NEIGHBOR|GEO|GROUND",
  "abstractAction": 0,
  "isLocalToSource": true,
  "isRemoteToSource": false,
  "linkAvailable": true,
  "sourceDistance": 0.0,
  "propagationDelaySec": 0.0,
  "estimatedTransmissionRateMbps": 1000.0,
  "estimatedTransmissionDelaySec": 0.0,
  "estimatedComputeCapacity": 47000.0,
  "estimatedComputeDelaySec": 0.02,
  "estimatedQueueLength": 3
}
```

The external controller should select a concrete `targetVmIndex` from `candidateVms`.  TriSatFlow maps its abstract action to a feasible VM by matching `abstractAction` first, then falls back only for legacy server states.  Fallbacks should be reported in replay logs; non-zero fallback rates indicate interface or scenario mismatch.
