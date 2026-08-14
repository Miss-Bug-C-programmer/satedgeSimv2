package edu.weijunyong.satedgesim.TasksOrchestration;

import org.cloudbus.cloudsim.vms.Vm;

import edu.weijunyong.satedgesim.SimulationManager.SimulationManager;
import edu.weijunyong.satedgesim.TasksGenerator.Task;
import edu.weijunyong.satedgesim.server.RlDecisionBridge;
import edu.weijunyong.satedgesim.server.RlDecisionBridgeRegistry;

/**
 * Orchestrator that delegates each VM-selection decision to an external Python RL
 * controller through RlDecisionBridge. It keeps SatEdgeSim's original task
 * lifecycle and network model unchanged.
 */
public class ExternalRLOrchestrator extends Orchestrator {
    private final RlDecisionBridge bridge;

    public ExternalRLOrchestrator(SimulationManager simulationManager) {
        super(simulationManager);
        this.bridge = RlDecisionBridgeRegistry.get(simulationManager.getSimulationId());
    }

    @Override
    protected int findVM(final String[] architecture, final Task task) {
        if (bridge == null || bridge.isClosed()) {
            return firstFeasibleVm(architecture, task);
        }
        int persistentVm = bridge.resolvePersistentVm(
                simulationManager,
                architecture,
                task,
                vmList,
                new RlDecisionBridge.FeasibilityChecker() {
                    @Override
                    public boolean isFeasible(String[] arch, Task t, Vm vm) {
                        return ExternalRLOrchestrator.this.offloadingIsPossible(t, vm, arch);
                    }
                });
        if (persistentVm >= 0) {
            return persistentVm;
        }
        return bridge.requestDecision(
                simulationManager,
                architecture,
                task,
                vmList,
                orchestrationHistory,
                new RlDecisionBridge.FeasibilityChecker() {
                    @Override
                    public boolean isFeasible(String[] arch, Task t, Vm vm) {
                        return ExternalRLOrchestrator.this.offloadingIsPossible(t, vm, arch);
                    }
                });
    }

    private int firstFeasibleVm(String[] architecture, Task task) {
        for (int i = 0; i < vmList.size(); i++) {
            if (offloadingIsPossible(task, vmList.get(i), architecture)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public void resultsReturned(Task task) {
        if (bridge != null && !bridge.isClosed()) {
            bridge.recordCompletion(task, simulationManager);
        }
    }
}
