package com.swiftcab;

import com.swiftcab.CityGraph.CityNode;
import com.swiftcab.CityGraph.NeighborEdge;
import com.swiftcab.CityGraph.RoadEdge;
import com.swiftcab.CityGraph.RouteResult;
import com.swiftcab.Models.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Thread-safe cab booking simulation engine in Java.
 */
public final class SimulationEngine {

    public static final double SIM_SECONDS_PER_REAL_SECOND = 8.0;
    private static final DateTimeFormatter CLOCK_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    public static final Map<String, TierConfig> RIDE_TIERS = new LinkedHashMap<>();
    public static final Map<String, PromoConfig> PROMO_CODES = new LinkedHashMap<>();
    public static final Map<String, Double> TRAFFIC_MULTIPLIERS = Map.of(
            "low", 1.0,
            "moderate", 1.38,
            "heavy", 1.90
    );

    private static final List<DriverInfo> DRIVER_ROSTER = List.of(
            new DriverInfo("Marcus Vance", 4.92, "Toyota Corolla", "SC-4012"),
            new DriverInfo("Elena Rostova", 4.96, "Hyundai Ioniq 5", "EV-8821"),
            new DriverInfo("Devon Brooks", 4.84, "Honda Civic", "SC-2190"),
            new DriverInfo("Priya Nair", 4.95, "Kia Niro EV", "EV-3304"),
            new DriverInfo("Liam O'Connor", 4.78, "Suzuki Swift", "SC-1147"),
            new DriverInfo("Sofia Mendoza", 4.91, "Toyota Camry", "PR-7740"),
            new DriverInfo("Noah Takahashi", 4.88, "Nissan Leaf", "EV-5509"),
            new DriverInfo("Amara Okafor", 4.97, "Kia Telluride", "XL-9032"),
            new DriverInfo("Lucas Meyer", 4.81, "VW Polo", "SC-6418"),
            new DriverInfo("Zara Al-Mansoor", 4.93, "Tesla Model 3", "EV-1092"),
            new DriverInfo("Ethan Cole", 4.86, "Skoda Octavia", "PR-4482"),
            new DriverInfo("Hannah Kim", 4.94, "Honda Accord", "PR-2275"),
            new DriverInfo("Mateo Silva", 4.79, "Chevrolet Suburban", "XL-6150"),
            new DriverInfo("Chloe Bennett", 4.89, "Ford Explorer", "XL-3814"),
            new DriverInfo("Arjun Mehta", 4.90, "Hyundai Elantra", "PR-5920"),
            new DriverInfo("Nadia Petrov", 4.85, "Mazda 3", "SC-7305")
    );

    private static final Map<String, List<String>> VEHICLE_BY_TIER = Map.of(
            "mini", List.of("Suzuki Swift", "VW Polo", "Mazda 3", "Toyota Corolla", "Honda Fit"),
            "ev", List.of("Hyundai Ioniq 5", "Tesla Model 3", "Kia Niro EV", "Nissan Leaf"),
            "sedan", List.of("Toyota Camry", "Honda Accord", "Skoda Octavia", "Hyundai Elantra"),
            "suv", List.of("Kia Telluride", "Ford Explorer", "Chevrolet Suburban", "Toyota Highlander")
    );

    static {
        RIDE_TIERS.put("mini", new TierConfig("mini", "CabGo Mini", "Affordable everyday rides", 3.50, 1.35, 0.22, 5.00, 4, "#38bdf8", 1.0));
        RIDE_TIERS.put("ev", new TierConfig("ev", "Eco EV", "Quiet zero-emission sedans", 4.00, 1.45, 0.24, 5.50, 4, "#10b981", 1.02));
        RIDE_TIERS.put("sedan", new TierConfig("sedan", "Prime Sedan", "Spacious cabins & top drivers", 5.00, 1.85, 0.32, 7.50, 4, "#a855f7", 1.05));
        RIDE_TIERS.put("suv", new TierConfig("suv", "CabXL SUV", "6-seater comfort & luggage", 7.50, 2.45, 0.42, 11.00, 6, "#f59e0b", 0.96));

        PROMO_CODES.put("FIRST50", new PromoConfig("percent", 0.50, 8.00, 0.0, "50% off up to $8"));
        PROMO_CODES.put("SAVE20", new PromoConfig("percent", 0.20, 5.00, 0.0, "20% off up to $5"));
        PROMO_CODES.put("SWIFTVIP", new PromoConfig("flat", 4.00, 4.00, 10.0, "$4 off rides $10+"));
    }

    private final Random rng;
    public final CityGraph graph = new CityGraph();

    private LocalDateTime simTime;
    private double simSpeed;
    private String trafficLevel;
    private String surgeMode;
    private boolean simulateDriverDecline;
    private double walletBalance;
    private final Map<String, Cab> cabs = new LinkedHashMap<>();
    private Ride activeRide;
    private final List<Ride> rideHistory = new ArrayList<>();
    private final List<EventLog> eventLogs = new ArrayList<>();
    private final Map<String, Double> surgeZones = new LinkedHashMap<>();
    private int cabCounter;

    public SimulationEngine() {
        this(null);
    }

    public SimulationEngine(Long seed) {
        this.rng = (seed == null) ? new Random() : new Random(seed);
        reset();
    }

    public synchronized void reset() {
        simTime = LocalDateTime.of(2026, 11, 26, 9, 0, 0);
        simSpeed = 1.0;
        trafficLevel = "moderate";
        surgeMode = "auto";
        simulateDriverDecline = false;
        walletBalance = 85.00;
        cabs.clear();
        activeRide = null;
        rideHistory.clear();
        eventLogs.clear();
        surgeZones.clear();
        cabCounter = 0;

        spawnInitialFleet();
        recomputeSurgeZones();
        log("SYSTEM", "Java city simulation initialized with 16 active cabs across 4 tiers.");
    }

