package com.swiftcab;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Embedded Java HTTP server + simulation tick scheduler for SwiftCab Booking Simulator.
 */
public final class Main {

    private static final Pattern RIDE_ACTION_PATTERN =
            Pattern.compile("^/api/rides/([^/]+)/(verify-otp|cancel|chat|rate)$");

    private final SimulationEngine engine;
    private final Path staticDir;
    private HttpServer server;
    private ScheduledExecutorService scheduler;

    public Main(SimulationEngine engine, Path staticDir) {
        this.engine = engine;
        this.staticDir = staticDir.toAbsolutePath().normalize();
    }

    public int start(String host, int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::handleRequest);
        server.start();

        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {
            try {
                engine.tick(0.1);
            } catch (Exception e) {
                System.err.println("Simulation tick error: " + e.getMessage());
            }
        }, 100, 100, TimeUnit.MILLISECONDS);

        return server.getAddress().getPort();
    }

    public void stop() {
        if (scheduler != null) scheduler.shutdownNow();
        if (server != null) server.stop(0);
    }

    private void handleRequest(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();

        try {
            if ("OPTIONS".equalsIgnoreCase(method)) {
                addCors(ex);
                ex.sendResponseHeaders(204, -1);
                return;
            }

            if (path.startsWith("/api/")) {
                handleApi(ex, method, path);
            } else {
                handleStatic(ex, path);
            }
        } catch (IllegalArgumentException badReq) {
            sendJson(ex, 400, Map.of("detail", badReq.getMessage()));
        } catch (Exception err) {
            err.printStackTrace();
            sendJson(ex, 500, Map.of("detail", "Internal server error: " + err.getMessage()));
        } finally {
            ex.close();
        }
    }

    private void handleApi(HttpExchange ex, String method, String path) throws IOException {
        if ("GET".equals(method) && "/api/health".equals(path)) {
            sendJson(ex, 200, Map.of("status", "ok"));
            return;
        }
        if ("GET".equals(method) && "/api/init".equals(path)) {
            sendJson(ex, 200, engine.getInitPayload());
            return;
        }
        if ("GET".equals(method) && "/api/state".equals(path)) {
            sendJson(ex, 200, engine.getState());
            return;
        }
        if ("POST".equals(method) && "/api/estimate".equals(path)) {
            Map<String, Object> body = readJsonBody(ex);
            String pickup = requireString(body, "pickup_node_id");
            String dropoff = requireString(body, "dropoff_node_id");
            String promo = JsonUtil.getString(body, "promo_code", null);
            sendJson(ex, 200, engine.estimateRide(pickup, dropoff, promo));
            return;
        }
        if ("POST".equals(method) && "/api/rides/book".equals(path)) {
            Map<String, Object> body = readJsonBody(ex);
            String pickup = requireString(body, "pickup_node_id");
            String dropoff = requireString(body, "dropoff_node_id");
            String tier = requireString(body, "tier");
            String pay = JsonUtil.getString(body, "payment_method", "wallet");
            String promo = JsonUtil.getString(body, "promo_code", null);
            Models.Ride ride = engine.bookRide(pickup, dropoff, tier, pay, promo);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("ride", ride.toMap(engine.getCabs(), engine.graph, SimulationEngine.RIDE_TIERS));
            resp.put("state", engine.getState());
            sendJson(ex, 200, resp);
            return;
        }
        if ("POST".equals(method) && "/api/rides/clear".equals(path)) {
            engine.clearActiveRide();
            sendJson(ex, 200, engine.getState());
            return;
        }

        Matcher m = RIDE_ACTION_PATTERN.matcher(path);
        if ("POST".equals(method) && m.matches()) {
            String rideId = m.group(1);
            String action = m.group(2);
            Map<String, Object> body = readJsonBody(ex);
            switch (action) {
                case "verify-otp" -> {
                    String otp = requireString(body, "otp");
                    Models.Ride ride = engine.verifyOtp(rideId, otp);
                    sendJson(ex, 200, rideAndState(ride));
                }
                case "cancel" -> {
                    String reason = JsonUtil.getString(body, "reason", "Changed plans");
                    Models.Ride ride = engine.cancelRide(rideId, reason);
                    sendJson(ex, 200, rideAndState(ride));
                }
                case "chat" -> {
                    String msg = requireString(body, "message");
                    Models.Ride ride = engine.sendChat(rideId, msg);
                    sendJson(ex, 200, Map.of("ride", ride.toMap(engine.getCabs(), engine.graph, SimulationEngine.RIDE_TIERS)));
                }
                case "rate" -> {
                    Integer stars = JsonUtil.getInt(body, "stars");
                    if (stars == null) throw new IllegalArgumentException("stars is required.");
                    List<String> tags = JsonUtil.getStringList(body, "tags");
                    Double tipVal = JsonUtil.getDouble(body, "tip");
                    double tip = tipVal == null ? 0.0 : tipVal;
                    Models.Ride ride = engine.rateRide(rideId, stars, tags, tip);
                    sendJson(ex, 200, rideAndState(ride));
                }
                default -> sendJson(ex, 404, Map.of("detail", "Unknown action"));
            }
            return;
        }

        if ("POST".equals(method) && "/api/sim/controls".equals(path)) {
            Map<String, Object> body = readJsonBody(ex);
            engine.updateControls(
                    JsonUtil.getDouble(body, "speed"),
                    JsonUtil.getString(body, "traffic_level", null),
                    JsonUtil.getString(body, "surge_mode", null),
                    JsonUtil.getBoolean(body, "simulate_driver_decline")
            );
            sendJson(ex, 200, engine.getState());
            return;
        }
        if ("POST".equals(method) && "/api/sim/spawn-cab".equals(path)) {
            Map<String, Object> body = readJsonBody(ex);
            Models.Cab cab = engine.spawnCab(
                    JsonUtil.getString(body, "tier", null),
                    JsonUtil.getString(body, "node_id", null)
            );
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("cab", cab.toMap());
            resp.put("state", engine.getState());
            sendJson(ex, 200, resp);
            return;
        }
        if ("POST".equals(method) && "/api/sim/reset".equals(path)) {
            engine.reset();
            sendJson(ex, 200, engine.getState());
            return;
        }
        if ("POST".equals(method) && "/api/wallet/topup".equals(path)) {
            Map<String, Object> body = readJsonBody(ex);
            Double amount = JsonUtil.getDouble(body, "amount");
            if (amount == null) throw new IllegalArgumentException("amount is required.");
            double balance = engine.topupWallet(amount);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("wallet_balance", balance);
            resp.put("state", engine.getState());
            sendJson(ex, 200, resp);
            return;
        }

        sendJson(ex, 404, Map.of("detail", "Endpoint not found: " + method + " " + path));
    }

    private Map<String, Object> rideAndState(Models.Ride ride) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ride", ride.toMap(engine.getCabs(), engine.graph, SimulationEngine.RIDE_TIERS));
        resp.put("state", engine.getState());
        return resp;
    }

    private void handleStatic(HttpExchange ex, String path) throws IOException {
        String rel = "/".equals(path) ? "index.html" : path.replaceFirst("^/static/?", "");
        if (rel.contains("..")) {
            sendText(ex, 403, "Forbidden", "text/plain");
            return;
        }
        Path file = staticDir.resolve(rel).normalize();
        if (!file.startsWith(staticDir) || !Files.isRegularFile(file)) {
            sendText(ex, 404, "Not Found", "text/plain");
            return;
        }
        byte[] bytes = Files.readAllBytes(file);
        String contentType = contentTypeFor(file.getFileName().toString());
        addCors(ex);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String contentTypeFor(String name) {
        if (name.endsWith(".html")) return "text/html; charset=utf-8";
        if (name.endsWith(".css")) return "text/css; charset=utf-8";
        if (name.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (name.endsWith(".json")) return "application/json; charset=utf-8";
        if (name.endsWith(".svg")) return "image/svg+xml";
        if (name.endsWith(".png")) return "image/png";
        return "application/octet-stream";
    }

    private static Map<String, Object> readJsonBody(HttpExchange ex) throws IOException {
        try (InputStream is = ex.getRequestBody()) {
            String raw = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            return JsonUtil.parseObject(raw);
        }
    }

    private static String requireString(Map<String, Object> map, String key) {
        String val = JsonUtil.getString(map, key, null);
        if (val == null || val.isBlank()) {
            throw new IllegalArgumentException("Missing required field: " + key);
        }
        return val;
    }

    private static void addCors(HttpExchange ex) {
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
    }

    private static void sendJson(HttpExchange ex, int status, Object payload) throws IOException {
        byte[] bytes = JsonUtil.toJson(payload).getBytes(StandardCharsets.UTF_8);
        addCors(ex);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void sendText(HttpExchange ex, int status, String text, String contentType) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        addCors(ex);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    public static void main(String[] args) throws IOException {
        String host = System.getenv().getOrDefault("HOST", "0.0.0.0");
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        Path staticPath = Path.of(System.getenv().getOrDefault("STATIC_DIR", "static"));

        Main app = new Main(new SimulationEngine(), staticPath);
        int actualPort = app.start("127.0.0.1".equals(host) ? "127.0.0.1" : host, port);
        System.out.println("SwiftCab Java Simulator running on http://" + host + ":" + actualPort);
    }
}
