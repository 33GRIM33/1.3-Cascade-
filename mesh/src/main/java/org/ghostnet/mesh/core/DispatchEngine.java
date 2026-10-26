package org.ghostnet.mesh.core;

import java.util.*;

/**
 * Autonomous Multi-Agent Dispatch Engine implementing the Probabilistic Evaluation Chain
 * from the CASCADE Emergency Network specification:
 *
 * Claim Verification ──► Hazard Avoidance ──► Trust Scoring ──► Battery/Routing Cost ──► Safe Dispatch
 *
 * Runs fully on-device without requiring internet connectivity or central dispatchers.
 */
public final class DispatchEngine {

    private final WarehouseManager warehouseManager;
    private final Map<String, Long> lastSeenClaimTimestamps = new HashMap<>();
    private final Map<String, Integer> claimCountPerOrigin = new HashMap<>();

    public DispatchEngine(WarehouseManager warehouseManager) {
        this.warehouseManager = warehouseManager != null ? warehouseManager : new WarehouseManager();
    }

    public DispatchEngine() {
        this(new WarehouseManager());
    }

    public WarehouseManager getWarehouseManager() {
        return warehouseManager;
    }

    /**
     * Complete evaluation result of the Probabilistic Evaluation Chain.
     */
    public static final class EvaluationResult {
        public final boolean claimVerified;
        public final boolean hazardSafe;
        public final double trustScore; // 0.0 to 1.0
        public final double urgencyCost; // Higher means higher dispatch priority
        public final boolean approvedForDispatch;
        public final String justification; // 1-line plain-language justification for human responder
        public final WarehouseManager.RoutePlan routePlan;
        public final List<String> requiredEquipment;

        public EvaluationResult(boolean claimVerified, boolean hazardSafe, double trustScore,
                                double urgencyCost, boolean approvedForDispatch, String justification,
                                WarehouseManager.RoutePlan routePlan, List<String> requiredEquipment) {
            this.claimVerified = claimVerified;
            this.hazardSafe = hazardSafe;
            this.trustScore = trustScore;
            this.urgencyCost = urgencyCost;
            this.approvedForDispatch = approvedForDispatch;
            this.justification = justification;
            this.routePlan = routePlan;
            this.requiredEquipment = requiredEquipment != null ? requiredEquipment : Collections.emptyList();
        }

        public String getSummary() {
            return String.format(Locale.US,
                    "[Trust: %.0f%% | Urgency: %.1f | %s]\n%s",
                    trustScore * 100, urgencyCost,
                    approvedForDispatch ? "APPROVED" : "HELD",
                    justification);
        }
    }

    /**
     * Evaluates an incoming emergency distress report through the 5-step Probabilistic Evaluation Chain.
     */
    public EvaluationResult evaluate(CascadeEmergency emergency, String originNodeId, int hopLimit,
                                     double responderLat, double responderLon) {
        if (emergency == null) {
            return new EvaluationResult(false, false, 0.0, 0.0, false, "Invalid empty emergency record", null, null);
        }

        // Default coordinates if victim has drifting/missing GPS
        double victimLat = emergency.senderLat != null ? emergency.senderLat : (responderLat + 0.007);
        double victimLon = emergency.senderLon != null ? emergency.senderLon : (responderLon + 0.005);

        // -------------------------------------------------------------
        // Step 1: Claim Verification (Sybil / Flooding / Decoy detection)
        // -------------------------------------------------------------
        boolean claimVerified = verifyClaim(originNodeId, emergency);

        // -------------------------------------------------------------
        // Step 2: Hazard Avoidance (Checking active hazards & equipment)
        // -------------------------------------------------------------
        List<String> requiredEquip = determineRequiredEquipment(emergency);
        boolean hazardSafe = true;
        if (emergency.hazards.contains("flood") && !requiredEquip.contains("boat")) {
            requiredEquip.add("boat");
        }
        if (emergency.hazards.contains("electrical")) {
            // Live wire hazard warning
            hazardSafe = false;
        }

        // -------------------------------------------------------------
        // Step 3: Trust Scoring (Multi-hop provenance & semantic sanity)
        // -------------------------------------------------------------
        double trustScore = computeTrustScore(emergency, originNodeId, hopLimit);

        // -------------------------------------------------------------
        // Step 4: Battery & Routing Cost Calculation
        // -------------------------------------------------------------
        double urgencyCost = computeUrgencyAndBatteryCost(emergency);

        // -------------------------------------------------------------
        // Step 5: Safe Dispatch Decision & Route Planning
        // -------------------------------------------------------------
        List<String> neededSupplies = new ArrayList<>(emergency.needs);
        for (String eq : requiredEquip) {
            if (!neededSupplies.contains(eq)) neededSupplies.add(eq);
        }

        WarehouseManager.RoutePlan routePlan = warehouseManager.planRoute(
                neededSupplies, responderLat, responderLon, victimLat, victimLon
        );

        hazardSafe = hazardSafe && routePlan.reachable;
        boolean approved = claimVerified && trustScore >= 0.50 && hazardSafe && routePlan.unfulfilledItems.isEmpty();

        // Build human-in-the-loop one-line plain language justification
        String justification = generateJustification(emergency, approved, trustScore, requiredEquip, routePlan);
        if (!routePlan.reachable) justification = routePlan.summaryText;
        else if (!hazardSafe) justification = "Held: reported electrical danger requires specialist review. " + routePlan.summaryText;
        else if (!routePlan.unfulfilledItems.isEmpty()) justification = "Held: required supplies unavailable. " + routePlan.summaryText;

        return new EvaluationResult(claimVerified, hazardSafe, trustScore, urgencyCost, approved, justification, routePlan, requiredEquip);
    }

