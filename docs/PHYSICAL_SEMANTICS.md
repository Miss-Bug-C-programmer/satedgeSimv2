# SatEdgeSim physical semantics

SatEdgeSim is the authoritative execution backend. The active configuration is
the native runtime object `ExecutionConfiguration` (the older
`PersistentExecutionConfiguration` name remains an input compatibility type):

```text
Π_k = { X_k assignments, R_k native resources, P_k mutable policy fields }
Π_{k+1} = Π_k ⊕ ΔΠ_k
```

`/configuration/apply` is bootstrap-only in strict mode, or an exact
idempotent re-submit. It installs the reusable configuration consumed by the
existing `ExternalRLOrchestrator` persistent-rule path and never claims that
running tasks changed. A material active-configuration update must use
`/configuration/patch` (or the `/intervention` alias), which calls the single
`ReconfigurationExecutor` and returns `PatchApplicationResult`.

Strict patch application requires a server-issued `ValidationReceipt`. The
receipt binds the session, intervention id, base configuration version, exact
patch digest, scope digest, current control-state identity and the
server-issued physical advance receipt. A caller-supplied
`revalidatedWorldVersion` is never an authorization source.

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
include requested, configuration-applied, native-applied, deferred, rejected,
changed-entity, version, timestamp, resource-binding and realized-volume
fields, plus a stable `evidenceId` and explicit apply/stale/partial-reject
status. Persistent-rule-only changes are configuration-plane changes and are
not immediate native actuation until a later dispatch produces runtime rule
effect evidence. The physical delay receipt
includes native task/transfer progression snapshots rather than only a clock
delta. `/protocol_events` and `/dynamic_validation/report` expose the same
runtime evidence boundary.
