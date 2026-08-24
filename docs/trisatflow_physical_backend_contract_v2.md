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
| `POST /configuration/apply` | Bootstrap or exact idempotent reusable-rule installation; strict active material changes are rejected. |
| `POST /configuration/patch` | Applies one versioned selective delta to the active native execution configuration. |
| `POST /intervention` | Canonical intervention alias for `/configuration/patch`. |
| `GET /configuration/current` | Active configuration and version. |
| `POST /configuration/validate` | Patch validation and server-issued `ValidationReceipt` creation. |
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
`acquisitionMetadata`, and `acquisitionEpoch`. The validation request and
the subsequent patch request must carry the same values because the server
receipt digest covers the complete intent. Empty scope dimensions are neutral;
only non-empty unsupported link/route dimensions are rejected. The response
carries `decisionStatus` (`APPLY`,
`REJECT_STALE`, `PARTIAL_REJECT`, or `REPLAN_REQUIRED`), an `evidenceId`,
requested/applied/rejected changes, realized scope/volume, version before and
after, world/simulation time, and the intervention identity.

Strict publication patch application requires a server-owned validation
receipt. Its digest covers the exact patch intent, including assignments,
resource dimensions, scope, semantics and provenance. The receipt is single-use
and binds the current control-state identity and a server-issued physical
advance receipt; client-supplied `revalidatedWorldVersion` is observational
metadata only.

`PatchApplicationResult` separates `configurationAppliedChanges`,
`nativeAppliedChanges`, `deferredChanges` and `rejectedChanges`, with distinct
configuration/native realized scopes. Native application is transactional and
restores touched VM/binding state if a later native operation fails.

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

The native decision point stores a lightweight `PendingDecisionContext`; it
does not invoke `RlStateBuilder.build` or evaluate all candidates. The POST
route filters VM identities and applies `max_candidate_count` before invoking
`RlStateBuilder.buildScoped`. The scoped builder therefore evaluates only the
retained candidates and omits dense source summaries and the all-datacenter
table; candidate `vmIndex` values are remapped to the authoritative original
action indices. Receipts expose requested/applied scope and budget, before/after
counts, `candidateEvaluationsDelta`, `fullStateBuilderDeltaSinceDecisionContext`,
`scopeRestrictionAppliedBeforeEvaluation`, `budgetRestrictionAppliedBeforeEvaluation`,
and `scopedPlannerCandidateEvaluations`.

GET `/get_state`, GET `/get_planner_state`, and the explicitly named legacy
configuration-viability report may materialize the full state for compatibility.
They set `legacyFullStateAccessObserved=true`; a later scoped response remains
usable for debugging but is not publication-eligible for selective acquisition.

The backend acquisition contract currently supports only
`max_candidate_count`. Planner compute/time budgets and coordination-byte
budgets are separate declarations and are not claimed as backend acquisition
consumers.

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
