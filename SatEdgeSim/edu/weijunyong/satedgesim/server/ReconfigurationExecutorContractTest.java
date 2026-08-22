package edu.weijunyong.satedgesim.server;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.cloudbus.cloudsim.cloudlets.Cloudlet;
import org.cloudbus.cloudsim.vms.Vm;
import org.cloudbus.cloudsim.vms.VmSimple;

import edu.weijunyong.satedgesim.TasksGenerator.Task;

/** Dependency-light T1-T10 regression coverage for native patch semantics. */
public final class ReconfigurationExecutorContractTest {
    private ReconfigurationExecutorContractTest() {
    }

    public static void main(String[] args) {
        Vm vm0 = new VmSimple(10L, 1000.0, 1L);
        Vm vm1 = new VmSimple(11L, 800.0, 1L);
        List<Vm> vms = Arrays.asList(vm0, vm1);
        Task queued = new Task(1, 100L, 1L);
        queued.setStatus(Cloudlet.Status.READY);
        Task running = new Task(2, 100L, 1L);
        running.setVm(vm0);
        running.setStatus(Cloudlet.Status.INEXEC);
        Task completed = new Task(3, 100L, 1L);
        completed.setVm(vm0);
        completed.setStatus(Cloudlet.Status.SUCCESS);
        List<Task> tasks = Arrays.asList(queued, running, completed);

        ExecutionConfiguration configuration = new ExecutionConfiguration();
        configuration.configId = "cfg";
        configuration.version = 7L;
        configuration.creationSimTimeSec = 0.0;
        configuration.lastUpdateSimTimeSec = 0.0;
        configuration.assignments.put("1", assignment(vm0, 0));
        configuration.assignments.put("2", assignment(vm0, 0));
        configuration.assignments.put("3", assignment(vm0, 0));
        configuration.resourceAllocations.put("2", resource(1.0));
        ReconfigurationExecutor executor = new ReconfigurationExecutor(tasks, vms, 10.0, 4L);

        ConfigurationPatch inScope = patch(configuration.version, scopeTasks(1L));
        inScope.taskAssignmentChanges.put("1", assignment(vm1, 1));
        PatchApplicationResult t1 = executor.apply(configuration, inScope, true);
        require(t1.accepted && t1.changed, "T1 in-scope patch must apply: " + t1.toMap());
        require(queued.getVm() == vm1, "T1 must change the native queued task assignment");
        configuration = configAfter(t1);

        ConfigurationPatch nodeScopeEscape = patch(configuration.version, scopeNodes(vm1.getId()));
        nodeScopeEscape.taskAssignmentChanges.put("1", assignment(vm0, 0));
        PatchApplicationResult t1b = executor.apply(configuration, nodeScopeEscape, true);
        require(!t1b.accepted && hasReason(t1b, "out_of_scope_target_node"),
                "T1b must reject assignment outside a node-only scope: " + t1b.toMap()
                        + " vm0=" + vm0.getId() + " vm1=" + vm1.getId());
        require(!t1b.scopeInvariantSatisfied, "T1b rejected scope escape must not claim scope invariant satisfied");
        require(queued.getVm() == vm1, "T1b must not escape the requested node scope");

        ConfigurationPatch outOfScope = patch(configuration.version, scopeTasks(1L));
        outOfScope.taskAssignmentChanges.put("2", assignment(vm1, 1));
        PatchApplicationResult t2 = executor.apply(configuration, outOfScope, true);
        require(!t2.accepted && hasReason(t2, "out_of_scope"), "T2 out-of-scope change must be rejected");
        require(running.getVm() == vm0, "T2 must not expand scope or change VM");

        ConfigurationPatch completedPatch = patch(configuration.version, scopeTasks(3L));
        completedPatch.taskAssignmentChanges.put("3", assignment(vm1, 1));
        PatchApplicationResult t3 = executor.apply(configuration, completedPatch, true);
        require(!t3.accepted && hasReason(t3, "completed_task_immutable"), "T3 completed task must be immutable");

        ConfigurationPatch runningMigration = patch(configuration.version, scopeTasks(2L));
        runningMigration.taskAssignmentChanges.put("2", assignment(vm1, 1));
        PatchApplicationResult t4 = executor.apply(configuration, runningMigration, true);
        require(!t4.accepted && hasReasonPrefix(t4, "unsupported_task_target_migration"), "T4 running migration must fail closed");
        require(running.getVm() == vm0, "T4 must not logically migrate the running task");

        ConfigurationPatch resourceOnly = patch(configuration.version, scopeTasks(2L));
        Map<String, Object> resource = resource(0.4);
        resourceOnly.resourceChanges.put("2", resource);
        PatchApplicationResult t5 = executor.apply(configuration, resourceOnly, true);
        require(t5.accepted && t5.changed, "T5 resource-only patch must apply");
        require(running.getVm() == vm0, "T5 resource-only patch must preserve placement");
        require(vm0.getMips() < 1000.0, "T5 must bind CPU resource on the native VM: mips=" + vm0.getMips() + " receipt=" + t5.toMap());
        configuration = configAfter(t5);

        ConfigurationPatch noOp = new ConfigurationPatch();
        noOp.baseConfigurationVersion = Long.valueOf(configuration.version);
        PatchApplicationResult t6 = executor.apply(configuration, noOp, true);
        require(t6.accepted && !t6.changed && t6.resultingConfigurationVersion == configuration.version,
                "T6 no material change must not increment configuration version");

        ConfigurationPatch stale = patch(configuration.version - 1L, scopeTasks(1L));
        stale.taskAssignmentChanges.put("1", assignment(vm0, 0));
        PatchApplicationResult t7 = executor.apply(configuration, stale, true);
        require(!t7.accepted && t7.staleBaseRejected, "T7 stale base must be rejected by canonical executor");

        ConfigurationPatch staleObservedWorld = patch(configuration.version, scopeTasks(2L));
        staleObservedWorld.observedWorldVersion = Long.valueOf(0L);
        staleObservedWorld.resourceChanges.put("2", resource(0.2));
        PatchApplicationResult t7b = executor.apply(configuration, staleObservedWorld, true);
        require(!t7b.accepted && t7b.staleBaseRejected,
                "T7b stale observed world must be rejected by canonical executor");

        ConfigurationPatch evidencePatch = patch(configuration.version, scopeTasks(2L));
        evidencePatch.resourceChanges.put("2", resource(0.3));
        PatchApplicationResult t8 = executor.apply(configuration, evidencePatch, true);
        require(t8.accepted, "T8 evidence patch must apply");
        require(t8.actualChangedEntities.contains("task:2:resourceChanges"), "T8 actual changed task must be evidenced");
        require(t8.realizedReconfigurationVolume.get("nativeBindingSnapshots") instanceof Map,
                "T8 native binding snapshot must come from the runtime binding manager");
        configuration = configAfter(t8);

        ExecutionConfiguration lifetime = configuration.copy();
        lifetime.lastUpdateSimTimeSec = 10.0;
        lifetime.configuredLifetimeSec = 20.0;
        lifetime.expiresAtSimTimeSec = 30.0;
        require(lifetime.ageAt(20.0) == 10.0 && !lifetime.isExpired(20.0), "T9 physical age at equal sim time");
        require(lifetime.ageAt(20.0) == 10.0 && lifetime.isExpired(30.0), "T9 expiry must not depend on decision count");
        ExecutionConfiguration lowDecisionFrequency = lifetime.copy();
        ExecutionConfiguration highDecisionFrequency = lifetime.copy();
        lowDecisionFrequency.metadata.put("decisionCount", Long.valueOf(1L));
        highDecisionFrequency.metadata.put("decisionCount", Long.valueOf(1000L));
        require(lowDecisionFrequency.ageAt(20.0) == highDecisionFrequency.ageAt(20.0)
                && lowDecisionFrequency.isExpired(30.0) == highDecisionFrequency.isExpired(30.0),
                "T9 equal sim time must produce equal lifetime despite decision frequency");

        ConfigurationPatch strictMigration = patch(configuration.version, scopeTasks(2L));
        strictMigration.taskAssignmentChanges.put("2", assignment(vm1, 1));
        PatchApplicationResult t10 = executor.apply(configuration, strictMigration, true);
        require(!t10.accepted && !ReconfigurationExecutor.SUPPORTS_TASK_TARGET_MIGRATION,
                "T10 strict mode must reject unsupported migration capability");
        System.out.println("ReconfigurationExecutorContractTest OK (T1-T10)");
    }

