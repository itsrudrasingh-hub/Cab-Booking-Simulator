package com.swiftcab;

import java.util.*;

/**
 * Domain models and configuration tables for the SwiftCab Booking Simulator.
 */
public final class Models {

    private Models() {}

    public record TierConfig(
            String id,
            String name,
            String tagline,
            double baseFare,
            double perKm,
            double perMin,
            double minFare,
            int seats,
            String color,
            double speedFactor
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("name", name);
            m.put("tagline", tagline);
            m.put("base_fare", baseFare);
            m.put("per_km", perKm);
            m.put("per_min", perMin);
            m.put("min_fare", minFare);
            m.put("seats", seats);
            m.put("color", color);
            m.put("speed_factor", speedFactor);
            return m;
        }
    }

    public record PromoConfig(
            String type,
            double value,
            double maxDiscount,
            double minFare,
            String label
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", type);
            m.put("value", value);
            m.put("max_discount", maxDiscount);
            m.put("min_fare", minFare);
            m.put("label", label);
            return m;
        }
    }

    public record DriverInfo(String name, double rating, String vehicleModel, String licensePlate) {}

    public record ChatMessage(String sender, String text, String timestamp) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sender", sender);
            m.put("text", text);
            m.put("timestamp", timestamp);
            return m;
        }
    }

    public record EventLog(String time, String category, String message) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("time", time);
            m.put("category", category);
            m.put("message", message);
            return m;
        }
    }

    public static final class Cab {
        public final String id;
        public final String tier;
        public final String driverName;
        public double driverRating;
        public final String vehicleModel;
        public final String licensePlate;
        public String currentNodeId;
        public String targetNodeId;
        public double x;
        public double y;
        public double heading;
        public double edgeProgress;
        public String status = "AVAILABLE"; // AVAILABLE, EN_ROUTE_TO_PICKUP, ARRIVED, IN_TRIP
        public String assignedRideId;
        public List<double[]> activeWaypoints = new ArrayList<>();
        public List<String> activeNodePath = new ArrayList<>();
        public double routeProgress;
        public double totalRouteDistanceKm;
        public int totalTripsCompleted;

        public Cab(
                String id, String tier, String driverName, double driverRating,
                String vehicleModel, String licensePlate, String currentNodeId,
                String targetNodeId, double x, double y
        ) {
            this.id = id;
            this.tier = tier;
            this.driverName = driverName;
            this.driverRating = driverRating;
            this.vehicleModel = vehicleModel;
            this.licensePlate = licensePlate;
            this.currentNodeId = currentNodeId;
            this.targetNodeId = targetNodeId;
            this.x = x;
            this.y = y;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("tier", tier);
            m.put("driver_name", driverName);
            m.put("driver_rating", round2(driverRating));
            m.put("vehicle_model", vehicleModel);
            m.put("license_plate", licensePlate);
            m.put("current_node_id", currentNodeId);
            m.put("target_node_id", targetNodeId);
            m.put("x", round2(x));
            m.put("y", round2(y));
            m.put("heading", Math.round(heading * 10000.0) / 10000.0);
            m.put("status", status);
            m.put("assigned_ride_id", assignedRideId);
            m.put("route_progress", Math.round(routeProgress * 10000.0) / 10000.0);
            m.put("total_trips_completed", totalTripsCompleted);
            return m;
        }
    }

    public static final class Ride {
        public final String id;
        public final String pickupNodeId;
        public final String dropoffNodeId;
        public final String pickupName;
        public final String dropoffName;
        public final String tier;
        public final String paymentMethod;
        public final String promoCode;
        public String status; // SEARCHING, DRIVER_ASSIGNED, ARRIVED, IN_TRIP, COMPLETED, CANCELLED
        public final String otp;
        public final String createdAt;
        public final double surgeMultiplier;
        public final double estimatedDistanceKm;
        public final double estimatedDurationMin;
        public final List<String> tripPath;
        public List<String> pickupPath = new ArrayList<>();
        public String cabId;
        public double searchElapsedSimSec;
        public final List<String> rejectedCabIds = new ArrayList<>();
        public double pickupDistanceKm;
        public double pickupEtaMin;
        public double pickupProgress;
        public double waitSimSec;
        public double tripProgress;
        public double tripElapsedSimSec;
        public double distanceCoveredKm;
        public double liveFare;
        public Map<String, Double> fareBreakdown = new LinkedHashMap<>();
        public double cancellationFee;
        public String cancellationReason;
        public double tip;
        public Integer ratingGiven;
        public List<String> feedbackTags = new ArrayList<>();
        public final List<ChatMessage> chatMessages = new ArrayList<>();

        public Ride(
                String id, String pickupNodeId, String dropoffNodeId,
                String pickupName, String dropoffName, String tier,
                String paymentMethod, String promoCode, String status,
                String otp, String createdAt, double surgeMultiplier,
                double estimatedDistanceKm, double estimatedDurationMin,
                List<String> tripPath
        ) {
            this.id = id;
            this.pickupNodeId = pickupNodeId;
            this.dropoffNodeId = dropoffNodeId;
            this.pickupName = pickupName;
            this.dropoffName = dropoffName;
            this.tier = tier;
            this.paymentMethod = paymentMethod;
            this.promoCode = promoCode;
            this.status = status;
            this.otp = otp;
            this.createdAt = createdAt;
            this.surgeMultiplier = surgeMultiplier;
            this.estimatedDistanceKm = estimatedDistanceKm;
            this.estimatedDurationMin = estimatedDurationMin;
            this.tripPath = new ArrayList<>(tripPath);
        }

        public boolean wouldIncurCancelFee() {
            if ("ARRIVED".equals(status)) return true;
            return "DRIVER_ASSIGNED".equals(status) && pickupProgress >= 0.35;
        }

        public Map<String, Object> toMap(Map<String, Cab> cabsById, CityGraph graph, Map<String, TierConfig> tiers) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("pickup_node_id", pickupNodeId);
            m.put("dropoff_node_id", dropoffNodeId);
            m.put("pickup_name", pickupName);
            m.put("dropoff_name", dropoffName);
            m.put("tier", tier);
            m.put("tier_name", tiers.get(tier).name());
            m.put("payment_method", paymentMethod);
            m.put("promo_code", promoCode);
            m.put("status", status);
            m.put("otp", otp);
            m.put("created_at", createdAt);
            m.put("surge_multiplier", round2(surgeMultiplier));
            m.put("estimated_distance_km", round2(estimatedDistanceKm));
            m.put("estimated_duration_min", round1(estimatedDurationMin));
            m.put("trip_path", toPointList(tripPath, graph));
            m.put("pickup_path", toPointList(pickupPath, graph));
            m.put("cab", (cabId != null && cabsById.containsKey(cabId)) ? cabsById.get(cabId).toMap() : null);
            m.put("pickup_distance_km", round2(pickupDistanceKm));
            m.put("pickup_eta_min", round1(pickupEtaMin));
            m.put("pickup_progress", Math.round(pickupProgress * 1000.0) / 1000.0);
            m.put("wait_sim_sec", round1(waitSimSec));
            m.put("trip_progress", Math.round(tripProgress * 1000.0) / 1000.0);
            m.put("trip_elapsed_min", round1(tripElapsedSimSec / 60.0));
            m.put("distance_covered_km", round2(distanceCoveredKm));
            m.put("live_fare", round2(liveFare));

            Map<String, Object> bd = new LinkedHashMap<>();
            for (Map.Entry<String, Double> e : fareBreakdown.entrySet()) {
                bd.put(e.getKey(), round2(e.getValue()));
            }
            m.put("fare_breakdown", bd);
            m.put("cancellation_fee", round2(cancellationFee));
            m.put("cancellation_reason", cancellationReason);
            m.put("tip", round2(tip));
            m.put("rating_given", ratingGiven);
            m.put("feedback_tags", feedbackTags);

            List<Map<String, Object>> chats = new ArrayList<>();
            for (ChatMessage cm : chatMessages) chats.add(cm.toMap());
            m.put("chat_messages", chats);
            m.put("cancel_fee_applies", wouldIncurCancelFee());
            return m;
        }

        private static List<Map<String, Object>> toPointList(List<String> nodeIds, CityGraph graph) {
            List<Map<String, Object>> pts = new ArrayList<>();
            for (String nid : nodeIds) {
                CityGraph.CityNode n = graph.nodes.get(nid);
                if (n != null) {
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("id", n.id());
                    p.put("x", n.x());
                    p.put("y", n.y());
                    pts.add(p);
                }
            }
            return pts;
        }
    }

    public static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    public static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
