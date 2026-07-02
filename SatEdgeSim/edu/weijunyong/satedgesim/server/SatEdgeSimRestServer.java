package edu.weijunyong.satedgesim.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/**
 * Long-running REST server wrapper for SatEdgeSim.
 *
 * Endpoints:
 *   POST /reset       -> create and start a simulation session
 *   GET  /get_state   -> get the current blocked decision state
 *   POST /step        -> submit RL action and wait for the next decision
 *   GET  /get_metrics -> get aggregate metrics
 *   POST /close       -> close the active session
 */
public class SatEdgeSimRestServer {
    private final Gson gson = new Gson();
    private final ServerConfig config;
    private final Object sessionLock = new Object();
    private volatile SatEdgeSimSession session;

    public SatEdgeSimRestServer(ServerConfig config) {
        this.config = config;
    }

    public static void main(String[] args) throws Exception {
        ServerConfig config = new ServerConfig();
        for (int i = 0; i < args.length; i++) {
            if ("--host".equals(args[i]) && i + 1 < args.length) {
                config.host = args[++i];
            } else if ("--port".equals(args[i]) && i + 1 < args.length) {
                config.port = Integer.parseInt(args[++i]);
            } else if ("--sim-config".equals(args[i]) && i + 1 < args.length) {
                config.simConfigFile = args[++i];
            }
        }
        new SatEdgeSimRestServer(config).start();
    }