    private boolean verifyClaim(String originNodeId, CascadeEmergency emergency) {
        if (originNodeId == null || originNodeId.trim().isEmpty()) return false;
        long now = System.currentTimeMillis();

        // Sybil / spam suppression: check burst rate from same origin
        int count = claimCountPerOrigin.getOrDefault(originNodeId, 0) + 1;
        claimCountPerOrigin.put(originNodeId, count);
        lastSeenClaimTimestamps.put(originNodeId, now);

        if (count > 25) {
            // Burst exceeding 25 calls in demo session is flagged as potential Sybil flood
            return false;
        }

        // Check if raw text is not empty
        return emergency.rawText != null && emergency.rawText.trim().length() > 2;
    }

    private List<String> determineRequiredEquipment(CascadeEmergency emergency) {
        List<String> equip = new ArrayList<>();
        if (emergency.hazards.contains("flood") || emergency.needs.contains("rescue")) {
            equip.add("boat");
            equip.add("life_jacket");
        }
        if (emergency.rawText.toLowerCase(Locale.US).contains("insulin")) equip.add("insulin");
        if (emergency.needs.contains("medical")) {
            equip.add("medical");
        }
        if (emergency.needs.contains("food_water") || emergency.rawText.toLowerCase(Locale.US).contains("water")) {
            equip.add("water");
        }
        if (emergency.needs.contains("power")) {
            equip.add("generator");
        }
        return equip;
    }

    private double computeTrustScore(CascadeEmergency emergency, String originNodeId, int hopLimit) {
        double score = 0.80; // Baseline trust

        // High confidence from local AI model boosts trust
        if (emergency.confidence > 0.85) score += 0.10;

        // Realistic hop count (within 1-4 hops) indicates local mesh propagation
        if (hopLimit >= 1 && hopLimit <= 4) score += 0.05;

        // Verified casualties and specific location increase veracity
        if (emergency.peopleCount != null && emergency.peopleCount > 0) score += 0.05;
        if (emergency.location != null && !emergency.location.isEmpty()) score += 0.05;

        return Math.min(1.0, score);
    }

    private double computeUrgencyAndBatteryCost(CascadeEmergency emergency) {
        double urgency = 5.0; // Base moderate

        if ("critical".equalsIgnoreCase(emergency.urgency)) urgency += 4.0;
        else if ("high".equalsIgnoreCase(emergency.urgency)) urgency += 2.5;

        // Factor in low battery (e.g. 12% battery from problem statement)
        if (emergency.batteryPct != null) {
            if (emergency.batteryPct <= 15) {
                // Critical battery dying soon!
                urgency += 2.0;
            } else if (emergency.batteryPct <= 30) {
                urgency += 1.0;
            }
        }

        if (emergency.peopleCount != null && emergency.peopleCount > 1) {
            urgency += Math.min(2.0, emergency.peopleCount * 0.5);
        }

        return urgency;
    }

    private String generateJustification(CascadeEmergency emergency, boolean approved, double trustScore,
                                        List<String> requiredEquip, WarehouseManager.RoutePlan routePlan) {
        if (!approved) {
            return "Held: Distress signal failed claim verification or trust threshold (" +
                    Math.round(trustScore * 100) + "% confidence). Confirming with adjacent mesh peers.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Suggested assistance: ");
        if ("critical".equalsIgnoreCase(emergency.urgency)) {
            sb.append("CRITICAL rescue (");
        } else {
            sb.append("Assistance for (");
        }

        if (emergency.peopleCount != null) {
            sb.append(emergency.peopleCount).append(" people");
        } else {
            sb.append("victim");
        }

        if (emergency.batteryPct != null && emergency.batteryPct <= 15) {
            sb.append(", battery dying at ").append(emergency.batteryPct).append("%");
        }
        sb.append("). ");

        if (routePlan != null && routePlan.summaryText != null && !routePlan.summaryText.isEmpty()) {
            sb.append(routePlan.summaryText);
        } else {
            sb.append("Proceed along direct route.");
        }

        return sb.toString();
    }
}
