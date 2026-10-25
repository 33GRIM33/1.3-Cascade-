package org.ghostnet.mesh.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Defines network participant roles and capabilities in the CASCADE emergency network.
 * Differentiates between normal users (victims needing assistance) and first responders.
 */
public enum UserRole {
    NORMAL_USER("Normal User / Citizen"),
    FIRST_RESPONDER("First Responder / Rescue Team");

    public final String displayName;

    UserRole(String displayName) {
        this.displayName = displayName;
    }

    /**
     * Standard capability tags for first responders.
     */
    public static final class Capabilities {
        public static final String MEDICAL = "medical";
        public static final String BOAT = "boat";
        public static final String SEARCH_RESCUE = "rescue";
        public static final String EVACUATION = "evacuation";
        public static final String POWER_EQUIPMENT = "power";
        public static final String SUPPLIES = "food_water";

        public static final Set<String> ALL = Collections.unmodifiableSet(
                new HashSet<>(Arrays.asList(MEDICAL, BOAT, SEARCH_RESCUE, EVACUATION, POWER_EQUIPMENT, SUPPLIES))
        );
    }
}
