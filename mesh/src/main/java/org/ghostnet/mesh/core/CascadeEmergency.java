package org.ghostnet.mesh.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Standard structured emergency record for the CASCADE network.
 * Compatible with cascade.emergency.v1 schema produced by the local AI pipeline.
 * Bounded to fit within MessageCodec.MAX_BYTES (2048 bytes).
 */
public final class CascadeEmergency {
    public static final String VERSION = "cascade.emergency.v1";

    public final String version;
    public final String intent;
    public final double confidence;
    public final String urgency;
    public final List<String> needs;
    public final List<String> hazards;
    public final Integer peopleCount;
    public final String location;
    public final String rawText;

    public CascadeEmergency(String version, String intent, double confidence, String urgency,
                            List<String> needs, List<String> hazards,
                            Integer peopleCount, String location, String rawText) {
        this.version = version == null ? VERSION : version;
        this.intent = intent == null ? "UNKNOWN" : intent;
        this.confidence = Math.max(0.0, Math.min(1.0, confidence));
        this.urgency = urgency == null ? "moderate" : urgency;
        this.needs = needs == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(needs));
        this.hazards = hazards == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(hazards));
        this.peopleCount = peopleCount;
        this.location = location;
        this.rawText = rawText == null ? "" : rawText;
    }

    public static CascadeEmergency create(String intent, double confidence, String urgency,
                                         List<String> needs, List<String> hazards,
                                         Integer peopleCount, String location, String rawText) {
        return new CascadeEmergency(VERSION, intent, confidence, urgency, needs, hazards, peopleCount, location, rawText);
    }

    /**
     * Serializes this emergency record to a compact UTF-8 JSON byte array.
     */
    public byte[] toJsonBytes() {
        return toJson().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Serializes this emergency record to a compact JSON string.
     */
    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"version\":").append(quote(version)).append(",");
        sb.append("\"intent\":").append(quote(intent)).append(",");
        sb.append(String.format(java.util.Locale.US, "\"confidence\":%.2f,", confidence));
        sb.append("\"urgency\":").append(quote(urgency)).append(",");
        
        // needs
        sb.append("\"needs\":[");
        for (int i = 0; i < needs.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(quote(needs.get(i)));
        }
        sb.append("],");

        // hazards
        sb.append("\"hazards\":[");
        for (int i = 0; i < hazards.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(quote(hazards.get(i)));
        }
        sb.append("],");

        // people_count
        if (peopleCount != null) {
            sb.append("\"people_count\":").append(peopleCount).append(",");
        } else {
            sb.append("\"people_count\":null,");
        }

        // location
        if (location != null) {
            sb.append("\"location\":").append(quote(location)).append(",");
        } else {
            sb.append("\"location\":null,");
        }

        // raw_text
        sb.append("\"raw_text\":").append(quote(rawText));
        sb.append("}");
        return sb.toString();
    }

    /**
     * Attempts to parse a UTF-8 JSON byte array into a CascadeEmergency.
     * Returns null if the byte array is not a cascade.emergency.v1 JSON object.
     */
    public static CascadeEmergency fromJsonBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;
        try {
            return fromJson(new String(bytes, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Pure-Java JSON parser for cascade.emergency.v1 envelopes.
     * Operates without requiring org.json or Gson dependencies in the core mesh library.
     */
    public static CascadeEmergency fromJson(String json) {
        if (json == null || !json.contains("cascade.emergency.v1")) return null;

        String version = extractString(json, "version");
        String intent = extractString(json, "intent");
        String urgency = extractString(json, "urgency");
        Double confidence = extractDouble(json, "confidence");
        Integer count = extractInteger(json, "people_count");
        String location = extractString(json, "location");
        String rawText = extractString(json, "raw_text");
        List<String> needs = extractStringList(json, "needs");
        List<String> hazards = extractStringList(json, "hazards");

        return new CascadeEmergency(
                version != null ? version : VERSION,
                intent != null ? intent : "EMERGENCY_REPORT",
                confidence != null ? confidence : 0.85,
                urgency != null ? urgency : "moderate",
                needs,
                hazards,
                count,
                location,
                rawText != null ? rawText : ""
        );
    }

    private static String quote(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') sb.append("\\\"");
            else if (c == '\\') sb.append("\\\\");
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else if (c == '\t') sb.append("\\t");
            else if (c < 32) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        sb.append("\"");
        return sb.toString();
    }

    private static String extractString(String json, String key) {
        Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)\"");
        Matcher m = p.matcher(json);
        if (m.find()) {
            return unescape(m.group(1));
        }
        return null;
    }

    private static Double extractDouble(String json, String key) {
        Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)");
        Matcher m = p.matcher(json);
        if (m.find()) {
            try { return Double.parseDouble(m.group(1)); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private static Integer extractInteger(String json, String key) {
        Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*([0-9]+)");
        Matcher m = p.matcher(json);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private static List<String> extractStringList(String json, String key) {
        Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\\[([^\\]]*)\\]");
        Matcher m = p.matcher(json);
        List<String> list = new ArrayList<>();
        if (m.find()) {
            String items = m.group(1);
            Pattern itemPattern = Pattern.compile("\"([^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)\"");
            Matcher itemMatcher = itemPattern.matcher(items);
            while (itemMatcher.find()) {
                list.add(unescape(itemMatcher.group(1)));
            }
        }
        return list;
    }

    private static String unescape(String s) {
        return s.replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t");
    }
}
