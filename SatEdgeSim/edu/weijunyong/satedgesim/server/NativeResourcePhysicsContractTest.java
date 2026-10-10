package edu.weijunyong.satedgesim.server;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.cloudbus.cloudsim.brokers.DatacenterBrokerSimple;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.datacenters.DatacenterSimple;
import org.cloudbus.cloudsim.hosts.HostSimple;
import org.cloudbus.cloudsim.resources.PeSimple;
import org.cloudbus.cloudsim.schedulers.vm.VmSchedulerTimeShared;
import org.cloudbus.cloudsim.vms.Vm;
import org.cloudbus.cloudsim.vms.VmSimple;
import org.cloudbus.cloudsim.utilizationmodels.UtilizationModelDynamic;
import org.cloudbus.cloudsim.utilizationmodels.UtilizationModel;

import edu.weijunyong.satedgesim.TasksGenerator.Task;

/**
 * Native CloudSim scheduler contract test.  It does not inject effective
 * allocations: the values are observed from Cloudlet finished-length deltas
 * after the real VM scheduler processes concurrent Cloudlets.
 */
public final class NativeResourcePhysicsContractTest {
    private NativeResourcePhysicsContractTest() {
    }

    public static void main(String[] args) {
        double one = runContentionCase(1);
        double two = runContentionCase(2);
        runContentionCase(4);
        runContentionCase(8);
        runPostAllocationRebindCase();
        runMidSimulationRebindCase();
        runCloudletUtilizationCase();
        runReleaseRestoresTaskDemandCase();
        require(one > two + 1.0e-6, "CloudSim contention must reduce per-task effective service");
        System.out.println("NativeResourcePhysicsContractTest OK (CloudSim native scheduler)");
    }

    /**
     * Regression for the production ordering: SatEdgeSim VMs are already
     * placed on a Host when a persistent decision binds task resources.
     * The production callback runs after VM placement.  The task-local demand
     * binding must affect native Cloudlet service without rewriting the Host's
     * VM capacity allocation.
     */
    private static void runPostAllocationRebindCase() {
        RlNativeResourceBindingManager.resetForSimulation();
        CloudSim simulation = new CloudSim();
        HostSimple host = new HostSimple(16384L, 100000L, 1000000L,
                Arrays.asList(new PeSimple(1000.0)));
        host.setVmScheduler(new VmSchedulerTimeShared());
        new DatacenterSimple(simulation, Arrays.asList(host)).setSchedulingInterval(1.0);
        DatacenterBrokerSimple broker = new DatacenterBrokerSimple(simulation);
        Vm vm = new VmSimple(92L, 1000.0, 1L);
        Task task = new Task(2000, 100000L, 1L);
        RlAction action = new RlAction();
        action.cpuShare = 0.5;
        RlResourceProfile profile = RlResourceProfile.fromAction(
                action, RlResourceBindingMode.native_scheduler_bound);
        final double[] allocationAfterBinding = new double[] {-1.0};
        final double[] taskDemandAfterBinding = new double[] {-1.0};
        vm.addOnHostAllocationListener(info -> {
            require(host.getTotalAllocatedMipsForVm(vm) > 999.0,
                    "precondition requires the Host scheduler's full VM allocation");
            RlNativeResourceBindingManager.bindTask(task, vm, 0, profile, info.getTime());
            allocationAfterBinding[0] = host.getTotalAllocatedMipsForVm(vm);
            taskDemandAfterBinding[0] = task.getUtilizationModelCpu().getUtilization(info.getTime());
        });
        broker.submitVm(vm);
        broker.submitCloudlet(task);
        simulation.start();
        require(Math.abs(allocationAfterBinding[0] - 1000.0) <= 1.0e-6,
                "task-local binding must not rewrite VM capacity, allocated=" + allocationAfterBinding[0]);
        require(Math.abs(taskDemandAfterBinding[0] - 0.5) <= 1.0e-6,
                "post-allocation binding must update native Cloudlet CPU demand");
        require(task.getFinishTime() >= 190.0,
                "half-share demand must reduce native Cloudlet service, finish=" + task.getFinishTime());
    }

    private static void runMidSimulationRebindCase() {
        RlNativeResourceBindingManager.resetForSimulation();
        CloudSim simulation = new CloudSim();
        HostSimple host = new HostSimple(16384L, 100000L, 1000000L,
                Arrays.asList(new PeSimple(1000.0)));
        host.setVmScheduler(new VmSchedulerTimeShared());
        new DatacenterSimple(simulation, Arrays.asList(host)).setSchedulingInterval(1.0);
        DatacenterBrokerSimple broker = new DatacenterBrokerSimple(simulation);
        Vm vm = new VmSimple(93L, 1000.0, 1L);
        Task task = new Task(2001, 100000L, 1L);
        RlAction action = new RlAction();
        action.cpuShare = 0.5;
        RlResourceProfile profile = RlResourceProfile.fromAction(
                action, RlResourceBindingMode.native_scheduler_bound);
        final boolean[] rebound = new boolean[] {false};
        simulation.addOnSimulationPauseListener(info -> {
            RlNativeResourceBindingManager.bindTask(task, vm, 0, profile, info.getTime());
            rebound[0] = true;
            simulation.resume();
        });
        broker.submitVm(vm);
        broker.submitCloudlet(task);
        simulation.pause(1.0);
        simulation.start();
        require(rebound[0], "mid-simulation native binding callback did not execute");
        require(task.getFinishedLengthSoFar() >= task.getLength(),
                "mid-simulation CPU binding must preserve Cloudlet execution");
        require(task.getFinishTime() >= 190.0,
                "half-share late binding must reduce native Cloudlet service, finish=" + task.getFinishTime());
    }

