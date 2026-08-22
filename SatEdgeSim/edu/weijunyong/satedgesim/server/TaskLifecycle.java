package edu.weijunyong.satedgesim.server;

import java.util.List;

import org.cloudbus.cloudsim.cloudlets.Cloudlet;
import org.cloudbus.cloudsim.vms.Vm;

import edu.weijunyong.satedgesim.Network.FileTransferProgress;
import edu.weijunyong.satedgesim.Network.NetworkModel;
import edu.weijunyong.satedgesim.TasksGenerator.Task;

/**
 * Maps existing CloudSim Cloudlet status and native transfer progression to
 * the publication-facing lifecycle. It does not own or mutate a second state
 * machine.
 */
public final class TaskLifecycle {
    private TaskLifecycle() {
    }

    public static TaskExecutionPhase phase(Task task, NetworkModel networkModel) {
        if (task == null) return TaskExecutionPhase.COMPLETED;
        Cloudlet.Status status = task.getStatus();
        if (status == Cloudlet.Status.SUCCESS || status == Cloudlet.Status.FAILED
                || status == Cloudlet.Status.CANCELED || status == Cloudlet.Status.FAILED_RESOURCE_UNAVAILABLE) {
            if (hasReturningTransfer(task, networkModel)) return TaskExecutionPhase.RETURNING;
            return TaskExecutionPhase.COMPLETED;
        }
        if (hasTransfer(task, networkModel)) {
            return hasReturningTransfer(task, networkModel)
                    ? TaskExecutionPhase.RETURNING : TaskExecutionPhase.TRANSMITTING;
        }
        if (status == Cloudlet.Status.INEXEC || status == Cloudlet.Status.PAUSED) {
            return TaskExecutionPhase.RUNNING;
        }
        if (task.getVm() == null || task.getVm() == Vm.NULL) return TaskExecutionPhase.QUEUED;
        return TaskExecutionPhase.QUEUED;
    }

    private static boolean hasTransfer(Task task, NetworkModel networkModel) {
        if (networkModel == null) return false;
        List<FileTransferProgress> transfers = networkModel.getTransferProgressList();
        if (transfers == null) return false;
        for (FileTransferProgress transfer : transfers) {
            if (transfer != null && transfer.getTask() == task && transfer.getRemainingFileSize() > 0.0) return true;
        }
        return false;
    }

    private static boolean hasReturningTransfer(Task task, NetworkModel networkModel) {
        if (networkModel == null) return false;
        List<FileTransferProgress> transfers = networkModel.getTransferProgressList();
        if (transfers == null) return false;
        for (FileTransferProgress transfer : transfers) {
            if (transfer == null || transfer.getTask() != task || transfer.getRemainingFileSize() <= 0.0) continue;
            FileTransferProgress.Type type = transfer.getTransferType();
            if (type == FileTransferProgress.Type.RESULTS_TO_ORCH || type == FileTransferProgress.Type.RESULTS_TO_DEV) return true;
        }
        return false;
    }
}