    private String simClockStr() {
        return simTime.format(CLOCK_FMT);
    }

    private void log(String category, String message) {
        eventLogs.add(0, new EventLog(simClockStr(), category, message));
        if (eventLogs.size() > 60) {
            eventLogs.subList(60, eventLogs.size()).clear();
        }
    }

    private void spawnInitialFleet() {
        List<String> tierPlan = new ArrayList<>();
        for (int i = 0; i < 5; i++) tierPlan.add("mini");
        for (int i = 0; i < 4; i++) tierPlan.add("ev");
        for (int i = 0; i < 4; i++) tierPlan.add("sedan");
        for (int i = 0; i < 3; i++) tierPlan.add("suv");

        List<String> nodeIds = new ArrayList<>(graph.nodes.keySet());
        Collections.shuffle(nodeIds, rng);

        for (int idx = 0; idx < tierPlan.size(); idx++) {
            String tier = tierPlan.get(idx);
            String startNode = nodeIds.get(idx % nodeIds.size());
            DriverInfo d = DRIVER_ROSTER.get(idx % DRIVER_ROSTER.size());
            List<String> models = VEHICLE_BY_TIER.get(tier);
            String vehicle = models.get(rng.nextInt(models.size()));
            String prefix = switch (tier) {
                case "mini" -> "GO";
                case "ev" -> "EV";
                case "sedan" -> "PR";
                default -> "XL";
            };
            String plate = prefix + "-" + (1000 + rng.nextInt(9000));
            spawnCab(tier, startNode, d.name(), d.rating(), vehicle, plate, false);
        }
    }

    public synchronized Cab spawnCab(String tier, String nodeId) {
        return spawnCab(tier, nodeId, null, null, null, null, true);
    }

    public synchronized Cab spawnCab(
            String tier,
            String nodeId,
            String driverName,
            Double driverRating,
            String vehicleModel,
            String licensePlate,
            boolean logEvent
    ) {
        cabCounter++;
        List<String> tierKeys = new ArrayList<>(RIDE_TIERS.keySet());
        String chosenTier = (tier != null && RIDE_TIERS.containsKey(tier))
                ? tier
                : tierKeys.get(rng.nextInt(tierKeys.size()));

        List<String> allNodeIds = new ArrayList<>(graph.nodes.keySet());
        String chosenNode = (nodeId != null && graph.nodes.containsKey(nodeId))
                ? nodeId
                : allNodeIds.get(rng.nextInt(allNodeIds.size()));

        CityNode node = graph.nodes.get(chosenNode);
        List<NeighborEdge> neighbors = graph.adj.getOrDefault(chosenNode, List.of());
        String targetId = neighbors.isEmpty()
                ? chosenNode
                : neighbors.get(rng.nextInt(neighbors.size())).targetId();

        if (driverName == null) {
            driverName = DRIVER_ROSTER.get(rng.nextInt(DRIVER_ROSTER.size())).name();
        }
        if (driverRating == null) {
            driverRating = Models.round2(4.72 + rng.nextDouble() * (4.98 - 4.72));
        }
        if (vehicleModel == null) {
            List<String> models = VEHICLE_BY_TIER.get(chosenTier);
            vehicleModel = models.get(rng.nextInt(models.size()));
        }
        if (licensePlate == null) {
            String prefix = switch (chosenTier) {
                case "mini" -> "GO";
                case "ev" -> "EV";
                case "sedan" -> "PR";
                default -> "XL";
            };
            licensePlate = prefix + "-" + (1000 + rng.nextInt(9000));
        }

        Cab cab = new Cab(
                String.format("CAB-%02d", cabCounter),
                chosenTier,
                driverName,
                driverRating,
                vehicleModel,
                licensePlate,
                chosenNode,
                targetId,
                node.x(),
                node.y()
        );
        cabs.put(cab.id, cab);
        if (logEvent) {
            log("FLEET", "Spawned " + cab.id + " (" + RIDE_TIERS.get(chosenTier).name() + ") at " + node.name() + ".");
        }
        return cab;
    }

    private void recomputeSurgeZones() {
        surgeZones.clear();
        if ("flat".equals(surgeMode)) return;

        long busyCabs = cabs.values().stream().filter(c -> !"AVAILABLE".equals(c.status)).count();
        int totalCabs = Math.max(1, cabs.size());
        double utilization = (double) busyCabs / totalCabs;

        for (CityNode lm : graph.getLandmarks()) {
            if ("rush_hour".equals(surgeMode)) {
                if (lm.baseDemand() >= 1.25) {
                    double mult = Models.round2(Math.min(2.2, 1.25 + (lm.baseDemand() - 1.0) * 0.95));
                    surgeZones.put(lm.id(), mult);
                }
            } else {
                long nearbyAvail = cabs.values().stream()
                        .filter(c -> "AVAILABLE".equals(c.status) && Math.hypot(c.x - lm.x(), c.y - lm.y()) < 180.0)
                        .count();
                double demandScore = lm.baseDemand() + utilization * 0.5 - nearbyAvail * 0.14;
                if (demandScore > 1.38) {
                    double mult = Models.round2(Math.min(1.85, 1.0 + (demandScore - 1.25) * 0.75));
                    if (mult >= 1.15) surgeZones.put(lm.id(), mult);
                }
            }
        }
    }

