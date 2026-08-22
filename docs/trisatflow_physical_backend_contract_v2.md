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
| `POST /configuration/patch` | Applies one versioned selective delta to the active native execution configuration. |
| `POST /intervention` | Canonical intervention alias for `/configuration/patch`. |
| `GET /configuration/current` | Active configuration and version. |
| `POST /configuration/validate` | Physical target/resource/version/contact validation. |
| `POST /configuration/dispatch` | Dispatches a current pending task under an active reusable rule. |
| `POST /advance_world` | Advances CloudSim through `pause(target)` and resumes after the receipt. |
| `GET /intervention_evidence` | Actual requested/applied/rejected intervention evidence. |
| `GET /protocol_events` | Runtime protocol events for configuration and intervention application. |
| `GET /dynamic_validation/report` | Strict capability and latest runtime validation report. |
| `GET /debug/decision_plane_stats` | Candidate/build instrumentation. |

The old scoped/budgeted planner routes are not part of the formal contract.

The patch request carries `interventionId` through
`originatingInterventionId`, `baseConfigurationVersion`, observed
`world/control` identity, `requestedScope`, `observationScope`, exact change
maps, `preserveResumeRecompute`, `planningDelayMetadata`, and
`acquisitionMetadata`. The response carries `decisionStatus` (`APPLY`,
`REJECT_STALE`, `PARTIAL_REJECT`, or `REPLAN_REQUIRED`), an `evidenceId`,
requested/applied/rejected changes, realized scope/volume, version before and
after, world/simulation time, and the intervention identity.

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

`ExecutionConfiguration` supports task assignments, native resource
allocations, associated persistent rules, routes/priorities as explicit
capability-bounded fields, timestamps and provenance. `ExternalRLOrchestrator`
resolves an active rule before requesting a new RL decision. A rule that matches
later tasks therefore does not require a list of future task IDs. A current
selective change is a `ConfigurationPatch`; it is not represented by deleting
the old rule and globally rebuilding future decisions.

The backend currently supports native CPU/bandwidth/power actuation, but its
CPU scope is the existing VM-level binding semantics. Target migration, route
actuation and dynamic priority actuation are explicitly unsupported.

## Physical time and known boundary

`/advance_world` uses CloudSim's public `pause(target)` API and returns the
actual before/target time, then resumes the simulation. It never mutates the
clock directly. The receipt also contains native task finished/remaining
workload, task-status counts, active transfer count and remaining network work
before/after snapshots. The old configuration remains active during the
advance; TriSatFlow validates and applies the new configuration only after the
receipt. A control-epoch advance leaves the simulation paused only until the
canonical validation/apply-or-reject path resumes it.
An advance request is rejected while the simulation thread is synchronously
waiting for an external decision; the controller must resolve that decision
before advancing.

`supportsMidTransferContactEnforcement=false` remains intentional. The backend
does not claim a verified interrupted-transfer/handover outcome until the
network transfer path has been instrumented and tested at packet/byte level.
