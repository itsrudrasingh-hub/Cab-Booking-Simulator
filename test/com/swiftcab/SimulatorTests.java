package com.swiftcab;

import com.swiftcab.CityGraph.RoadEdge;
import com.swiftcab.CityGraph.RouteResult;
import com.swiftcab.Models.Ride;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Dependency-free Java unit and HTTP integration tests for SwiftCab Simulator.
 */
public final class SimulatorTests {

    private static int passed = 0;

    public static void main(String[] args) throws Exception {
        testCityGraphBridgeRouting();
        testTrafficIncreasesDuration();
        testEstimateAndPromoCodes();
        testFullRideLifecycle();
        testDriverDeclineReassignsNextCab();
        testCancellationFeeAfterArrival();
        testHttpEndpoints();
        System.out.println("\nALL " + passed + " JAVA TESTS PASSED.");
    }

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) throw new AssertionError("Assertion failed: " + msg);
    }

    private static void assertEquals(Object expected, Object actual, String msg) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(msg + " — expected: " + expected + ", actual: " + actual);
        }
    }

    private static void testCityGraphBridgeRouting() {
        CityGraph graph = new CityGraph();
        assertTrue(graph.getLandmarks().size() >= 12, "Expected >= 12 landmarks");
        RouteResult res = graph.findShortestPath("N2_3", "N4_2", 1.0);
        assertEquals("N2_3", res.path().get(0), "Start node");
        assertEquals("N4_2", res.path().get(res.path().size() - 1), "End node");
        assertTrue(res.distanceKm() > 0.5, "Distance should be positive");

        boolean crossedBridge = false;
        for (int i = 0; i < res.path().size() - 1; i++) {
            RoadEdge e = graph.getEdge(res.path().get(i), res.path().get(i + 1));
            if (e != null && e.isBridge()) {
                crossedBridge = true;
                break;
            }
        }
        assertTrue(crossedBridge, "Route across river must traverse a bridge");
        passed++;
        System.out.println("[OK] testCityGraphBridgeRouting");
    }

    private static void testTrafficIncreasesDuration() {
        CityGraph graph = new CityGraph();
        RouteResult low = graph.findShortestPath("N2_3", "N0_6", 1.0);
        RouteResult heavy = graph.findShortestPath("N2_3", "N0_6", 1.9);
        assertEquals(low.distanceKm(), heavy.distanceKm(), "Distance unchanged");
        assertTrue(heavy.durationMin() > low.durationMin(), "Heavy traffic increases duration");
        passed++;
        System.out.println("[OK] testTrafficIncreasesDuration");
    }

    @SuppressWarnings("unchecked")
    private static void testEstimateAndPromoCodes() {
        SimulationEngine sim = new SimulationEngine(42L);
        sim.updateControls(null, null, "flat", null);
        Map<String, Object> estPlain = sim.estimateRide("N2_3", "N0_6", null);
        Map<String, Object> estPromo = sim.estimateRide("N2_3", "N0_6", "FIRST50");

        assertEquals(1.0, estPlain.get("surge_multiplier"), "Flat surge is 1.0");
        assertEquals("FIRST50", estPromo.get("promo_applied"), "Promo applied");

        List<Map<String, Object>> plainTiers = (List<Map<String, Object>>) estPlain.get("tiers");
        List<Map<String, Object>> promoTiers = (List<Map<String, Object>>) estPromo.get("tiers");
        double plainFare = ((Number) plainTiers.get(0).get("estimated_fare")).doubleValue();
        double promoFare = ((Number) promoTiers.get(0).get("estimated_fare")).doubleValue();
        assertTrue(promoFare < plainFare, "Promo should reduce estimated fare");

        boolean threw = false;
        try {
            sim.estimateRide("N2_3", "N2_3", null);
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        assertTrue(threw, "Same pickup & dropoff must throw");
        passed++;
        System.out.println("[OK] testEstimateAndPromoCodes");
    }

    private static void testFullRideLifecycle() {
        SimulationEngine sim = new SimulationEngine(42L);
        double initialWallet = sim.getWalletBalance();
        Ride ride = sim.bookRide("N2_3", "N0_6", "mini", "wallet", null);
        assertEquals("SEARCHING", ride.status, "Initial status SEARCHING");

        for (int i = 0; i < 30 && !"DRIVER_ASSIGNED".equals(ride.status); i++) {
            sim.tick(0.2);
        }
        assertEquals("DRIVER_ASSIGNED", ride.status, "Driver assigned");
        assertTrue(ride.cabId != null, "Cab assigned");

        sim.updateControls(10.0, null, null, null);
        for (int i = 0; i < 200 && !"ARRIVED".equals(ride.status); i++) {
            sim.tick(0.2);
        }
        assertEquals("ARRIVED", ride.status, "Driver arrived");

        String wrongOtp = "0000".equals(ride.otp) ? "9999" : "0000";
        boolean otpThrew = false;
        try {
            sim.verifyOtp(ride.id, wrongOtp);
        } catch (IllegalArgumentException expected) {
            otpThrew = true;
        }
        assertTrue(otpThrew, "Wrong OTP rejected");

        sim.verifyOtp(ride.id, ride.otp);
        assertEquals("IN_TRIP", ride.status, "Trip started");

        sim.sendChat(ride.id, "Please take the expressway!");
        assertEquals("driver", ride.chatMessages.get(ride.chatMessages.size() - 1).sender(), "Driver replied");

        for (int i = 0; i < 300 && !"COMPLETED".equals(ride.status); i++) {
            sim.tick(0.2);
        }
        assertEquals("COMPLETED", ride.status, "Ride completed");
        assertTrue(sim.getWalletBalance() < initialWallet, "Wallet deducted");
        assertEquals("AVAILABLE", sim.getCabs().get(ride.cabId).status, "Cab released");
        assertEquals(1, sim.getRideHistory().size(), "History recorded");

        double beforeTip = sim.getWalletBalance();
        sim.rateRide(ride.id, 5, List.of("Smooth Drive"), 2.0);
        assertEquals(5, ride.ratingGiven, "Rating saved");
        assertEquals(Models.round2(beforeTip - 2.0), sim.getWalletBalance(), "Tip deducted");
        passed++;
        System.out.println("[OK] testFullRideLifecycle");
    }

    private static void testDriverDeclineReassignsNextCab() {
        SimulationEngine sim = new SimulationEngine(42L);
        sim.updateControls(null, null, null, true);
        Ride ride = sim.bookRide("N2_3", "N2_2", "sedan", "card", null);
        for (int i = 0; i < 40 && !"DRIVER_ASSIGNED".equals(ride.status); i++) {
            sim.tick(0.2);
        }
        assertEquals("DRIVER_ASSIGNED", ride.status, "Reassigned after decline");
        assertEquals(1, ride.rejectedCabIds.size(), "One cab rejected");
        assertTrue(!ride.rejectedCabIds.contains(ride.cabId), "Assigned cab differs from rejected cab");
        passed++;
        System.out.println("[OK] testDriverDeclineReassignsNextCab");
    }

    private static void testCancellationFeeAfterArrival() {
        SimulationEngine sim = new SimulationEngine(42L);
        sim.updateControls(10.0, null, null, null);
        Ride ride = sim.bookRide("N1_1", "N2_2", "ev", "wallet", null);
        double before = sim.getWalletBalance();

        for (int i = 0; i < 250 && !"ARRIVED".equals(ride.status); i++) {
            sim.tick(0.2);
        }
        assertEquals("ARRIVED", ride.status, "Driver arrived");
        assertTrue(ride.wouldIncurCancelFee(), "Cancel fee applies");

        Ride cancelled = sim.cancelRide(ride.id, "Plan changed");
        assertEquals("CANCELLED", cancelled.status, "Status cancelled");
        assertEquals(2.50, cancelled.cancellationFee, "Cancel fee $2.50");
        assertEquals(Models.round2(before - 2.50), sim.getWalletBalance(), "Fee deducted");
        assertEquals("AVAILABLE", sim.getCabs().get(ride.cabId).status, "Cab released");
        passed++;
        System.out.println("[OK] testCancellationFeeAfterArrival");
    }

    @SuppressWarnings("unchecked")
    private static void testHttpEndpoints() throws Exception {
        SimulationEngine sim = new SimulationEngine(99L);
        Main serverApp = new Main(sim, Path.of("static"));
        int port = serverApp.start("127.0.0.1", 0);
        HttpClient client = HttpClient.newHttpClient();
        String base = "http://127.0.0.1:" + port;

        try {
            HttpResponse<String> htmlResp = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, htmlResp.statusCode(), "GET /");

            HttpResponse<String> healthResp = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, healthResp.statusCode(), "GET /api/health");

            HttpResponse<String> initResp = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/init")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, initResp.statusCode(), "GET /api/init");
            Map<String, Object> initMap = JsonUtil.parseObject(initResp.body());
            assertTrue(initMap.containsKey("city") && initMap.containsKey("tiers"), "Init keys");

            String estBody = "{\"pickup_node_id\":\"N2_3\",\"dropoff_node_id\":\"N0_6\",\"promo_code\":\"SAVE20\"}";
            HttpResponse<String> estResp = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/estimate"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(estBody))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, estResp.statusCode(), "POST /api/estimate");
            Map<String, Object> estMap = JsonUtil.parseObject(estResp.body());
            assertEquals("SAVE20", estMap.get("promo_applied"), "Promo SAVE20");

            HttpResponse<String> topupResp = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/wallet/topup"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"amount\":25}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, topupResp.statusCode(), "POST /api/wallet/topup");
            assertEquals(110.0, ((Number) JsonUtil.parseObject(topupResp.body()).get("wallet_balance")).doubleValue(), "Wallet 110");

            String bookBody = "{\"pickup_node_id\":\"N2_3\",\"dropoff_node_id\":\"N0_6\",\"tier\":\"mini\",\"payment_method\":\"wallet\"}";
            HttpResponse<String> bookResp = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/rides/book"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(bookBody))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, bookResp.statusCode(), "POST /api/rides/book");
            Map<String, Object> rideMap = (Map<String, Object>) JsonUtil.parseObject(bookResp.body()).get("ride");
            String rideId = (String) rideMap.get("id");

            HttpResponse<String> cancelResp = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/rides/" + rideId + "/cancel"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"reason\":\"Test cleanup\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, cancelResp.statusCode(), "POST cancel");
            passed++;
            System.out.println("[OK] testHttpEndpoints");
        } finally {
            serverApp.stop();
        }
    }
}