    private static ExecutionConfiguration configAfter(PatchApplicationResult result) {
        return PersistentExecutionConfiguration.fromRequest(result.afterConfiguration);
    }

    private static ConfigurationPatch patch(long version, Map<String, Object> scope) {
        ConfigurationPatch patch = new ConfigurationPatch();
        patch.baseConfigurationVersion = Long.valueOf(version);
        patch.baseWorldVersion = Long.valueOf(4L);
        patch.requestedScope.putAll(scope);
        patch.originatingInterventionId = "contract-test";
        return patch;
    }

    private static Map<String, Object> scopeTasks(Long taskId) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("task_ids", new ArrayList<Long>(Arrays.asList(taskId)));
        return result;
    }

    private static Map<String, Object> scopeNodes(long nodeId) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("node_ids", new ArrayList<Long>(Arrays.asList(Long.valueOf(nodeId))));
        return result;
    }

    private static Map<String, Object> assignment(Vm vm, int index) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("targetVmIndex", Integer.valueOf(index));
        return result;
    }

    private static Map<String, Object> resource(double cpuShare) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("cpuShare", Double.valueOf(cpuShare));
        result.put("bandwidthShare", Double.valueOf(1.0));
        result.put("txPowerRatio", Double.valueOf(1.0));
        return result;
    }

    private static boolean hasReason(PatchApplicationResult result, String reason) {
        for (Map<String, Object> item : result.rejectedChanges) if (reason.equals(item.get("reason"))) return true;
        return false;
    }

    private static boolean hasReasonPrefix(PatchApplicationResult result, String prefix) {
        for (Map<String, Object> item : result.rejectedChanges) {
            Object reason = item.get("reason");
            if (reason != null && String.valueOf(reason).startsWith(prefix)) return true;
        }
        return false;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
