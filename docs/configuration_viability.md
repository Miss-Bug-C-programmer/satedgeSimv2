# Configuration Viability（report-only）

本阶段在 `TopologyOracle + ContactPlan` 之上增加最小的当前配置可行性评估，不改变四动作语义，也不执行控制策略。

## 判定输入

每个 candidate 使用：

```text
current link availability
remaining deterministic contact lifetime
estimated task completion time
configured survival margin
contact-end censoring flag
```

判定关系：

```text
requiredContactSec = estimatedTaskCompletionTimeSec + requiredMarginSec
serviceMarginSec = availableContactSec - requiredContactSec
```

## 输出状态

```text
VIABLE
    当前链路可用、contact end 未 censored，且 service margin >= 0

INVIABLE
    当前无 contact，或 service margin < 0

UNCERTAIN
    当前下界足够，但 contact end 被 horizon/trace boundary censored
```

local configuration 直接视为 `VIABLE`，因为它不依赖远程 contact window。

## 接口

```text
GET /configuration/viability
```

接口返回当前 RL decision 的候选报告以及聚合数量：

```text
viableCandidateCount
inviableCandidateCount
uncertainCandidateCount
```

candidate 额外字段：

```text
viabilityStatus
viabilityReason
viabilitySource
viabilityContactEndCensored
viabilityAvailableContactSec
viabilityRequiredContactSec
viabilityServiceMarginSec
```

当前默认配置：

```properties
configuration_viability_mode=report_only
```

## 边界

这个 evaluator 是纯报告层：

- 不修改 action mask
- 不选择目标 VM
- 不执行 KEEP/REPLAN
- 不保存 Persistent Configuration
- 不读取未来 queue、load、channel 或 task state
- 不实现 VoC、planning budget 或新的 DRL controller

后续 Configuration Viability Monitor 可以在此报告之上组合多个候选、服务约束和配置状态；本阶段不提前实现该 monitor。
