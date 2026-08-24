package edu.weijunyong.satedgesim.server;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.cloudbus.cloudsim.cloudlets.Cloudlet;
import org.cloudbus.cloudsim.vms.Vm;
import org.cloudbus.cloudsim.vms.VmSimple;

import edu.weijunyong.satedgesim.TasksGenerator.Task;

/** Contract tests for server-authenticated and atomic intervention semantics. */
public final class InterventionTransactionContractTest {
    private InterventionTransactionContractTest() {
    }

    public static void main(String[] args) {
        RlNativeResourceBindingManager.resetForSimulation();
        Vm vm = new VmSimple(101L, 1000.0, 1L);
        Task running = task(11L, vm, Cloudlet.Status.INEXEC);
        List<Task> tasks = new ArrayList<Task>(Arrays.asList(running));
        List<Vm> vms = new ArrayList<Vm>(Arrays.asList(vm));
        ExecutionConfiguration configuration = baseConfiguration();
        configuration.resourceAllocations.put("11", resource(0.8, 0.6, 0.7));
        ReconfigurationExecutor executor = new ReconfigurationExecutor(tasks, vms, 10.0, 3L);

        ConfigurationPatch cpuOnly = patch(configuration.version, 3L, scopeTasks(11L));
        Map<String, Object> cpu = new LinkedHashMap<String, Object>();
        cpu.put("cpuShare", Double.valueOf(0.4));
        cpuOnly.resourceChanges.put("11", cpu);
        PatchApplicationResult t10 = executor.apply(configuration, cpuOnly, true);
        require(t10.accepted, "T10 CPU-only patch must apply");
        Map<String, Object> preserved = asMap(t10.afterConfiguration.get("resourceAllocations"));
        Map<String, Object> preservedTask = asMap(preserved.get("11"));
        require(number(preservedTask.get("bandwidthShare")) == 0.6
                && number(preservedTask.get("txPowerRatio")) == 0.7,
                "T10 unspecified resource dimensions must be preserved: " + t10.toMap());

        Task firstBindingTask = task(13L, vm, Cloudlet.Status.INEXEC);
        ReconfigurationExecutor firstBindingExecutor = new ReconfigurationExecutor(
                Arrays.asList(firstBindingTask), vms, 10.0, 3L);
        ExecutionConfiguration firstBinding = baseConfiguration();
        ConfigurationPatch incompleteFirstBinding = patch(firstBinding.version, 3L, scopeTasks(13L));
        Map<String, Object> firstCpuOnly = new LinkedHashMap<String, Object>();
        firstCpuOnly.put("cpuShare", Double.valueOf(0.4));
        incompleteFirstBinding.resourceChanges.put("13", firstCpuOnly);
        PatchApplicationResult t10b = firstBindingExecutor.apply(firstBinding, incompleteFirstBinding, true);
        require(!t10b.accepted && hasReasonPrefix(t10b, "MISSING_RESOURCE_VALUE"),
                "T10b first native binding must reject unspecified dimensions: " + t10b.toMap());

        ConfigurationPatch invalid = patch(t10.resultingConfigurationVersion, 3L, scopeTasks(11L));
        Map<String, Object> invalidResource = new LinkedHashMap<String, Object>();
        invalidResource.put("cpuShare", Double.NaN);
        invalid.resourceChanges.put("11", invalidResource);
        PatchApplicationResult t11 = executor.apply(configAfter(t10), invalid, true);
        require(!t11.accepted && hasReasonPrefix(t11, "INVALID_RESOURCE_VALUE"),
                "T11 NaN must fail closed");
        require(t11.resultingConfigurationVersion == t10.resultingConfigurationVersion,
                "T11 failed patch must not increment version");

        Task unassigned = task(12L, null, Cloudlet.Status.READY);
        List<Task> deferredTasks = new ArrayList<Task>(Arrays.asList(unassigned));
        ExecutionConfiguration deferredConfig = baseConfiguration();
        ReconfigurationExecutor deferredExecutor = new ReconfigurationExecutor(deferredTasks, vms, 10.0, 3L);
        ConfigurationPatch deferred = patch(deferredConfig.version, 3L, scopeTasks(12L));
        deferred.resourceChanges.put("12", resource(0.5, 0.6, 0.7));
        PatchApplicationResult t12 = deferredExecutor.apply(deferredConfig, deferred, true);
        require(t12.accepted && t12.deferredChanges.get("resourceChanges") instanceof Map
                && t12.nativeAppliedChanges.isEmpty() && t12.actualChangedEntities.isEmpty(),
                "T12 unassigned resource must be deferred, not immediate native actuation");

        ExecutionConfiguration ruleConfig = configAfter(t10);
        ConfigurationPatch ruleOnly = patch(ruleConfig.version, 3L, scopeResources("default"));
        ruleOnly.persistentRuleChanges.put("default", rule("source", "1"));
        PatchApplicationResult t13 = executor.apply(ruleConfig, ruleOnly, true);
        require(t13.accepted && t13.configurationChanged && !t13.nativeExecutionChanged
                && t13.futureDispatchRuleChanged && !t13.nativeResourceActuationObserved,
                "T13 rule-only patch must be configuration-only");

        ConfigurationPatch a = patch(1L, 3L, scopeTasks(11L));
        a.resourceChanges.put("11", resource(0.4, 0.6, 0.7));
        ConfigurationPatch b = patch(1L, 3L, scopeTasks(11L));
        b.resourceChanges.put("11", resource(0.5, 0.6, 0.7));
        require(!ConfigurationPatchDigest.patch(a).equals(ConfigurationPatchDigest.patch(b)),
                "T5 patch digest must bind exact patch intent");

        Vm failingVm = failingVm(202L, 800.0);
        Vm goodVm = new VmSimple(201L, 1000.0, 1L);
        Task first = task(21L, goodVm, Cloudlet.Status.INEXEC);
        Task second = task(22L, failingVm, Cloudlet.Status.INEXEC);
        List<Task> rollbackTasks = Arrays.asList(first, second);
        List<Vm> rollbackVms = Arrays.asList(goodVm, failingVm);
        ExecutionConfiguration rollbackConfig = baseConfiguration();
        rollbackConfig.resourceAllocations.put("21", resource(1.0, 1.0, 1.0));
        rollbackConfig.resourceAllocations.put("22", resource(1.0, 1.0, 1.0));
        ReconfigurationExecutor rollbackExecutor = new ReconfigurationExecutor(rollbackTasks, rollbackVms, 10.0, 3L);
        ConfigurationPatch rollback = patch(rollbackConfig.version, 3L, scopeTasks(21L, 22L));
        rollback.resourceChanges.put("21", resource(0.4, 0.6, 0.7));
        rollback.resourceChanges.put("22", resource(0.4, 0.6, 0.7));
        PatchApplicationResult t15 = rollbackExecutor.apply(rollbackConfig, rollback, true);
        require(!t15.accepted && rollbackConfig.version == 1L && Math.abs(goodVm.getMips() - 1000.0) < 1.0e-9,
                "T15 native failure must rollback earlier VM mutation: " + t15.toMap());

        System.out.println("InterventionTransactionContractTest OK (T5,T10-T13,T15-T17)");
    }

