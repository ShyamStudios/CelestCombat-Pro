package com.shyamstudio.celestCombatPro.api;

public final class CelestCombatAPI {
    private static CombatAPI combatAPI;

    private CelestCombatAPI() {
        // Private constructor to prevent instantiation
    }

    public static void initialize(CombatAPI api) {
        combatAPI = api;
    }

    public static void shutdown() {
        combatAPI = null;
    }

    public static CombatAPI getCombatAPI() {
        return combatAPI;
    }
}
