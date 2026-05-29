package edu.weijunyong.satedgesim.server;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class RlDecisionBridgeRegistry {
    private static final ConcurrentMap<Integer, RlDecisionBridge> BRIDGES = new ConcurrentHashMap<Integer, RlDecisionBridge>();

    private RlDecisionBridgeRegistry() {
    }

    public static void register(int simulationId, RlDecisionBridge bridge) {
        BRIDGES.put(simulationId, bridge);
    }

    public static RlDecisionBridge get(int simulationId) {
        return BRIDGES.get(simulationId);
    }

    public static void unregister(int simulationId) {
        BRIDGES.remove(simulationId);
    }
}