    public synchronized double getSurgeForNode(String nodeId) {
        if ("flat".equals(surgeMode)) return 1.0;
        if (surgeZones.containsKey(nodeId)) return surgeZones.get(nodeId);

        CityNode node = graph.nodes.get(nodeId);
        double best = 1.0;
        for (Map.Entry<String, Double> e : surgeZones.entrySet()) {
            CityNode zn = graph.nodes.get(e.getKey());
            double dist = Math.hypot(node.x() - zn.x(), node.y() - zn.y());
            if (dist < 160.0) {
                double decayed = 1.0 + (e.getValue() - 1.0) * (1.0 - dist / 160.0);
                best = Math.max(best, decayed);
            }
        }
        return Models.round2(best);
    }

    private Map<String, Double> computeFare(
            String tier,
            double distanceKm,
            double durationMin,
            double surgeMultiplier,
            String promoCode,
            double waitingFee
    ) {
        TierConfig cfg = RIDE_TIERS.get(tier);
        double base = cfg.baseFare();
        double distCharge = distanceKm * cfg.perKm();
        double timeCharge = durationMin * cfg.perMin();
        double rawSubtotal = Math.max(cfg.minFare(), base + distCharge + timeCharge);
        double surgeAmount = rawSubtotal * (surgeMultiplier - 1.0);
        double subtotalWithSurge = rawSubtotal + surgeAmount + waitingFee;

        double discount = 0.0;
        String codeClean = promoCode == null ? "" : promoCode.trim().toUpperCase();
        if (PROMO_CODES.containsKey(codeClean)) {
            PromoConfig promo = PROMO_CODES.get(codeClean);
            if (subtotalWithSurge >= promo.minFare()) {
                if ("percent".equals(promo.type())) {
                    discount = Math.min(promo.maxDiscount(), subtotalWithSurge * promo.value());
                } else {
                    discount = Math.min(promo.value(), subtotalWithSurge);
                }
            }
        }

        double total = Math.max(2.0, subtotalWithSurge - discount);
        Map<String, Double> out = new LinkedHashMap<>();
        out.put("base_fare", Models.round2(base));
        out.put("distance_fare", Models.round2(distCharge));
        out.put("time_fare", Models.round2(timeCharge));
        out.put("surge_amount", Models.round2(surgeAmount));
        out.put("waiting_fee", Models.round2(waitingFee));
        out.put("promo_discount", Models.round2(discount));
        out.put("total_fare", Models.round2(total));
        return out;
    }

    public synchronized Map<String, Object> estimateRide(String pickupNodeId, String dropoffNodeId, String promoCode) {
        if (!graph.nodes.containsKey(pickupNodeId) || !graph.nodes.containsKey(dropoffNodeId)) {
            throw new IllegalArgumentException("Invalid pickup or dropoff location.");
        }
        if (pickupNodeId.equals(dropoffNodeId)) {
            throw new IllegalArgumentException("Pickup and drop-off locations must be different.");
        }

        double trafficMult = TRAFFIC_MULTIPLIERS.get(trafficLevel);
        RouteResult route = graph.findShortestPath(pickupNodeId, dropoffNodeId, trafficMult);
        double surge = getSurgeForNode(pickupNodeId);

        String codeClean = promoCode == null ? "" : promoCode.trim().toUpperCase();
        boolean promoValid = !codeClean.isEmpty() && PROMO_CODES.containsKey(codeClean);

        List<Map<String, Object>> tiersOut = new ArrayList<>();
        for (TierConfig cfg : RIDE_TIERS.values()) {
            double tierDuration = Models.round1(Math.max(1.0, route.durationMin() / cfg.speedFactor()));
            Map<String, Double> breakdown = computeFare(cfg.id(), route.distanceKm(), tierDuration, surge, promoCode, 0.0);
            CabEta nearest = findNearestAvailableCab(cfg.id(), pickupNodeId, List.of());
            long availCount = cabs.values().stream()
                    .filter(c -> c.tier.equals(cfg.id()) && "AVAILABLE".equals(c.status))
                    .count();

            Map<String, Object> tMap = new LinkedHashMap<>();
            tMap.put("id", cfg.id());
            tMap.put("name", cfg.name());
            tMap.put("tagline", cfg.tagline());
            tMap.put("seats", cfg.seats());
            tMap.put("color", cfg.color());
            tMap.put("available_cabs", availCount);
            tMap.put("pickup_eta_min", nearest.cab() != null ? Models.round1(nearest.etaMin()) : null);
            tMap.put("trip_duration_min", tierDuration);
            tMap.put("fare_breakdown", breakdown);
            tMap.put("estimated_fare", breakdown.get("total_fare"));
            tiersOut.add(tMap);
        }

        List<Map<String, Object>> pathPts = new ArrayList<>();
        for (String nid : route.path()) {
            CityNode n = graph.nodes.get(nid);
            Map<String, Object> pt = new LinkedHashMap<>();
            pt.put("id", n.id());
            pt.put("x", n.x());
            pt.put("y", n.y());
            pathPts.add(pt);
        }

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("pickup", graph.nodes.get(pickupNodeId).name());
        res.put("dropoff", graph.nodes.get(dropoffNodeId).name());
        res.put("distance_km", route.distanceKm());
        res.put("duration_min", route.durationMin());
        res.put("traffic_level", trafficLevel);
        res.put("surge_multiplier", surge);
        res.put("promo_applied", promoValid ? codeClean : null);
        res.put("promo_label", promoValid ? PROMO_CODES.get(codeClean).label() : null);
        res.put("path", pathPts);
        res.put("tiers", tiersOut);
        return res;
    }

    private record CabEta(Cab cab, double etaMin) {}

