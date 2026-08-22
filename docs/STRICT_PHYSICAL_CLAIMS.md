# Strict physical claim rules

The following claims are eligible only when the corresponding native runtime
evidence is present:

| Capability | Strict rule |
|---|---|
| Selective reconfiguration | `actual_changed_entities` must be a subset of the requested scope. |
| Placement change | The task object must be changed in the native task registry while the task is legal to reconfigure. |
| Resource change | A native binding snapshot must show the applied resource effect; a requested value alone is insufficient. |
| Running-task migration | Unsupported in this backend and rejected fail-closed. |
| Route actuation | Unsupported in this backend and rejected fail-closed. |
| Dynamic priority | Unsupported in this backend and rejected fail-closed. |
| Physical lifetime | Evidence must use simulation timestamps, not decision/task counts. |
| Stale protection | A mismatched base configuration/world version must reject or go through the canonical validation path. |
| Contact interruption | The current native transfer path handles contact closure, but the capability flag remains false until a qualifying runtime event is recorded. |
| CPU conservation | Runtime Cloudlet service evidence must include requested/effective/capacity/contention/timestamp fields and satisfy sum-effective <= native VM capacity. |
| Bandwidth conservation | Runtime transfer evidence must satisfy conservation for the claimed shared-LAN/global-WAN scope; per-link allocation is unsupported. |
| Requested versus effective | Requested action fields alone never establish physical allocation; the native consumer and runtime trace are mandatory. |

`SUPPORTED_BY_CODE`, `OBSERVED_AT_RUNTIME`, and
`ELIGIBLE_FOR_PUBLICATION_CLAIM` are distinct states. A branch, unit test, or
fixture is not a qualifying experiment result. Failed or unexecuted runs must
remain failures and are never converted into favorable defaults.