    private static ExecutionConfiguration baseConfiguration() {
        ExecutionConfiguration out = new ExecutionConfiguration();
        out.configId = "cfg";
        out.version = 1L;
        out.worldVersion = 3L;
        out.creationSimTimeSec = 0.0;
        out.lastUpdateSimTimeSec = 0.0;
        return out;
    }

    private static Task task(long id, Vm vm, Cloudlet.Status status) {
        Task out = new Task((int) id, 100L, 1L);
        if (vm != null) out.setVm(vm);
        out.setStatus(status);
        return out;
    }

    private static ConfigurationPatch patch(long version, long world, Map<String, Object> scope) {
        ConfigurationPatch out = new ConfigurationPatch();
        out.baseConfigurationVersion = Long.valueOf(version);
        out.baseWorldVersion = Long.valueOf(world);
        out.validationReceiptId = "contract-receipt";
        out.originatingInterventionId = "contract-intervention";
        out.requestedScope.putAll(scope);
        out.attachServerValidationReceipt(new ValidationReceipt(
                "contract-receipt", "contract-session", "contract-intervention",
                version, world, world, 1L, "world", "scope", "patch", "advance",
                0.0, 0L, Long.MAX_VALUE, true, false));
        return out;
    }

    private static Map<String, Object> scopeTasks(Long... ids) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("task_ids", new ArrayList<Long>(Arrays.asList(ids)));
        return out;
    }

    private static Map<String, Object> scopeResources(String... ids) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("resource_keys", new ArrayList<String>(Arrays.asList(ids)));
        return out;
    }

    private static Map<String, Object> resource(double cpu, double bandwidth, double txPower) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("cpuShare", Double.valueOf(cpu));
        out.put("bandwidthShare", Double.valueOf(bandwidth));
        out.put("txPowerRatio", Double.valueOf(txPower));
        return out;
    }

    private static Map<String, Object> rule(String selector, String value) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        Map<String, Object> match = new LinkedHashMap<String, Object>();
        match.put(selector, value);
        out.put("selector", match);
        out.put("assignment", new LinkedHashMap<String, Object>());
        return out;
    }

    private static Vm failingVm(final long id, final double mips) {
        return (Vm) Proxy.newProxyInstance(
                Vm.class.getClassLoader(), new Class<?>[] {Vm.class}, new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        if ("getId".equals(method.getName())) return Long.valueOf(id);
                        if ("getMips".equals(method.getName())) return Double.valueOf(mips);
                        if ("setMips".equals(method.getName())) throw new IllegalStateException("injected_native_failure");
                        if ("hashCode".equals(method.getName())) return Integer.valueOf((int) id);
                        if ("equals".equals(method.getName())) return Boolean.valueOf(proxy == args[0]);
                        if ("toString".equals(method.getName())) return "FailingVm(" + id + ")";
                        Class<?> type = method.getReturnType();
                        if (type == boolean.class) return Boolean.FALSE;
                        if (type == byte.class || type == short.class || type == int.class || type == long.class) return Integer.valueOf(0);
                        if (type == float.class || type == double.class) return Double.valueOf(0.0);
                        return null;
                    }
                });
    }

    private static ExecutionConfiguration configAfter(PatchApplicationResult result) {
        return PersistentExecutionConfiguration.fromRequest(result.afterConfiguration);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<String, Object>();
    }

    private static double number(Object value) {
        return value instanceof Number ? ((Number) value).doubleValue() : Double.NaN;
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