    private CabEta findNearestAvailableCab(String tier, String pickupNodeId, List<String> excludeIds) {
        Set<String> excluded = new HashSet<>(excludeIds);
        double trafficMult = TRAFFIC_MULTIPLIERS.get(trafficLevel);
        Cab bestCab = null;
        double bestEta = Double.POSITIVE_INFINITY;

        for (Cab cab : cabs.values()) {
            if (!cab.tier.equals(tier) || !"AVAILABLE".equals(cab.status) || excluded.contains(cab.id)) continue;
            RouteResult rr = graph.findShortestPath(cab.targetNodeId, pickupNodeId, trafficMult);
            CityNode targetNode = graph.nodes.get(cab.targetNodeId);
            double remKm = Math.hypot(targetNode.x() - cab.x, targetNode.y() - cab.y) / 105.0;
            double eta = rr.durationMin() + (remKm / (40.0 / trafficMult)) * 60.0;
            if (eta < bestEta) {
                bestEta = eta;
                bestCab = cab;
            }
        }
        if (bestCab == null) return new CabEta(null, Double.POSITIVE_INFINITY);
        return new CabEta(bestCab, Models.round1(Math.max(1.0, bestEta)));
    }

    @SuppressWarnings("unchecked")
    public synchronized Ride bookRide(
            String pickupNodeId,
            String dropoffNodeId,
            String tier,
            String paymentMethod,
            String promoCode
    ) {
        if (activeRide != null && !"COMPLETED".equals(activeRide.status) && !"CANCELLED".equals(activeRide.status)) {
            throw new IllegalArgumentException("An active ride is already in progress.");
        }
        if (!RIDE_TIERS.containsKey(tier)) {
            throw new IllegalArgumentException("Unknown ride tier: " + tier);
        }
        if (pickupNodeId.equals(dropoffNodeId)) {
            throw new IllegalArgumentException("Pickup and drop-off locations must be different.");
        }

        Map<String, Object> est = estimateRide(pickupNodeId, dropoffNodeId, promoCode);
        List<Map<String, Object>> tiers = (List<Map<String, Object>>) est.get("tiers");
        Map<String, Object> tierEst = tiers.stream()
                .filter(t -> tier.equals(t.get("id")))
                .findFirst()
                .orElseThrow();

        double estFare = ((Number) tierEst.get("estimated_fare")).doubleValue();
        String method = (paymentMethod == null || paymentMethod.isBlank()) ? "wallet" : paymentMethod;
        if ("wallet".equals(method) && walletBalance < estFare) {
            throw new IllegalArgumentException(String.format(
                    "Insufficient wallet balance ($%.2f) for estimated fare ($%.2f).", walletBalance, estFare));
        }

        String otp = String.valueOf(1000 + rng.nextInt(9000));
        String rideId = "RIDE-" + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
        String codeClean = promoCode == null ? "" : promoCode.trim().toUpperCase();
        String appliedPromo = PROMO_CODES.containsKey(codeClean) ? codeClean : null;

        List<String> tripPath = new ArrayList<>();
        for (Map<String, Object> pt : (List<Map<String, Object>>) est.get("path")) {
            tripPath.add((String) pt.get("id"));
        }

        Ride ride = new Ride(
                rideId,
                pickupNodeId,
                dropoffNodeId,
                graph.nodes.get(pickupNodeId).name(),
                graph.nodes.get(dropoffNodeId).name(),
                tier,
                method,
                appliedPromo,
                "SEARCHING",
                otp,
                simClockStr(),
                ((Number) est.get("surge_multiplier")).doubleValue(),
                ((Number) est.get("distance_km")).doubleValue(),
                ((Number) tierEst.get("trip_duration_min")).doubleValue(),
                tripPath
        );
        ride.fareBreakdown = new LinkedHashMap<>((Map<String, Double>) tierEst.get("fare_breakdown"));
        ride.liveFare = estFare;
        activeRide = ride;

        log("DISPATCH", "Ride " + ride.id + " requested (" + RIDE_TIERS.get(tier).name()
                + ") from " + ride.pickupName + " to " + ride.dropoffName + ".");
        return ride;
    }

    private boolean assignCabToRide(Ride ride) {
        CabEta match = findNearestAvailableCab(ride.tier, ride.pickupNodeId, ride.rejectedCabIds);
        Cab cab = match.cab();
        if (cab == null) return false;

        if (simulateDriverDecline && ride.rejectedCabIds.isEmpty()) {
            ride.rejectedCabIds.add(cab.id);
            log("DISPATCH", cab.driverName + " (" + cab.id + ") declined " + ride.id + "; searching next driver...");
            simulateDriverDecline = false;
            return false;
        }

        double trafficMult = TRAFFIC_MULTIPLIERS.get(trafficLevel);
        RouteResult route = graph.findShortestPath(cab.targetNodeId, ride.pickupNodeId, trafficMult);

        List<double[]> waypoints = new ArrayList<>();
        waypoints.add(new double[]{cab.x, cab.y});
        for (String nid : route.path()) {
            CityNode n = graph.nodes.get(nid);
            double[] last = waypoints.get(waypoints.size() - 1);
            if (Math.hypot(n.x() - last[0], n.y() - last[1]) > 1.0) {
                waypoints.add(new double[]{n.x(), n.y()});
            }
        }
        if (waypoints.size() == 1) {
            CityNode n = graph.nodes.get(ride.pickupNodeId);
            waypoints.add(new double[]{n.x(), n.y()});
        }

        cab.status = "EN_ROUTE_TO_PICKUP";
        cab.assignedRideId = ride.id;
        cab.activeWaypoints = waypoints;
        cab.activeNodePath = new ArrayList<>(route.path());
        cab.routeProgress = 0.0;
        cab.totalRouteDistanceKm = Math.max(0.25, polylineLength(waypoints) / 105.0);

        ride.cabId = cab.id;
        ride.status = "DRIVER_ASSIGNED";
        ride.pickupPath = new ArrayList<>(route.path());
        ride.pickupDistanceKm = Models.round2(cab.totalRouteDistanceKm);
        ride.pickupEtaMin = Math.max(1.0, match.etaMin());
        ride.pickupProgress = 0.0;
        ride.chatMessages.add(new ChatMessage(
                "driver",
                "Hi! I'm " + cab.driverName + " in a " + cab.vehicleModel + " (" + cab.licensePlate + "). Heading to " + ride.pickupName + " now!",
                simClockStr()
        ));
        log("DRIVER", cab.id + " (" + cab.driverName + ") accepted " + ride.id + ". ETA " + Models.round1(ride.pickupEtaMin) + " min.");
        return true;
    }

