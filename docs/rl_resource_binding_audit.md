# RL native resource binding audit

This project now exposes three resource-binding modes through `RlResourceBindingMode`:

- `candidate_only`: discrete VM/candidate selection only; continuous fields are preserved in receipts but do not affect execution.
- `resource_aware_estimator_bound`: `cpuShare`, `bandwidthShare`, and `txPowerRatio` affect resource-aware delay/energy estimates used by the RL bridge for candidate ranking and metadata, but not the native scheduler.
- `native_scheduler_bound`: `cpuShare`, `bandwidthShare`, and `txPowerRatio` are bound into SatEdgeSim execution paths.

## Native binding implementation

`native_scheduler_bound` is implemented with explicit task-scoped hooks:

1. CPU binding: `RlNativeResourceBindingManager.bindTask(...)` reduces the selected VM's native CloudSim MIPS while the task is active. Because the current SatEdgeSim/CloudSim integration uses VM-wide MIPS rather than a per-cloudlet cap, overlapping native-bound tasks on the same VM use the most restrictive active `cpuShare`. The binding scope is recorded as `vm_mips_scoped_min_active_share`.
2. Network binding: `DefaultNetworkModel` attaches the task's active native profile to `FileTransferProgress`. At each native network update, `bandwidthShare` is a requested weight and the effective LAN/WAN service is normalized over the active shared-LAN domain and the global WAN group. The binding scope is `shared_lan_domain_and_global_wan`; this backend does not claim per-link allocation.
3. Transmit-power binding: `DefaultEnergyModel` scales wireless transmission energy by `txPowerRatio` for transmission events. The binding scope is `wireless_transmission_energy_ratio`.
4. Completion release: `ExternalRLOrchestrator.resultsReturned(...)` calls `RlDecisionBridge.recordCompletion(...)`, which releases the native VM MIPS binding and records release metadata in the completion receipt.

The receipt fields that evidence native binding include:

```text
continuous_resource_binding_mode = native_scheduler_bound
native_scheduler_bound = true
native_binding_applied = true
native_cpu_mips_bound = true
native_network_bandwidth_bound = true
native_tx_power_bound = true
native_base_mips
native_applied_mips
native_cpu_share
native_bandwidth_share
native_tx_power_ratio
native_binding
```

Completion receipts include `native_binding_released` and `native_binding_release`.

## Remaining engineering caveat

This is a native VM/network/power binding, but CPU binding is VM-scoped because the project does not use a per-cloudlet MIPS cap. For concurrent tasks sharing a VM, the VM effective capacity follows the minimum active `cpuShare`, while per-task effective service is observed from CloudSim Cloudlet finished-length deltas. The runtime trace records requested share, effective MIPS, VM capacity, active-task contention and simulation timestamp; it must satisfy sum-effective <= VM capacity before a conservation claim is eligible.

Bandwidth uses the same requested/effective distinction. The native transfer progression records requested share, effective LAN/WAN bandwidth, capacity, contention group and timestamp. The current physical scope is shared LAN domains plus one global WAN bottleneck; `supportsPerLinkBandwidthAllocation` is therefore false.

Contact closure is recorded only from the native transfer progression. A qualifying interruption requires positive transferred work, unfinished remaining work and a contact boundary. A zero-byte fail-closed transfer is retained as terminal evidence but is not counted as a mid-transfer event. The current post-boundary action is task failure; seamless resume/retry is not claimed.

## Required validation before paper-scale use

Before using native SatEdgeSim replay as formal evidence, run a smoke replay that requests `continuous_resource_binding_mode=native_scheduler_bound` and verify that scheduling receipts show native binding evidence and completion receipts show release evidence. Then run the TriSatFlow formal preflight with the same SatEdgeSim root.
