package edu.weijunyong.satedgesim.server;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.cloudbus.cloudsim.vms.Vm;
import org.cloudbus.cloudsim.vms.VmSimple;

import edu.weijunyong.satedgesim.TasksGenerator.Task;

/** Native binding contract test for the persistent fast path (not a live simulation). */
public class PersistentNativeBindingContractTest {
    public static void main(String[] args) {
        RlDecisionBridge bridge = new RlDecisionBridge("persistent-binding-contract");
        PersistentExecutionConfiguration configuration = new PersistentExecutionConfiguration();
        configuration.configId = "cfg-native";
        configuration.version = 3L;
        Map<String, Object> assignment = new LinkedHashMap<String, Object>();
        assignment.put("targetVmIndex", 0);
        assignment.put("bindingMode", "native_scheduler_bound");
        assignment.put("cpuShare", 0.5);
        assignment.put("bandwidthShare", 0.5);
        assignment.put("txPowerRatio", 0.5);
        configuration.assignments.put("42", assignment);
        bridge.setPersistentConfiguration(configuration);

        Vm vm = new VmSimple(1000.0, 1L);
        List<Vm> vms = new ArrayList<Vm>();
        vms.add(vm);
        Task task = new Task(42, 100L, 1L);
        task.setVm(vm);
        int selected = bridge.resolvePersistentVm(
                null, new String[0], task, vms,
                new RlDecisionBridge.FeasibilityChecker() {
                    @Override
                    public boolean isFeasible(String[] architecture, Task candidate, Vm candidateVm) {
                        return true;
                    }
                });
        require(selected == 0, "persistent rule must select target VM");
        Map<String, Object> stats = bridge.getDecisionPlaneStats();
        Map<?, ?> dispatch = (Map<?, ?>) stats.get("lastPersistentDispatch");
        require(Boolean.TRUE.equals(dispatch.get("nativeBindingRequested")), "native binding must be requested");
        require(Boolean.TRUE.equals(dispatch.get("nativeBindingApplied")), "native binding must be applied");
        require("native_scheduler_bound".equals(dispatch.get("bindingMode")), "binding mode must be native");
        Map<?, ?> receipt = (Map<?, ?>) dispatch.get("executionReceipt");
        require(Boolean.TRUE.equals(receipt.get("nativeBindingApplied")), "receipt must expose native binding");
        require(Boolean.TRUE.equals(receipt.get("native_scheduler_bound")), "receipt must expose native mode");
        System.out.println("PersistentNativeBindingContractTest OK");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