    public synchronized Ride verifyOtp(String rideId, String otp) {
        if (activeRide == null || !activeRide.id.equals(rideId)) {
            throw new IllegalArgumentException("Ride not found.");
        }
        if (!"ARRIVED".equals(activeRide.status)) {
            throw new IllegalArgumentException("Driver has not arrived at pickup yet.");
        }
        if (otp == null || !otp.trim().equals(activeRide.otp)) {
            throw new IllegalArgumentException("Invalid OTP. Please check the 4-digit ride PIN.");
        }

        Cab cab = cabs.get(activeRide.cabId);
        List<double[]> waypoints = new ArrayList<>();
        for (String nid : activeRide.tripPath) {
            CityNode n = graph.nodes.get(nid);
            waypoints.add(new double[]{n.x(), n.y()});
        }

        cab.status = "IN_TRIP";
        cab.activeWaypoints = waypoints;
        cab.activeNodePath = new ArrayList<>(activeRide.tripPath);
        cab.routeProgress = 0.0;
        cab.totalRouteDistanceKm = Math.max(0.3, activeRide.estimatedDistanceKm);

        activeRide.status = "IN_TRIP";
        activeRide.tripProgress = 0.0;
        activeRide.tripElapsedSimSec = 0.0;
        activeRide.distanceCoveredKm = 0.0;
        activeRide.liveFare = RIDE_TIERS.get(activeRide.tier).baseFare();
        activeRide.chatMessages.add(new ChatMessage(
                "system",
                "OTP verified! Trip started toward " + activeRide.dropoffName + ".",
                simClockStr()
        ));
        log("TRIP", activeRide.id + " started! En route to " + activeRide.dropoffName + " (" + activeRide.estimatedDistanceKm + " km).");
        return activeRide;
    }

    public synchronized Ride cancelRide(String rideId, String reason) {
        if (activeRide == null || !activeRide.id.equals(rideId)) {
            throw new IllegalArgumentException("Ride not found.");
        }
        if ("IN_TRIP".equals(activeRide.status) || "COMPLETED".equals(activeRide.status) || "CANCELLED".equals(activeRide.status)) {
            throw new IllegalArgumentException("Cannot cancel a ride in " + activeRide.status + " state.");
        }

        double fee = activeRide.wouldIncurCancelFee() ? 2.50 : 0.0;
        activeRide.status = "CANCELLED";
        activeRide.cancellationFee = fee;
        activeRide.cancellationReason = (reason == null || reason.isBlank()) ? "Changed plans" : reason;

        if (fee > 0 && "wallet".equals(activeRide.paymentMethod)) {
            walletBalance = Models.round2(Math.max(0.0, walletBalance - fee));
        }
        if (activeRide.cabId != null && cabs.containsKey(activeRide.cabId)) {
            releaseCab(cabs.get(activeRide.cabId));
        }
        rideHistory.add(0, activeRide);
        log("DISPATCH", String.format("Ride %s cancelled (%s). Cancellation fee: $%.2f.", activeRide.id, activeRide.cancellationReason, fee));
        return activeRide;
    }

    public synchronized Ride sendChat(String rideId, String message) {
        if (activeRide == null || !activeRide.id.equals(rideId)) {
            throw new IllegalArgumentException("Ride not found.");
        }
        String clean = message == null ? "" : message.trim();
        if (clean.isEmpty()) throw new IllegalArgumentException("Message cannot be empty.");

        activeRide.chatMessages.add(new ChatMessage("rider", clean, simClockStr()));
        String lower = clean.toLowerCase();
        String reply;
        String sender;
        if ("SEARCHING".equals(activeRide.status)) {
            reply = "Matching your message once a driver accepts...";
            sender = "system";
        } else if (lower.contains("where") || lower.contains("eta") || lower.contains("long")) {
            reply = "ARRIVED".equals(activeRide.status)
                    ? "I'm right outside at the pin!"
                    : "About " + Math.max(1, Math.round(activeRide.pickupEtaMin)) + " min away, navigating traffic.";
            sender = "driver";
        } else if (lower.contains("wait") || lower.contains("coming") || lower.contains("down")) {
            reply = "No worries, I have my hazard lights on and I'll wait right here.";
            sender = "driver";
        } else if (lower.contains("gate") || lower.contains("entrance") || lower.contains("corner")) {
            reply = "Got it! Pulling up near the main entrance.";
            sender = "driver";
        } else {
            reply = "Sounds good, thank you!";
            sender = "driver";
        }
        activeRide.chatMessages.add(new ChatMessage(sender, reply, simClockStr()));
        return activeRide;
    }

