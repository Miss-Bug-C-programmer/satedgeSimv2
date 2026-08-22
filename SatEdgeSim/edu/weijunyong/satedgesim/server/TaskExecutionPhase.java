package edu.weijunyong.satedgesim.server;

/** Canonical phase labels derived from the native Cloudlet/network lifecycle. */
public enum TaskExecutionPhase {
    QUEUED,
    TRANSMITTING,
    RUNNING,
    RETURNING,
    COMPLETED
}