    private static void runCloudletUtilizationCase() {
        CloudSim simulation = new CloudSim();
        HostSimple host = new HostSimple(16384L, 100000L, 1000000L,
                Arrays.asList(new PeSimple(1000.0)));
        host.setVmScheduler(new VmSchedulerTimeShared());
        new DatacenterSimple(simulation, Arrays.asList(host)).setSchedulingInterval(1.0);
        DatacenterBrokerSimple broker = new DatacenterBrokerSimple(simulation);
        Vm vm = new VmSimple(94L, 1000.0, 1L);
        Task task = new Task(2002, 100000L, 1L);
        task.setUtilizationModelCpu(new UtilizationModelDynamic(0.5));
        broker.submitVm(vm);
        broker.submitCloudlet(task);
        simulation.start();
        require(task.getFinishTime() >= 190.0,
                "native Cloudlet CPU utilization share must affect service, finish=" + task.getFinishTime());
    }

    private static void runReleaseRestoresTaskDemandCase() {
        RlNativeResourceBindingManager.resetForSimulation();
        Vm vm = new VmSimple(95L, 1000.0, 1L);
        Task task = new Task(2003, 100000L, 1L);
        UtilizationModel original = task.getUtilizationModelCpu();
        RlAction action = new RlAction();
        action.cpuShare = 0.25;
        RlResourceProfile profile = RlResourceProfile.fromAction(
                action, RlResourceBindingMode.native_scheduler_bound);
        RlNativeResourceBindingManager.bindTask(task, vm, 0, profile, 0.0);
        require(Math.abs(task.getUtilizationModelCpu().getUtilization(0.0) - 0.25) <= 1.0e-6,
                "binding must install the requested task demand");
        RlNativeResourceBindingManager.releaseTask(task, null);
        require(task.getUtilizationModelCpu() == original,
                "release/rollback boundary must restore the exact prior utilization model");
    }

    private static double runContentionCase(int taskCount) {
        RlNativeResourceBindingManager.resetForSimulation();
        CloudSim simulation = new CloudSim();
        HostSimple host = new HostSimple(16384L, 100000L, 1000000L,
                Arrays.asList(new PeSimple(1000.0)));
        host.setVmScheduler(new VmSchedulerTimeShared());
        new DatacenterSimple(simulation, Arrays.asList(host)).setSchedulingInterval(1.0);
        DatacenterBrokerSimple broker = new DatacenterBrokerSimple(simulation);
        Vm vm = new VmSimple(91L, 1000.0, 1L);
        List<Task> tasks = new ArrayList<Task>();
        RlAction action = new RlAction();
        action.cpuShare = 1.0;
        RlResourceProfile profile = RlResourceProfile.fromAction(action, RlResourceBindingMode.native_scheduler_bound);
        for (int index = 0; index < taskCount; index++) {
            Task task = new Task(1000 + index, 100000L, 1L);
            tasks.add(task);
            RlNativeResourceBindingManager.bindTask(task, vm, 0, profile, 0.0);
        }
        broker.submitVm(vm);
        broker.submitCloudletList(tasks);
        RlNativeResourceBindingManager.observeRuntimeProgress(tasks, 0.0);
        simulation.start();
        RlNativeResourceBindingManager.observeRuntimeProgress(tasks, simulation.clock());
        Map<String, Object> evidence = RlNativeResourceBindingManager.runtimeConservationEvidence();
        require(Boolean.TRUE.equals(evidence.get("observed")), "CPU runtime sample missing for " + taskCount + " tasks");
        require(Boolean.TRUE.equals(evidence.get("conservationSatisfied")),
                "CPU capacity exceeded for " + taskCount + " tasks");
        double sum = 0.0;
        double perTask = 0.0;
        Object entries = evidence.get("entries");
        require(entries instanceof List && ((List<?>) entries).size() == taskCount,
                "CPU trace must include every bound task");
        for (Object entry : (List<?>) entries) {
            Map<?, ?> item = (Map<?, ?>) entry;
            double effective = ((Number) item.get("effective_cpu_mips")).doubleValue();
            double capacity = ((Number) item.get("cpu_capacity_mips")).doubleValue();
            require(effective <= capacity + 1.0e-6, "task effective CPU exceeds VM capacity");
            require(((Number) item.get("contention_count")).intValue() == taskCount,
                    "contention context must match active Cloudlets");
            sum += effective;
            perTask = effective;
        }
        require(sum <= vm.getMips() + 1.0e-6, "sum effective CPU must not exceed VM MIPS");
        return perTask;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