    public synchronized Ride rateRide(String rideId, int stars, List<String> tags, double tip) {
        Ride target = null;
        if (activeRide != null && activeRide.id.equals(rideId)) {
            target = activeRide;
        } else {
            for (Ride r : rideHistory) {
                if (r.id.equals(rideId)) {
                    target = r;
                    break;
                }
            }
        }
        if (target == null) throw new IllegalArgumentException("Ride not found.");
        if (!"COMPLETED".equals(target.status)) throw new IllegalArgumentException("Only completed rides can be rated.");
        if (stars < 1 || stars > 5) throw new IllegalArgumentException("Rating must be between 1 and 5 stars.");

        double cleanTip = Math.max(0.0, Models.round2(tip));
        if (cleanTip > 0 && target.ratingGiven == null && "wallet".equals(target.paymentMethod)) {
            if (walletBalance < cleanTip) throw new IllegalArgumentException("Insufficient wallet balance for tip.");
            walletBalance = Models.round2(walletBalance - cleanTip);
        }

        target.ratingGiven = stars;
        target.feedbackTags = tags == null ? new ArrayList<>() : new ArrayList<>(tags);
        target.tip = cleanTip;

        if (target.cabId != null && cabs.containsKey(target.cabId)) {
            Cab cab = cabs.get(target.cabId);
            cab.driverRating = Models.round2(Math.min(5.0, Math.max(3.5, cab.driverRating * 0.92 + stars * 0.08)));
        }
        log("TRIP", "Rider rated " + target.id + " " + stars + "★" + (cleanTip > 0 ? String.format(" with $%.2f tip.", cleanTip) : "."));
        return target;
    }

    public synchronized void clearActiveRide() {
        if (activeRide != null && ("COMPLETED".equals(activeRide.status) || "CANCELLED".equals(activeRide.status))) {
            activeRide = null;
        }
    }

    public synchronized double topupWallet(double amount) {
        if (amount <= 0 || amount > 1000) {
            throw new IllegalArgumentException("Top-up amount must be between $1 and $1000.");
        }
        walletBalance = Models.round2(walletBalance + amount);
        log("WALLET", String.format("Added $%.2f to wallet. New balance: $%.2f.", amount, walletBalance));
        return walletBalance;
    }

    public synchronized void updateControls(Double speed, String traffic, String surge, Boolean decline) {
        if (speed != null) {
            double s = speed;
            if (s != 0 && s != 1 && s != 2 && s != 5 && s != 10) {
                throw new IllegalArgumentException("Speed must be one of 0, 1, 2, 5, 10.");
            }
            this.simSpeed = s;
        }
        if (traffic != null) {
            if (!TRAFFIC_MULTIPLIERS.containsKey(traffic)) {
                throw new IllegalArgumentException("Traffic level must be low, moderate, or heavy.");
            }
            this.trafficLevel = traffic;
            log("TRAFFIC", "Traffic condition set to " + traffic.toUpperCase() + ".");
        }
        if (surge != null) {
            if (!List.of("auto", "rush_hour", "flat").contains(surge)) {
                throw new IllegalArgumentException("Surge mode must be auto, rush_hour, or flat.");
            }
            this.surgeMode = surge;
            recomputeSurgeZones();
            log("SURGE", "Surge pricing mode set to " + surge.toUpperCase() + ".");
        }
        if (decline != null) {
            this.simulateDriverDecline = decline;
        }
    }

    private void releaseCab(Cab cab) {
        CityNode nearest = graph.findNearestNode(cab.x, cab.y);
        List<NeighborEdge> neighbors = graph.adj.getOrDefault(nearest.id(), List.of());
        cab.status = "AVAILABLE";
        cab.assignedRideId = null;
        cab.activeWaypoints.clear();
        cab.activeNodePath.clear();
        cab.routeProgress = 0.0;
        cab.currentNodeId = nearest.id();
        cab.targetNodeId = neighbors.isEmpty() ? nearest.id() : neighbors.get(rng.nextInt(neighbors.size())).targetId();
        cab.x = nearest.x();
        cab.y = nearest.y();
        cab.edgeProgress = 0.0;
    }

    private void completeRide(Ride ride, Cab cab) {
        CityNode dropNode = graph.nodes.get(ride.dropoffNodeId);
        cab.x = dropNode.x();
        cab.y = dropNode.y();
        cab.totalTripsCompleted++;

        double waitMinutes = Math.max(0.0, (ride.waitSimSec - 120.0) / 60.0);
        double waitingFee = Models.round2(waitMinutes * 0.25);
        double actualMin = Math.max(1.0, Models.round1(ride.tripElapsedSimSec / 60.0));
        Map<String, Double> finalBreakdown = computeFare(
                ride.tier, ride.estimatedDistanceKm, actualMin, ride.surgeMultiplier, ride.promoCode, waitingFee);

        ride.fareBreakdown = finalBreakdown;
        ride.liveFare = finalBreakdown.get("total_fare");
        ride.status = "COMPLETED";
        ride.tripProgress = 1.0;
        ride.distanceCoveredKm = ride.estimatedDistanceKm;

        if ("wallet".equals(ride.paymentMethod)) {
            walletBalance = Models.round2(Math.max(0.0, walletBalance - ride.liveFare));
        }

        ride.chatMessages.add(new ChatMessage(
                "driver",
                "We've arrived at " + ride.dropoffName + "! Thank you for riding with SwiftCab.",
                simClockStr()
        ));
        rideHistory.add(0, ride);
        releaseCab(cab);
        recomputeSurgeZones();
        log("TRIP", String.format("Ride %s completed! Final fare: $%.2f via %s.", ride.id, ride.liveFare, ride.paymentMethod.toUpperCase()));
    }

