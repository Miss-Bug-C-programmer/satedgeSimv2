# TopologyOracle 与 ContactPlan

本阶段在现有区域化 GEO–LEO–Ground 场景之上增加确定性轨迹查询和接触窗口后端，不改变四动作接口：`0 local`、`1 neighbor LEO`、`2 GEO`、`3 ground`。

## 1. Physical truth source

`FilesParser` 继续一次性加载三类 CSV 轨迹。`TrajectoryPositionProvider` 复用内存中的 block，不在每次 future query 时重新读取 CSV：

```text
CLOUD             -> geo.csv
EDGE_DEVICE       -> leo.csv
EDGE_DATACENTER   -> ground.csv
```

节点主键是 `simulationParameters.TYPES + DataCenter.deviceID`，不是 CloudSim 内部 datacenter id。

## 2. Coordinate and time semantics

轨迹使用 ECEF，Java 内部单位是 meters，CSV 单位是 km。GEO 和 ground 在 ECEF 中固定，LEO 使用采样点之间的线性插值，因此支持 `double` simulation time。超过 trace horizon 不会 wrap 到轨迹开头；position query 会 clamp 到轨迹末端，而 ContactPlan 会用 `rightCensored` 表示未知的未来边界。

## 3. TopologyOracle

`TopologyOracle` 是确定性 physical topology truth，提供位置、链路快照和 active-node graph building blocks。`LinkSnapshot.available` 始终表示：

```text
geometryVisible AND withinDirectionalCommunicationRange
```

几何规则由 `LinkGeometry` 统一实现：ground–satellite 使用 elevation，satellite–satellite 使用 Earth occultation，ground–ground 仅相同位置可见。范围由 `LinkAvailability` 按 destination type 选择。

## 4. ContactPlan

`ContactPlan` 对 ordered pair 延迟计算完整 deterministic trace 的 contact windows，并缓存窗口列表。窗口边界由 `contact_scan_step_sec` 扫描，再以 `contact_refine_tolerance_sec` 做二分细化。`ContactForecast` 提供：

- `availableNow`
- `remainingLifetimeSec`
- `remainingLifetimeCensored`
- 当前窗口结束时间
- 下一个窗口的开始/结束时间
- 查询区间内的窗口列表

默认配置：

```properties
topology_forecast_mode=deterministic_trajectory
topology_forecast_horizon_sec=600
contact_scan_step_sec=1.0
contact_refine_tolerance_sec=0.1
```

## 5. Ground truth 与 forecast 边界

`GET /topology/current` 返回当前物理真值。`POST /topology/contact_plan` 返回仅由确定性轨道、位置、几何和通信范围生成的 contact forecast。

The deterministic forecast contains only orbital/geometric contact information and does not reveal future stochastic workload, queue, channel, or compute states.

ContactPlan 不代表未来吞吐率、队列、CPU load、VM utilization、task arrival、reward 或 controller destination。

## 6. REST endpoints

```text
GET  /topology/current
POST /topology/contact_plan
GET  /debug/contact_plan_stats
```

contact query 示例：

```json
{
  "source": {"type": "EDGE_DEVICE", "deviceId": 1},
  "destination": {"type": "EDGE_DATACENTER", "deviceId": 1},
  "horizonSec": 600
}
```

接口会验证 node type、positive device id 和当前 session 的 active node 集合。旧的 `/reset`、`/get_state`、`/step`、`/apply_action`、`/get_metrics`、`/close`、`/health` 保持可用。

## 7. Orchestrator compatibility

实际 `scenarioProfile=default` 路径的 `estimatedLinkLifetimeSec` 来自 ContactPlan；`FeasibilityInfo` 同时暴露 lifetime source、censoring 和 current/next contact summary。controlled synthetic profile 保留原有 compatibility-only estimate，因为它刻意不代表真实轨迹。

本阶段不实现 Persistent Configuration、KEEP/REPLAN、VoC、planning budget、world model 或新的 DRL controller。当前的 report-only Configuration Viability 位于独立的 `Viability/ConfigurationViability.java`，只消费 ContactPlan 结果，不改变动作选择；详见 `docs/configuration_viability.md`。
