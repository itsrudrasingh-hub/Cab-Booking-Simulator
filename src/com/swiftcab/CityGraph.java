package com.swiftcab;

import java.util.*;

/**
 * Simulated city road network graph, landmarks, bridges, and Dijkstra shortest-path routing.
 */
public final class CityGraph {

    public static final double LOGICAL_WIDTH = 1000.0;
    public static final double LOGICAL_HEIGHT = 700.0;

    public record CityNode(
            String id,
            String name,
            double x,
            double y,
            boolean isLandmark,
            String category,
            double baseDemand
    ) {}

    public record RoadEdge(
            String u,
            String v,
            double distanceKm,
            double speedLimitKmh,
            String roadName,
            boolean isBridge,
            boolean isExpressway
    ) {}

    public record NeighborEdge(String targetId, RoadEdge edge) {}

    public record RouteResult(List<String> path, double distanceKm, double durationMin) {}

    public final Map<String, CityNode> nodes = new LinkedHashMap<>();
    public final List<RoadEdge> edges = new ArrayList<>();
    public final Map<String, List<NeighborEdge>> adj = new LinkedHashMap<>();

    public static final List<double[]> RIVER_POINTS = List.of(
            new double[]{-20.0, 425.0},
            new double[]{160.0, 405.0},
            new double[]{330.0, 388.0},
            new double[]{510.0, 408.0},
            new double[]{700.0, 420.0},
            new double[]{860.0, 392.0},
            new double[]{1020.0, 375.0}
    );

    public static final List<Map<String, Object>> PARKS = List.of(
            park("Emerald Central Park", 405.0, 230.0, 90.0, 75.0),
            park("Riverside Botanical", 120.0, 465.0, 95.0, 70.0),
            park("Eastside Green", 685.0, 115.0, 85.0, 65.0)
    );

