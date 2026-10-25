package org.ghostnet.mesh.core;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline rule-based emergency extractor for Android and pure Java environments.
 * Extracts intent, needs, hazards, casualties, battery constraint, and urgency from freeform emergency text.
 * Mirrors local_sos.py logic and serves as the offline baseline / fallback.
 */
public final class CascadeExtractor {

    private static final Pattern RESCUE_PATTERN = Pattern.compile(
            "\\b(trapped|stuck|stranded|pinned|collapse|buried|drowning|roof|attic|boat|raft|helicopter|airlift|evacuate|rescue)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern MEDICAL_PATTERN = Pattern.compile(
            "\\b(insulin|dialysis|bleeding|broken|fracture|unconscious|cpr|oxygen|asthma|heart|stroke|burns|wound|medic|ambulance|doctor|nurse|first[ -]?aid|medical)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern FOOD_WATER_PATTERN = Pattern.compile(
            "\\b(water|drinking water|food|ration|formula|baby food|starving|dehydrated|hunger)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern SHELTER_PATTERN = Pattern.compile(
            "\\b(shelter|blanket|tent|tarpaulin|tarp|homeless|displaced|dry clothes)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern POWER_PATTERN = Pattern.compile(
            "\\b(power|generator|battery|electricity|blackout|lights out|charge)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern FLOOD_PATTERN = Pattern.compile(
            "\\b(flood|rising water|water level|water rising|ground floor flooded|submerged|current|river overflow|storm surge)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern FIRE_PATTERN = Pattern.compile(
            "\\b(fire|flames|smoke|burning|explosion|gas leak|propane)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern STRUCTURAL_PATTERN = Pattern.compile(
            "\\b(collapse|rubble|debris|cracked wall|roof caved|sinkhole|landslide)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern WIRE_PATTERN = Pattern.compile(
            "\\b(downed power line|live wire|electrocution|sparking wire)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern PEOPLE_COUNT_NUM = Pattern.compile(
            "\\b(\\d+)\\s*(?:people|persons|victims|casualties|individuals|family members|trapped|of us|adults|kids|children)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern FAMILY_OF_N = Pattern.compile(
            "\\bfamily of\\s*(\\d+)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern BATTERY_PCT_PATTERN = Pattern.compile(
            "\\b(?:(\\d{1,3})%\\s*(?:battery)?|battery\\s*(?:is|at)?\\s*(\\d{1,3})%)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern OFFER_PATTERN = Pattern.compile(
            "\\b(i can help|we have|available|volunteering|can provide|willing to assist|spare|bringing|has a boat|can do dialysis)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern CHAT_PATTERN = Pattern.compile(
            "\\b(hello|hi|hey|good morning|how are you|thanks|thank you|ok|okay)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern QUERY_PATTERN = Pattern.compile(
            "\\b(is anyone|any news|what is the status|where can i|who is|update on)\\b",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Extracts structured emergency record from raw input text.
     */
    public static CascadeEmergency extract(String text) {
        return extract(text, "NORMAL_USER", null, null, null, false);
    }

    /**
     * Extracts structured emergency record with sender role, battery, coordinates, and audio metadata.
     */
    public static CascadeEmergency extract(String text, String senderRole, Integer batteryPct,
                                          Double lat, Double lon, boolean hasAudio) {
        if (text == null) text = "";
        String clean = text.trim();

        // 1. Tag needs
        List<String> needs = new ArrayList<>();
        if (RESCUE_PATTERN.matcher(clean).find()) needs.add("rescue");
        if (MEDICAL_PATTERN.matcher(clean).find()) needs.add("medical");
        if (FOOD_WATER_PATTERN.matcher(clean).find()) needs.add("food_water");
        if (SHELTER_PATTERN.matcher(clean).find()) needs.add("shelter");
        if (POWER_PATTERN.matcher(clean).find()) needs.add("power");

        // Specific items
        if (clean.toLowerCase(Locale.US).contains("insulin") && !needs.contains("insulin")) needs.add("insulin");
        if (clean.toLowerCase(Locale.US).contains("boat") && !needs.contains("boat")) needs.add("boat");

        // 2. Tag hazards
        List<String> hazards = new ArrayList<>();
        if (FLOOD_PATTERN.matcher(clean).find()) hazards.add("flood");
        if (FIRE_PATTERN.matcher(clean).find()) hazards.add("fire");
        if (STRUCTURAL_PATTERN.matcher(clean).find()) hazards.add("structural");
        if (WIRE_PATTERN.matcher(clean).find()) hazards.add("electrical");

        // 3. Extract people count
        Integer count = extractPeopleCount(clean);

        // 4. Extract battery % if mentioned in text and not provided externally
        Integer extractedBattery = batteryPct != null ? batteryPct : extractBatteryPct(clean);

        // 5. Classify intent
        String intent = classifyIntent(clean, needs, hazards);

        // 6. Urgency calculation
        String urgency = calculateUrgency(intent, needs, hazards, count, extractedBattery);

        // 7. Confidence estimate
        double confidence = (needs.isEmpty() && hazards.isEmpty()) ? 0.70 : 0.94;

        return CascadeEmergency.createWithRole(
                intent, confidence, urgency, needs, hazards, count, null, clean,
                senderRole != null ? senderRole : "NORMAL_USER",
                extractedBattery, lat, lon, hasAudio
        );
    }

    private static Integer extractBatteryPct(String text) {
        Matcher m = BATTERY_PCT_PATTERN.matcher(text);
        if (m.find()) {
            String g1 = m.group(1);
            String g2 = m.group(2);
            String num = g1 != null ? g1 : g2;
            try {
                int val = Integer.parseInt(num);
                if (val >= 0 && val <= 100) return val;
            } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private static Integer extractPeopleCount(String text) {
        Matcher m1 = FAMILY_OF_N.matcher(text);
        if (m1.find()) {
            try { return Integer.parseInt(m1.group(1)); } catch (NumberFormatException ignored) {}
        }
        Matcher m2 = PEOPLE_COUNT_NUM.matcher(text);
        if (m2.find()) {
            try { return Integer.parseInt(m2.group(1)); } catch (NumberFormatException ignored) {}
        }
        // Common word counts
        if (Pattern.compile("\\b(two of us|couple)\\b", Pattern.CASE_INSENSITIVE).matcher(text).find()) return 2;
        if (Pattern.compile("\\b(three of us)\\b", Pattern.CASE_INSENSITIVE).matcher(text).find()) return 3;
        if (Pattern.compile("\\b(four of us)\\b", Pattern.CASE_INSENSITIVE).matcher(text).find()) return 4;
        if (Pattern.compile("\\b(five of us)\\b", Pattern.CASE_INSENSITIVE).matcher(text).find()) return 5;
        if (Pattern.compile("\\b(alone|by myself|just me)\\b", Pattern.CASE_INSENSITIVE).matcher(text).find()) return 1;

        return null;
    }

    private static String classifyIntent(String text, List<String> needs, List<String> hazards) {
        if (OFFER_PATTERN.matcher(text).find()) return "RESOURCE_OFFER";
        if (QUERY_PATTERN.matcher(text).find() && needs.isEmpty()) return "STATUS_QUERY";
        if (CHAT_PATTERN.matcher(text).find() && needs.isEmpty() && hazards.isEmpty()) return "CHIT_CHAT";
        if (needs.contains("rescue")) return "RESCUE_REQUEST";
        if (needs.contains("medical")) return "CASUALTY_REPORT";
        if (!hazards.isEmpty() && needs.isEmpty()) return "HAZARD_ALERT";
        if (!needs.isEmpty()) return "RESCUE_REQUEST";
        return "STATUS_QUERY";
    }

    private static String calculateUrgency(String intent, List<String> needs, List<String> hazards,
                                          Integer count, Integer batteryPct) {
        if (intent.equals("CHIT_CHAT")) return "low";
        if (intent.equals("RESOURCE_OFFER")) return "low";

        // Battery critical: <= 15% escalates urgency
        if (batteryPct != null && batteryPct <= 15 && (!needs.isEmpty() || !hazards.isEmpty())) {
            return "critical";
        }

        if (needs.contains("rescue") || needs.contains("medical") || hazards.contains("fire") || hazards.contains("flood")) {
            if (count != null && count >= 3) return "critical";
            return "high";
        }
        if (!needs.isEmpty() || !hazards.isEmpty()) return "moderate";
        return "low";
    }
}
