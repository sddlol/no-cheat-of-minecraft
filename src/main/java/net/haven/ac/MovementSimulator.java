package net.haven.ac;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Conservative movement allowance model for Paper 1.16.5.
 * Designed for PVP: prefer fewer false positives over aggressive flags.
 */
public final class MovementSimulator {

    private MovementSimulator() {}

    public static boolean shouldSkip(Player p) {
        if (p == null) return true;
        if (!p.isOnline()) return true;
        if (p.isDead()) return true;
        GameMode gm = p.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) return true;
        if (p.isInsideVehicle()) return true;
        return false;
    }

    public static int getPotionLevel(Player p, PotionEffectType type) {
        PotionEffect e = p.getPotionEffect(type);
        if (e == null) return 0;
        return Math.max(0, e.getAmplifier() + 1);
    }

    public static boolean isSpecialEnvironment(Player p) {
        if (p.isSwimming()) return true;
        Material feet = p.getLocation().getBlock().getType();
        Material head = p.getEyeLocation().getBlock().getType();
        if (feet == Material.WATER || head == Material.WATER) return true;
        if (feet == Material.LAVA || head == Material.LAVA) return true;
        if (feet == Material.COBWEB) return true;
        if (feet == Material.LADDER || feet == Material.VINE) return true;
        if (feet == Material.ICE || feet == Material.PACKED_ICE || feet == Material.BLUE_ICE) return true;
        if (feet == Material.SLIME_BLOCK) return true;
        if (feet == Material.HONEY_BLOCK) return true;
        return false;
    }

    public static boolean hasLowCeiling(Player p) {
        Block b = p.getEyeLocation().add(0, 0.45, 0).getBlock();
        Material m = b.getType();
        return m != Material.AIR && m.isSolid();
    }

    public static double allowedHorizontalBps(Player p, boolean onGround, boolean sprinting, boolean sneaking, Config cfg) {
        if (shouldSkip(p)) return Double.POSITIVE_INFINITY;

        double attr = 0.1;
        try {
            if (p.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED) != null) {
                attr = p.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).getValue();
            }
        } catch (Throwable ignored) {}

        double bps = attr * cfg.speedAttrToBps; // default 0.1 -> ~4.317 bps

        if (sprinting) bps *= cfg.sprintMult;
        if (sneaking) bps *= cfg.sneakMult;

        int speedLv = getPotionLevel(p, PotionEffectType.SPEED);
        if (speedLv > 0) {
            bps *= (1.0 + cfg.speedPotionPerLevel * speedLv);
        }

        if (!onGround) {
            bps *= cfg.airMult;
            if (hasLowCeiling(p)) {
                bps *= cfg.headHitAirMult;
            }
        }

        if (isSpecialEnvironment(p)) {
            bps *= cfg.specialEnvLooseMult;
        }

        bps += cfg.baseSlackBps;
        return bps;
    }

    public static final class Config {
        public double speedAttrToBps = 43.17;
        public double sprintMult = 1.30;
        public double sneakMult = 0.30;
        public double airMult = 1.08;
        public double headHitAirMult = 1.15;
        public double speedPotionPerLevel = 0.20;
        public double specialEnvLooseMult = 1.35;
        public double baseSlackBps = 0.75;
        public double peakSlackBps = 1.25;
        public int sampleWindowMs = 550;
        public int minSamples = 6;
        public int minDtMs = 20;
        public int maxDtMs = 220;
        public double violationAdd = 1.0;
        public double peakViolationAdd = 2.5;
    }
}