    private static Map<String, Object> park(String name, double x, double y, double w, double h) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("x", x);
        m.put("y", y);
        m.put("w", w);
        m.put("h", h);
        return m;
    }

    public CityGraph() {
        buildCity();
    }

    private void addNode(CityNode node) {
        nodes.put(node.id(), node);
        adj.computeIfAbsent(node.id(), k -> new ArrayList<>());
    }

    private void addEdge(String u, String v, String roadName, double speedLimitKmh, boolean isBridge, boolean isExpressway) {
        CityNode nu = nodes.get(u);
        CityNode nv = nodes.get(v);
        double pixelDist = Math.hypot(nv.x() - nu.x(), nv.y() - nu.y());
        double distanceKm = Math.round(Math.max(0.35, pixelDist / 105.0) * 100.0) / 100.0;
        RoadEdge edgeUv = new RoadEdge(u, v, distanceKm, speedLimitKmh, roadName, isBridge, isExpressway);
        RoadEdge edgeVu = new RoadEdge(v, u, distanceKm, speedLimitKmh, roadName, isBridge, isExpressway);
        edges.add(edgeUv);
        adj.get(u).add(new NeighborEdge(v, edgeUv));
        adj.get(v).add(new NeighborEdge(u, edgeVu));
    }

    private void buildCity() {
        List<CityNode> initialNodes = List.of(
                // Row 0
                new CityNode("N0_0", "Northgate Suburbs", 90, 95, true, "residential", 1.1),
                new CityNode("N0_1", "Oak & 1st Ave", 240, 88, false, "intersection", 1.0),
                new CityNode("N0_2", "University Campus", 380, 92, true, "transit", 1.35),
                new CityNode("N0_3", "Civic Plaza", 520, 85, false, "intersection", 1.0),
                new CityNode("N0_4", "Arts Quarter", 660, 92, true, "leisure", 1.2),
                new CityNode("N0_5", "Eastwood Junction", 805, 95, false, "intersection", 1.0),
                new CityNode("N0_6", "Skyline Airport", 925, 110, true, "transit", 1.85),
                // Row 1
                new CityNode("N1_0", "West End Market", 95, 215, true, "commercial", 1.15),
                new CityNode("N1_1", "Tech Park Hub", 240, 208, true, "commercial", 1.65),
                new CityNode("N1_2", "Midtown Crossing", 378, 212, false, "intersection", 1.0),
                new CityNode("N1_3", "Financial District", 525, 205, true, "commercial", 1.6),
                new CityNode("N1_4", "City Hospital", 665, 218, true, "medical", 1.25),
                new CityNode("N1_5", "Expo Center", 805, 210, true, "leisure", 1.35),
                new CityNode("N1_6", "Airport Expressway", 920, 235, false, "intersection", 1.0),
                // Row 2
                new CityNode("N2_0", "West Quay", 95, 345, false, "intersection", 1.0),
                new CityNode("N2_1", "West Bridge North", 242, 338, false, "intersection", 1.0),
                new CityNode("N2_2", "Downtown Mall", 380, 332, true, "commercial", 1.7),
                new CityNode("N2_3", "Central Station", 518, 338, true, "transit", 1.8),
                new CityNode("N2_4", "Museum Walk", 658, 345, true, "leisure", 1.2),
                new CityNode("N2_5", "East Bridge North", 802, 335, false, "intersection", 1.0),
                new CityNode("N2_6", "Industrial Port", 918, 338, false, "intersection", 1.0),
                // Row 3
                new CityNode("N3_0", "Riverside Park", 95, 475, true, "leisure", 1.1),
                new CityNode("N3_1", "West Bridge South", 242, 470, false, "intersection", 1.0),
                new CityNode("N3_2", "Southbank Commons", 385, 475, false, "intersection", 1.0),
                new CityNode("N3_3", "Central Bridge South", 518, 468, false, "intersection", 1.0),
                new CityNode("N3_4", "Harborfront Pier", 662, 478, true, "leisure", 1.4),
                new CityNode("N3_5", "East Bridge South", 802, 468, false, "intersection", 1.0),
                new CityNode("N3_6", "Bayview Heights", 915, 472, false, "intersection", 1.0),
                // Row 4
                new CityNode("N4_0", "Sunset Villas", 100, 600, true, "residential", 1.0),
                new CityNode("N4_1", "Cedar & 9th St", 245, 595, false, "intersection", 1.0),
                new CityNode("N4_2", "Grand Stadium", 388, 605, true, "leisure", 1.55),
                new CityNode("N4_3", "South Terminal", 522, 598, true, "transit", 1.3),
                new CityNode("N4_4", "Marina Bay", 665, 605, false, "intersection", 1.0),
                new CityNode("N4_5", "Old Town Bazaar", 805, 595, true, "commercial", 1.3),
                new CityNode("N4_6", "Southpoint Tech", 915, 600, true, "commercial", 1.25)
        );
        for (CityNode node : initialNodes) addNode(node);

        String[] rowNames = {"North Ring Rd", "Commerce Blvd", "Riverfront Ave", "Southbank Pkwy", "Harbor Loop Rd"};
        for (int r = 0; r < 5; r++) {
            for (int c = 0; c < 6; c++) {
                String u = "N" + r + "_" + c;
                String v = "N" + r + "_" + (c + 1);
                boolean isExp = (r == 0 && c >= 3) || (r == 4 && c <= 2);
                double speed = isExp ? 58.0 : 40.0;
                addEdge(u, v, rowNames[r], speed, false, isExp);
            }
        }

        String[] colNames = {
                "Westside Ave", "1st Meridian", "Parkway Central",
                "Grand Central Ave", "Hospital Rd", "Eastern Blvd", "Airport Express"
        };
        for (int c = 0; c < 7; c++) {
            for (int r = 0; r < 4; r++) {
                String u = "N" + r + "_" + c;
                String v = "N" + (r + 1) + "_" + c;
                if (r == 2) {
                    if (c == 1 || c == 3 || c == 5) {
                        String bridgeName = switch (c) {
                            case 1 -> "Westgate Bridge";
                            case 3 -> "Central Metro Bridge";
                            default -> "Eastside Bridge";
                        };
                        addEdge(u, v, bridgeName, 46.0, true, false);
                    }
                } else {
                    boolean isExp = (c == 6);
                    double speed = isExp ? 65.0 : 42.0;
                    addEdge(u, v, colNames[c], speed, false, isExp);
                }
            }
        }

        addEdge("N0_0", "N1_1", "North-West Diagonal", 48.0, false, false);
        addEdge("N1_1", "N2_2", "Midtown Diagonal", 45.0, false, false);
        addEdge("N2_3", "N1_4", "Metro Connector", 45.0, false, false);
        addEdge("N1_5", "N0_6", "Skyline Spur", 68.0, false, true);
        addEdge("N3_1", "N4_2", "Stadium Link", 46.0, false, false);
        addEdge("N3_4", "N4_5", "Bazaar Promenade", 42.0, false, false);
    }

    public List<CityNode> getLandmarks() {
        List<CityNode> out = new ArrayList<>();
        for (CityNode n : nodes.values()) {
            if (n.isLandmark()) out.add(n);
        }
        return out;
    }

    public CityNode findNearestNode(double x, double y) {
        CityNode best = null;
        double bestDist = Double.MAX_VALUE;
        for (CityNode n : nodes.values()) {
            double d = Math.hypot(n.x() - x, n.y() - y);
            if (d < bestDist) {
                bestDist = d;
                best = n;
            }
        }
        return best;
    }

    public RoadEdge getEdge(String u, String v) {
        for (NeighborEdge ne : adj.getOrDefault(u, List.of())) {
            if (ne.targetId().equals(v)) return ne.edge();
        }
        return null;
    }

    public RouteResult findShortestPath(String startId, String endId, double trafficMultiplier) {
        if (!nodes.containsKey(startId) || !nodes.containsKey(endId)) {
            throw new IllegalArgumentException("Unknown node id: " + startId + " or " + endId);
        }
        if (startId.equals(endId)) {
            return new RouteResult(List.of(startId), 0.0, 0.0);
        }

        record State(double time, double dist, String nodeId) {}
        PriorityQueue<State> pq = new PriorityQueue<>(Comparator.comparingDouble(State::time));
        Map<String, Double> bestTime = new HashMap<>();
        Map<String, Double> bestDist = new HashMap<>();
        Map<String, String> parent = new HashMap<>();

        bestTime.put(startId, 0.0);
        bestDist.put(startId, 0.0);
        parent.put(startId, null);
        pq.add(new State(0.0, 0.0, startId));

        while (!pq.isEmpty()) {
            State cur = pq.poll();
            if (cur.nodeId().equals(endId)) break;
            if (cur.time() > bestTime.getOrDefault(cur.nodeId(), Double.POSITIVE_INFINITY)) continue;

            for (NeighborEdge ne : adj.get(cur.nodeId())) {
                RoadEdge edge = ne.edge();
                double bridgePenalty = (edge.isBridge() && trafficMultiplier > 1.2) ? 1.15 : 1.0;
                double effectiveSpeed = Math.max(10.0, edge.speedLimitKmh() / (trafficMultiplier * bridgePenalty));
                double edgeMinutes = (edge.distanceKm() / effectiveSpeed) * 60.0;
                double newTime = cur.time() + edgeMinutes;
                double newDist = cur.dist() + edge.distanceKm();

                if (newTime < bestTime.getOrDefault(ne.targetId(), Double.POSITIVE_INFINITY)) {
                    bestTime.put(ne.targetId(), newTime);
                    bestDist.put(ne.targetId(), newDist);
                    parent.put(ne.targetId(), cur.nodeId());
                    pq.add(new State(newTime, newDist, ne.targetId()));
                }
            }
        }

        if (!parent.containsKey(endId)) {
            throw new IllegalStateException("No route found between " + startId + " and " + endId);
        }

        List<String> path = new ArrayList<>();
        String cur = endId;
        while (cur != null) {
            path.add(cur);
            cur = parent.get(cur);
        }
        Collections.reverse(path);

        double distKm = Math.round(bestDist.get(endId) * 100.0) / 100.0;
        double mins = Math.round(Math.max(1.0, bestTime.get(endId)) * 10.0) / 10.0;
        return new RouteResult(path, distKm, mins);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("width", LOGICAL_WIDTH);
        m.put("height", LOGICAL_HEIGHT);

        List<Map<String, Object>> riverList = new ArrayList<>();
        for (double[] pt : RIVER_POINTS) {
            Map<String, Object> rp = new LinkedHashMap<>();
            rp.put("x", pt[0]);
            rp.put("y", pt[1]);
            riverList.add(rp);
        }
        m.put("river", riverList);
        m.put("parks", PARKS);

        List<Map<String, Object>> nodeList = new ArrayList<>();
        for (CityNode n : nodes.values()) {
            Map<String, Object> nm = new LinkedHashMap<>();
            nm.put("id", n.id());
            nm.put("name", n.name());
            nm.put("x", n.x());
            nm.put("y", n.y());
            nm.put("is_landmark", n.isLandmark());
            nm.put("category", n.category());
            nodeList.add(nm);
        }
        m.put("nodes", nodeList);

        List<Map<String, Object>> edgeList = new ArrayList<>();
        for (RoadEdge e : edges) {
            Map<String, Object> em = new LinkedHashMap<>();
            em.put("u", e.u());
            em.put("v", e.v());
            em.put("distance_km", e.distanceKm());
            em.put("speed_limit_kmh", e.speedLimitKmh());
            em.put("road_name", e.roadName());
            em.put("is_bridge", e.isBridge());
            em.put("is_expressway", e.isExpressway());
            edgeList.add(em);
        }
        m.put("edges", edgeList);
        return m;
    }
}
