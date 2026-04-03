package com.shyamstudio.celestCombatPro.hooks.practice;

import com.shyamstudio.celestCombatPro.CelestCombatPro;
import com.shyamstudio.celestCombatPro.combat.CombatManager;
import ga.strikepractice.events.DuelEndEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Hook for StrikePractice plugin integration.
 * Automatically removes combat timers when practice matches end.
 */
public class StrikePracticeHook implements Listener {

    private final CelestCombatPro plugin;
    private final CombatManager combatManager;

    public StrikePracticeHook(CelestCombatPro plugin, CombatManager combatManager) {
        this.plugin = plugin;
        this.combatManager = combatManager;
        plugin.debug("StrikePractice hook initialized");
    }

    /**
     * Handles duel end events - removes combat timer from both players
     * This covers all duel types (1v1, 2v2, etc.)
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDuelEnd(DuelEndEvent event) {
        Player winner = event.getWinner();
        Player loser = event.getLoser();

        // Remove combat timer from winner
        if (winner != null && combatManager.isInCombat(winner)) {
            combatManager.removeFromCombat(winner);
            plugin.debug("[StrikePractice] Removed combat timer from duel winner: " + winner.getName());
        }

        // Remove combat timer from loser
        if (loser != null && combatManager.isInCombat(loser)) {
            combatManager.removeFromCombat(loser);
            plugin.debug("[StrikePractice] Removed combat timer from duel loser: " + loser.getName());
        }
    }

    /**
     * Cleanup method called on plugin disable
     */
    public void cleanup() {
        plugin.debug("StrikePractice hook cleaned up");
    }
}
