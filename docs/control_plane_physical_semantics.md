# Control-plane / physical semantics audit

The formal split is:

```text
TriSatFlow: monitor -> viability -> scope/fidelity/budget -> BenefitEstimator/VoC
           -> planner -> physical delay receipt -> post-delay validation -> apply
SatEdgeSim: CloudSim clock, topology/contact, VM/resource feasibility, execution,
            transfer/task lifecycle, completion and energy receipts
```

The REST server exposes contract `2.0`. `KEEP` is an outer control decision and
is not an inner SatEdgeSim action. `LOCAL`, `REGIONAL` and `GLOBAL` are scope
reporting buckets only; they are not action labels.

The cheap monitor is a separate DTO and reports zero candidate evaluations.
The unified planner POST applies scope and budget before the scoped builder;
the compatibility GET is the only full-state route. Configuration payloads
contain reusable selector rules rather than a future-task lookup table.

Physical decision delay is verified from CloudSim before/after time and uses no
wall-clock sleep as simulation time. Configuration validation rejects stale
versions, unavailable VM bindings, expired contact markers and invalid resource
shares. Contact plans are deterministic orbit forecasts and explicitly do not
expose future stochastic workload/channel truth.

The current evidence boundary is mid-transfer enforcement: the capability is
false until a real interrupted-transfer test demonstrates failure/handover
semantics and the receipt reports the affected bytes/task outcome.
