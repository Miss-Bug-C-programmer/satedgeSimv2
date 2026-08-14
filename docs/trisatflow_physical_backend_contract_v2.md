# TriSatFlow / SatEdgeSim Physical Backend Contract v2

SatEdgeSim is the authoritative physical backend. It owns CloudSim time,
mobility, contact availability, VM/resource feasibility, transfer execution,
task completion and energy accounting. TriSatFlow owns KEEP/REPLAN, scope,
fidelity, planning budgets, BenefitEstimator/VoC, prices, lifecycle and SMDP
logging.

## Formal routes

| Route | Semantics |
|---|---|
| `GET /capabilities` | Declared contract and semantic capabilities. |
| `GET /get_monitor_state` | Bounded cheap monitor; no candidate enumeration. |
| `POST /get_planner_state` | Unified request carrying `scope`, `budget`, and `fidelityHint`. |
| `GET /get_planner_state` | Legacy/full compatibility only. |
| `GET /topology/current` | Current physical topology snapshot. |
| `POST /topology/contact_plan` | Deterministic current/future contact forecast. |
| `POST /configuration/apply` | Applies reusable execution rules after control-plane validation. |
| `GET /configuration/current` | Active configuration and version. |
| `POST /configuration/validate` | Physical target/resource/version/contact validation. |
| `POST /configuration/dispatch` | Dispatches a current pending task under an active reusable rule. |
| `POST /advance_world` | Advances CloudSim through `pause(target)` and resumes after the receipt. |
| `GET /debug/decision_plane_stats` | Candidate/build instrumentation. |

The old scoped/budgeted planner routes are not part of the formal contract.

## Cheap monitor boundary

`CheapMonitorState` is independent of `RlStateBuilder`. It contains scalar
time/decision/task identifiers, aggregate queue/load counters, current config,
small-neighborhood/cache summaries and uncertainty/degradation summaries. Its
instrumentation explicitly reports:

```text
payloadKind=cheap_monitor
candidateEvaluations=0
fullStateBuilderInvoked=false
containsFutureStochasticState=false
vmEnumeration=aggregate_count_only
datacenterEnumeration=aggregate_count_only
```

## Planner acquisition

The POST route filters the VM list before invoking `RlStateBuilder.buildScoped`.
The scoped builder omits dense source summaries and the all-datacenter table;
candidate `vmIndex` values are remapped to the authoritative original action
indices. Receipts expose requested/applied scope and budget, before/after
counts, `budgetAppliedDuringAcquisition=true`, `postFilterOnly=false`, and
`scopedPlannerCandidateEvaluations`.

## Persistent configuration

`PersistentExecutionConfiguration` supports exact task overrides and reusable
selector rules for source, application, traffic, flow, node, route, resource
and default dimensions. `ExternalRLOrchestrator` resolves an active rule before
requesting a new RL decision. A rule that matches later tasks therefore does
not require a list of future task IDs. Unavailable targets are not silently
relabelled as successful execution.

## Physical time and known boundary

`/advance_world` uses CloudSim's public `pause(target)` API and returns the
actual before/target time, then resumes the simulation. It never mutates the
clock directly. The old configuration remains active during the advance;
TriSatFlow validates and applies the new configuration only after the receipt.
An advance request is rejected while the simulation thread is synchronously
waiting for an external decision; the controller must resolve that decision
before advancing.

`supportsMidTransferContactEnforcement=false` remains intentional. The backend
does not claim a verified interrupted-transfer/handover outcome until the
network transfer path has been instrumented and tested at packet/byte level.