    public synchronized void tick(double realDtSec) {
        if (simSpeed <= 0 || realDtSec <= 0) return;

        double simDt = realDtSec * simSpeed * SIM_SECONDS_PER_REAL_SECOND;
        long nanos = Math.round(simDt * 1_000_000_000.0);
        simTime = simTime.plusNanos(nanos);
        double trafficMult = TRAFFIC_MULTIPLIERS.get(trafficLevel);

        for (Cab cab : cabs.values()) {
            if ("AVAILABLE".equals(cab.status)) {
                stepCruisingCab(cab, simDt, trafficMult);
            }
        }

        Ride ride = activeRide;
        if (ride == null) return;

        switch (ride.status) {
            case "SEARCHING" -> {
                ride.searchElapsedSimSec += simDt;
                if (ride.searchElapsedSimSec >= 14.0) {
                    boolean matched = assignCabToRide(ride);
                    if (!matched && ride.searchElapsedSimSec >= 90.0) {
                        ride.status = "CANCELLED";
                        ride.cancellationReason = "No drivers available nearby";
                        rideHistory.add(0, ride);
                        log("DISPATCH", "Ride " + ride.id + " timed out — no " + ride.tier + " cabs available.");
                    }
                }
            }
            case "DRIVER_ASSIGNED" -> {
                Cab cab = cabs.get(ride.cabId);
                if (cab == null) return;
                double speedKmh = (42.0 * RIDE_TIERS.get(cab.tier).speedFactor()) / trafficMult;
                double distStepKm = (speedKmh * simDt) / 3600.0;
                double progStep = distStepKm / Math.max(0.2, cab.totalRouteDistanceKm);
                cab.routeProgress = Math.min(1.0, cab.routeProgress + progStep);
                double[] pose = interpolatePoints(cab.activeWaypoints, cab.routeProgress);
                cab.x = pose[0];
                cab.y = pose[1];
                cab.heading = pose[2];

                ride.pickupProgress = cab.routeProgress;
                double remKm = cab.totalRouteDistanceKm * (1.0 - cab.routeProgress);
                ride.pickupDistanceKm = Models.round2(remKm);
                ride.pickupEtaMin = Models.round1(Math.max(0.2, (remKm / Math.max(8.0, speedKmh)) * 60.0));

                if (cab.routeProgress >= 1.0) {
                    cab.status = "ARRIVED";
                    ride.status = "ARRIVED";
                    ride.pickupEtaMin = 0.0;
                    ride.pickupDistanceKm = 0.0;
                    ride.waitSimSec = 0.0;
                    ride.chatMessages.add(new ChatMessage(
                            "driver",
                            "I've arrived at " + ride.pickupName + "! Share OTP " + ride.otp + " to start the ride.",
                            simClockStr()
                    ));
                    log("DRIVER", cab.id + " arrived at " + ride.pickupName + " for " + ride.id + ".");
                }
            }
            case "ARRIVED" -> ride.waitSimSec += simDt;
            case "IN_TRIP" -> {
                Cab cab = cabs.get(ride.cabId);
                if (cab == null) return;
                double speedKmh = (44.0 * RIDE_TIERS.get(cab.tier).speedFactor()) / trafficMult;
                double distStepKm = (speedKmh * simDt) / 3600.0;
                double progStep = distStepKm / Math.max(0.2, cab.totalRouteDistanceKm);
                cab.routeProgress = Math.min(1.0, cab.routeProgress + progStep);
                double[] pose = interpolatePoints(cab.activeWaypoints, cab.routeProgress);
                cab.x = pose[0];
                cab.y = pose[1];
                cab.heading = pose[2];

                ride.tripProgress = cab.routeProgress;
                ride.tripElapsedSimSec += simDt;
                ride.distanceCoveredKm = Models.round2(ride.estimatedDistanceKm * cab.routeProgress);

                Map<String, Double> liveCalc = computeFare(
                        ride.tier,
                        ride.distanceCoveredKm,
                        Math.max(0.5, ride.tripElapsedSimSec / 60.0),
                        ride.surgeMultiplier,
                        ride.promoCode,
                        0.0
                );
                ride.liveFare = liveCalc.get("total_fare");

                if (cab.routeProgress >= 1.0) {
                    completeRide(ride, cab);
                }
            }
            default -> {}
        }
    }

    private void stepCruisingCab(Cab cab, double simDt, double trafficMult) {
        RoadEdge edge = graph.getEdge(cab.currentNodeId, cab.targetNodeId);
        if (edge == null) {
            List<NeighborEdge> neighbors = graph.adj.getOrDefault(cab.currentNodeId, List.of());
            if (neighbors.isEmpty()) return;
            cab.targetNodeId = neighbors.get(rng.nextInt(neighbors.size())).targetId();
            edge = graph.getEdge(cab.currentNodeId, cab.targetNodeId);
            if (edge == null) return;
        }

        double cruiseSpeedKmh = Math.max(12.0, (edge.speedLimitKmh() * 0.72) / trafficMult);
        double distStepKm = (cruiseSpeedKmh * simDt) / 3600.0;
        cab.edgeProgress += distStepKm / Math.max(0.2, edge.distanceKm());

        while (cab.edgeProgress >= 1.0) {
            double overflow = (cab.edgeProgress - 1.0) * edge.distanceKm();
            String prevNode = cab.currentNodeId;
            cab.currentNodeId = cab.targetNodeId;
            List<NeighborEdge> neighbors = graph.adj.getOrDefault(cab.currentNodeId, List.of());
            List<String> forward = new ArrayList<>();
            for (NeighborEdge ne : neighbors) {
                if (!ne.targetId().equals(prevNode)) forward.add(ne.targetId());
            }
            if (forward.isEmpty()) {
                for (NeighborEdge ne : neighbors) forward.add(ne.targetId());
            }
            cab.targetNodeId = forward.get(rng.nextInt(forward.size()));
            edge = graph.getEdge(cab.currentNodeId, cab.targetNodeId);
            if (edge == null) {
                cab.edgeProgress = 0.0;
                break;
            }
            cab.edgeProgress = overflow / Math.max(0.2, edge.distanceKm());
        }

        CityNode u = graph.nodes.get(cab.currentNodeId);
        CityNode v = graph.nodes.get(cab.targetNodeId);
        cab.x = u.x() + (v.x() - u.x()) * cab.edgeProgress;
        cab.y = u.y() + (v.y() - u.y()) * cab.edgeProgress;
        cab.heading = Math.atan2(v.y() - u.y(), v.x() - u.x());
    }

