package edu.weijunyong.satedgesim.server;

import java.util.Map;

/**
 * Backward-compatible input type for the v2 persistent-rule API.
 * The active runtime state is {@link ExecutionConfiguration}; this subtype
 * keeps existing clients and tests source-compatible.
 */
public final class PersistentExecutionConfiguration extends ExecutionConfiguration {
    public static PersistentExecutionConfiguration fromRequest(Map<String, Object> request) {
        PersistentExecutionConfiguration result = new PersistentExecutionConfiguration();
        populateFromRequest(result, request);
        return result;
    }
}
