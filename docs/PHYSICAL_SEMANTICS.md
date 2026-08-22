# SatEdgeSim physical semantics

SatEdgeSim is the authoritative execution backend. The active configuration is
the native runtime object `ExecutionConfiguration` (the older
`PersistentExecutionConfiguration` name remains an input compatibility type):

```text
Π_k = { X_k assignments, R_k native resources, P_k mutable policy fields }
Π_{k+1} = Π_k ⊕ ΔΠ_k
```

`/configuration/apply` installs the reusable configuration consumed by the
existing `ExternalRLOrchestrator` persistent-rule path. It does not claim that
running tasks changed. A current selective intervention uses
`/configuration/patch` (or the `/intervention` alias), which calls the single
`ReconfigurationExecutor` and returns `PatchApplicationResult`.

The executor validates the base configuration/world version, maps CloudSim
Cloudlet status plus the native transfer list to `QUEUED`, `TRANSMITTING`,
`RUNNING`, `RETURNING`, or `COMPLETED`, checks requested scope, and then applies
only accepted changes. CPU/bandwidth/power changes use the existing native
resource-binding registry. Active transfers are refreshed after a resource
patch, so the patch changes native progression rather than only a log.

Target migration, route actuation, and dynamic priority actuation are not
implemented by this backend. They are reported as unsupported and strict mode
rejects them. No execution-node identifier is changed when physical migration
is unavailable.

Configuration timestamps are simulation-time fields. `creationSimTimeSec`,
`lastUpdateSimTimeSec`, and `expiresAtSimTimeSec` are authoritative; the
control/monitor epoch is only a coordination guard. World evolution increments
`worldVersion` through `/advance_world` and never mutates the CloudSim clock
directly.

Actual intervention receipts are queryable from `/intervention_evidence` and
include requested, applied, rejected, changed-entity, version, timestamp,
resource-binding and realized-volume fields, plus a stable `evidenceId` and
explicit apply/stale/partial-reject status. The physical delay receipt
includes native task/transfer progression snapshots rather than only a clock
delta. `/protocol_events` and `/dynamic_validation/report` expose the same
runtime evidence boundary.