    private static double polylineLength(List<double[]> points) {
        double total = 0.0;
        for (int i = 0; i < points.size() - 1; i++) {
            total += Math.hypot(points.get(i + 1)[0] - points.get(i)[0], points.get(i + 1)[1] - points.get(i)[1]);
        }
        return total;
    }

    private static double[] interpolatePoints(List<double[]> points, double fraction) {
        if (points.isEmpty()) return new double[]{0, 0, 0};
        if (points.size() == 1) return new double[]{points.get(0)[0], points.get(0)[1], 0};
        double f = Math.max(0.0, Math.min(1.0, fraction));
        double totalLen = polylineLength(points);
        if (totalLen <= 1e-6) return new double[]{points.get(0)[0], points.get(0)[1], 0};

        double target = f * totalLen;
        double acc = 0.0;
        for (int i = 0; i < points.size() - 1; i++) {
            double x1 = points.get(i)[0], y1 = points.get(i)[1];
            double x2 = points.get(i + 1)[0], y2 = points.get(i + 1)[1];
            double seg = Math.hypot(x2 - x1, y2 - y1);
            if (acc + seg >= target || seg == 0) {
                double t = seg > 0 ? (target - acc) / seg : 0.0;
                return new double[]{x1 + (x2 - x1) * t, y1 + (y2 - y1) * t, Math.atan2(y2 - y1, x2 - x1)};
            }
            acc += seg;
        }
        double[] prev = points.get(points.size() - 2);
        double[] last = points.get(points.size() - 1);
        return new double[]{last[0], last[1], Math.atan2(last[1] - prev[1], last[0] - prev[0])};
    }

    public synchronized Map<String, Object> getInitPayload() {
        Map<String, Object> tiersMap = new LinkedHashMap<>();
        for (Map.Entry<String, TierConfig> e : RIDE_TIERS.entrySet()) {
            tiersMap.put(e.getKey(), e.getValue().toMap());
        }
        Map<String, Object> promosMap = new LinkedHashMap<>();
        for (Map.Entry<String, PromoConfig> e : PROMO_CODES.entrySet()) {
            promosMap.put(e.getKey(), e.getValue().toMap());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("city", graph.toMap());
        out.put("tiers", tiersMap);
        out.put("promo_codes", promosMap);
        out.put("state", getState());
        return out;
    }

    public synchronized Map<String, Object> getState() {
        long availCabs = cabs.values().stream().filter(c -> "AVAILABLE".equals(c.status)).count();
        Map<String, Object> fleetSummary = new LinkedHashMap<>();
        fleetSummary.put("total", cabs.size());
        fleetSummary.put("available", availCabs);
        fleetSummary.put("busy", cabs.size() - availCabs);

        List<Map<String, Object>> surgeList = new ArrayList<>();
        for (Map.Entry<String, Double> e : surgeZones.entrySet()) {
            CityNode n = graph.nodes.get(e.getKey());
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("node_id", n.id());
            sm.put("name", n.name());
            sm.put("x", n.x());
            sm.put("y", n.y());
            sm.put("multiplier", e.getValue());
            surgeList.add(sm);
        }

        List<Map<String, Object>> cabList = new ArrayList<>();
        for (Cab c : cabs.values()) cabList.add(c.toMap());

        List<Map<String, Object>> histList = new ArrayList<>();
        int limit = Math.min(25, rideHistory.size());
        for (int i = 0; i < limit; i++) {
            histList.add(rideHistory.get(i).toMap(cabs, graph, RIDE_TIERS));
        }

        List<Map<String, Object>> logList = new ArrayList<>();
        int logLimit = Math.min(30, eventLogs.size());
        for (int i = 0; i < logLimit; i++) {
            logList.add(eventLogs.get(i).toMap());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sim_time", simClockStr());
        out.put("sim_speed", simSpeed);
        out.put("traffic_level", trafficLevel);
        out.put("surge_mode", surgeMode);
        out.put("simulate_driver_decline", simulateDriverDecline);
        out.put("wallet_balance", Models.round2(walletBalance));
        out.put("fleet_summary", fleetSummary);
        out.put("surge_zones", surgeList);
        out.put("cabs", cabList);
        out.put("active_ride", activeRide != null ? activeRide.toMap(cabs, graph, RIDE_TIERS) : null);
        out.put("ride_history", histList);
        out.put("event_logs", logList);
        return out;
    }

    public synchronized Ride getActiveRide() {
        return activeRide;
    }

    public synchronized double getWalletBalance() {
        return walletBalance;
    }

    public synchronized Map<String, Cab> getCabs() {
        return cabs;
    }

    public synchronized List<Ride> getRideHistory() {
        return rideHistory;
    }
}
