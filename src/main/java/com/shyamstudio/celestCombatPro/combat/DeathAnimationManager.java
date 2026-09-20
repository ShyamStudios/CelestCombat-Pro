package com.shyamstudio.celestCombatPro.combat;

import com.shyamstudio.celestCombatPro.CelestCombatPro;
import com.shyamstudio.celestCombatPro.Scheduler;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class DeathAnimationManager {
    private final CelestCombatPro plugin;
    private final Random random = new Random();

    // Cached configuration (written on reload, read on region threads)
    private volatile boolean enabled;
    private volatile boolean onlyPlayerKill;
    private volatile boolean lightningEnabled;
    private volatile boolean fireParticlesEnabled;

    public DeathAnimationManager(CelestCombatPro plugin) {
        this.plugin = plugin;
        reloadConfig();
    }

    public void reloadConfig() {
        this.enabled = plugin.getConfig().getBoolean("death_animation.enabled", true);
        this.onlyPlayerKill = plugin.getConfig().getBoolean("death_animation.only_player_kill", true);
        this.lightningEnabled = plugin.getConfig().getBoolean("death_animation.animation.lightning", true);
        this.fireParticlesEnabled = plugin.getConfig().getBoolean("death_animation.animation.fire_particles", true);
    }

    public void performDeathAnimation(Player victim, Player killer) {
        // Check if death animations are enabled
        if (!enabled) {
            return;
        }

        // Check if the death was by another player
        if (killer == null && onlyPlayerKill) {
            return;
        }

        if (victim == null) {
            return;
        }

        Location deathLocation = victim.getLocation();
        World world = deathLocation.getWorld();

        if (world == null) return;

        // Build the available animation list from cached config
        List<String> availableAnimations = new ArrayList<>(2);
        if (lightningEnabled) {
            availableAnimations.add("lightning");
        }
        if (fireParticlesEnabled) {
            availableAnimations.add("fire_particles");
        }

        // If no animations are available, return
        if (availableAnimations.isEmpty()) {
            return;
        }

        // Randomly select an animation if multiple are true
        String selectedAnimation = availableAnimations.get(random.nextInt(availableAnimations.size()));

        // World effects must run on the region that owns the death location
        Scheduler.runRegion(deathLocation, () -> {
            switch (selectedAnimation) {
                case "lightning":
                    performLightningAnimation(world, deathLocation);
                    break;
                case "fire_particles":
                    performParticleAnimation(world, deathLocation);
                    break;
                default:
                    break;
            }
        });
    }

    private void performLightningAnimation(World world, Location location) {
        world.strikeLightningEffect(location);

        // Play a dramatic thunder sound
        world.playSound(
                location,
                Sound.ENTITY_LIGHTNING_BOLT_THUNDER,
                1.0F,
                1.0F
        );
        // plugin.debug("Lightning animation performed at " + location);
    }

    private void performParticleAnimation(World world, Location location) {
        // Create a circular burst of particles
        for (int i = 0; i < 50; i++) {
            double angle = 2 * Math.PI * i / 50;
            double x = Math.cos(angle) * 2;
            double z = Math.sin(angle) * 2;

            world.spawnParticle(
                    Particle.FLAME,
                    location.clone().add(x, 1, z),
                    1,
                    0.1, 0.1, 0.1,
                    0.05
            );
        }

        // Play a dramatic sound
        world.playSound(
                location,
                Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST,
                1.0F,
                1.0F
        );

        // plugin.debug("Fire particles animation performed at " + location);
    }
}