    public void start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(config.host, config.port), 0);
        server.createContext("/reset", new JsonHandler() {
            @Override
            protected JsonResponse handleJson(HttpExchange exchange, String body) throws Exception {
                requireMethod(exchange, "POST");
                ResetRequest request = body.trim().isEmpty() ? new ResetRequest() : gson.fromJson(body, ResetRequest.class);
                SatEdgeSimSession newSession;
                synchronized (sessionLock) {
                    if (session != null) {
                        session.close();
                    }
                    newSession = new SatEdgeSimSession(config, request);
                    session = newSession;
                }
                newSession.start();
                return ok(newSession.getState());
            }
        });
        server.createContext("/get_state", new JsonHandler() {
            @Override
            protected JsonResponse handleJson(HttpExchange exchange, String body) {
                requireMethod(exchange, "GET");
                ensureSession();
                return ok(session.getState());
            }
        });
        server.createContext("/step", new JsonHandler() {
            @Override
            protected JsonResponse handleJson(HttpExchange exchange, String body) {
                requireMethod(exchange, "POST");
                ensureSession();
                StepRequest request = gson.fromJson(body, StepRequest.class);
                RlAction action;
                long waitTimeoutMs = 30000L;
                if (request != null && request.action != null) {
                    action = request.action;
                    waitTimeoutMs = request.waitTimeoutMs;
                } else {
                    action = gson.fromJson(body, RlAction.class);
                }
                return ok(session.step(action, waitTimeoutMs));
            }
        });
        server.createContext("/apply_action", new JsonHandler() {
            @Override
            protected JsonResponse handleJson(HttpExchange exchange, String body) {
                requireMethod(exchange, "POST");
                ensureSession();
                StepRequest request = gson.fromJson(body, StepRequest.class);
                RlAction action;
                if (request != null && request.action != null) {
                    action = request.action;
                } else {
                    action = gson.fromJson(body, RlAction.class);
                }
                ExecutionReceipt receipt = session.applyAction(action);
                return receiptResponse(receipt);
            }
        });
        server.createContext("/get_metrics", new JsonHandler() {
            @Override
            protected JsonResponse handleJson(HttpExchange exchange, String body) {
                requireMethod(exchange, "GET");
                ensureSession();
                return ok(session.readMetrics());
            }
        });
        server.createContext("/close", new JsonHandler() {
            @Override
            protected JsonResponse handleJson(HttpExchange exchange, String body) {
                requireMethod(exchange, "POST");
                synchronized (sessionLock) {
                    if (session != null) {
                        session.close();
                        session = null;
                    }
                }
                Map<String, Object> response = new LinkedHashMap<String, Object>();
                response.put("status", "CLOSED");
                return ok(response);
            }
        });
        server.createContext("/health", new JsonHandler() {
            @Override
            protected JsonResponse handleJson(HttpExchange exchange, String body) {
                if (session == null) {
                    Map<String, Object> response = new LinkedHashMap<String, Object>();
                    response.put("ok", true);
                    response.put("serverTimeMs", System.currentTimeMillis());
                    response.put("scenarioProfile", null);
                    response.put("taskSourceMode", null);
                    response.put("currentDecisionId", null);
                    response.put("currentTaskId", null);
                    return ok(response);
                }
                return ok(session.getHealthPayload());
            }
        });
        server.createContext("/debug/current_decision", new JsonHandler() {
            @Override
            protected JsonResponse handleJson(HttpExchange exchange, String body) {
                requireMethod(exchange, "GET");
                ensureSession();
                return ok(session.getCurrentDecisionDebug());
            }
        });
        server.createContext("/debug/last_receipt", new JsonHandler() {
            @Override
            protected JsonResponse handleJson(HttpExchange exchange, String body) {
                requireMethod(exchange, "GET");
                ensureSession();
                return ok(session.getLastReceiptDebug());
            }
        });
        server.createContext("/debug/receipt_stats", new JsonHandler() {
            @Override
            protected JsonResponse handleJson(HttpExchange exchange, String body) {
                requireMethod(exchange, "GET");
                ensureSession();
                return ok(session.getReceiptStats());
            }
        });
        int threads = Math.max(8, Runtime.getRuntime().availableProcessors() * 2);
        server.setExecutor((ThreadPoolExecutor) Executors.newFixedThreadPool(threads));
        server.start();
        System.out.println("SatEdgeSim REST server started at http://" + config.host + ":" + config.port);
    }

    private void ensureSession() {
        if (session == null) {
            throw new IllegalStateException("no active session; call POST /reset first");
        }
    }

    private void requireMethod(HttpExchange exchange, String expected) {
        if (!expected.equalsIgnoreCase(exchange.getRequestMethod())) {
            throw new IllegalArgumentException("expected HTTP " + expected + " but got " + exchange.getRequestMethod());
        }
    }

    private abstract class JsonHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                addCors(exchange);
                if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                    sendRaw(exchange, 204, "");
                    return;
                }
                String body = readBody(exchange);
                JsonResponse result = handleJson(exchange, body == null ? "" : body);
                sendJson(exchange, result == null ? 200 : result.statusCode, result == null ? new LinkedHashMap<String, Object>() : result.body);
            } catch (Throwable t) {
                t.printStackTrace(System.err);
                sendErrorJson(exchange, statusCodeForThrowable(t), errorCodeForThrowable(t), t.getMessage());
            }
        }

        protected abstract JsonResponse handleJson(HttpExchange exchange, String body) throws Exception;
    }

    private void addCors(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    }

    private String readBody(HttpExchange exchange) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private void sendRaw(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length);
        OutputStream os = exchange.getResponseBody();
        os.write(bytes);
        os.close();
    }

    private void sendJson(HttpExchange exchange, int code, Object body) throws IOException {
        String payload = gson.toJson(body);
        try {
            sendRaw(exchange, code, payload);
        } catch (IOException e) {
            if (session != null && exchange.getRequestURI() != null && "/apply_action".equals(exchange.getRequestURI().getPath())) {
                session.recordTimeoutSuspected();
            }
            String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            if (message.contains("broken pipe") || message.contains("断开的管道")) {
                System.err.println("[SatEdgeSimRestServer] broken pipe while writing " + exchange.getRequestURI());
            }
            throw e;
        }
    }

    private void sendErrorJson(HttpExchange exchange, int code, String errorCode, String message) throws IOException {
        Map<String, Object> error = new LinkedHashMap<String, Object>();
        error.put("status", "ERROR");
        error.put("code", errorCode);
        error.put("message", message == null ? "" : message);
        sendJson(exchange, code, error);
    }

    private JsonResponse ok(Object body) {
        return new JsonResponse(200, body);
    }

    private JsonResponse receiptResponse(ExecutionReceipt receipt) {
        if (receipt == null) {
            return new JsonResponse(500, errorBody("missing_receipt", "ExecutionReceipt is null"));
        }
        if (receipt.accepted) {
            return new JsonResponse(200, receipt);
        }
        String fallbackReason = receipt.fallbackReason == null ? "" : receipt.fallbackReason;
        if ("stale_decision_id".equals(fallbackReason) || "task_id_mismatch".equals(fallbackReason)) {
            return new JsonResponse(409, receipt);
        }
        if ("action_not_visible".equals(fallbackReason) || "invalid_selected_candidate".equals(fallbackReason)
                || "unknown_target_vm_id".equals(fallbackReason) || "no_action_target".equals(fallbackReason)) {
            return new JsonResponse(400, receipt);
        }
        return new JsonResponse(409, receipt);
    }

    private Map<String, Object> errorBody(String code, String message) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("status", "ERROR");
        body.put("code", code);
        body.put("message", message == null ? "" : message);
        return body;
    }

    private int statusCodeForThrowable(Throwable t) {
        if (t instanceof IllegalArgumentException) {
            return 400;
        }
        if (t instanceof IllegalStateException) {
            return 409;
        }
        return 500;
    }

    private String errorCodeForThrowable(Throwable t) {
        if (t instanceof IllegalArgumentException) {
            return "bad_request";
        }
        if (t instanceof IllegalStateException) {
            return "invalid_state";
        }
        return "server_error";
    }

    private static class JsonResponse {
        final int statusCode;
        final Object body;

        JsonResponse(int statusCode, Object body) {
            this.statusCode = statusCode;
            this.body = body;
        }
    }

    private static class StepRequest {
        RlAction action;
        long waitTimeoutMs = 30000L;
    }
}
