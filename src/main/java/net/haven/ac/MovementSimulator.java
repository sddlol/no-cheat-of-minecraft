package net.haven.ac;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import java.lang.reflect.Method;

    /**
     * Conservative movement allowance model that stays compatible from 1.8.x to modern versions.
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

    public static int getPotionLevel(Player p, String typeName) {
        if (p == null || typeName == null) return 0;
        int amp = Compat.potionAmplifier(p, typeName);
        return Compat.hasPotion(p, typeName) ? Math.max(0, amp + 1) : 0;
    }

    public static boolean isSpecialEnvironment(Player p) {
        if (Compat.isSwimming(p)) return true;
        Material feet = p.getLocation().getBlock().getType();
        Material head = p.getEyeLocation().getBlock().getType();
        if (feet == Material.WATER || head == Material.WATER) return true;
        if (feet == Material.LAVA || head == Material.LAVA) return true;
        if (feet == Compat.material("COBWEB","WEB")) return true;
        if (feet == Material.LADDER || feet == Material.VINE) return true;
        if (Compat.isOneOf(feet, "ICE", "PACKED_ICE", "BLUE_ICE")) return true;
        if (Compat.isOneOf(feet, "SLIME_BLOCK", "HONEY_BLOCK")) return true;
        return false;
    }

    public static boolean hasLowCeiling(Player p) {
        Block b = p.getEyeLocation().add(0, 0.45, 0).getBlock();
        Material m = b.getType();
        return m != Material.AIR && m.isSolid();
    }

    private static boolean isUsingSlowItem(Player p) {
        if (p == null) return false;
        // 1.8 blocking
        try {
            if (p.isBlocking()) return true;
        } catch (Throwable ignored) {}

        // 1.9+ hand raised / item use state
        try {
            Method m = p.getClass().getMethod("isHandRaised");
            Object o = m.invoke(p);
            if (o instanceof Boolean && (Boolean) o) return true;
        } catch (Throwable ignored) {}

        try {
            Method m = p.getClass().getMethod("isHandRaised", boolean.class);
            Object o = m.invoke(p, true);
            if (o instanceof Boolean && (Boolean) o) return true;
        } catch (Throwable ignored) {}

        return false;
    }

    public static double allowedHorizontalBps(Player p, boolean onGround, boolean sprinting, boolean sneaking, Config cfg) {
        if (shouldSkip(p)) return Double.POSITIVE_INFINITY;

        // 1.9+ has Attribute API; 1.8 doesn't. Use reflection.
        double attr = 0.1;
        try {
            Class<?> attrCls = Class.forName("org.bukkit.attribute.Attribute");
            Object attrEnum = Enum.valueOf((Class<Enum>) attrCls.asSubclass(Enum.class), "GENERIC_MOVEMENT_SPEED");
            Method getAttr = p.getClass().getMethod("getAttribute", attrCls);
            Object inst = getAttr.invoke(p, attrEnum);
            if (inst != null) {
                Method getVal = inst.getClass().getMethod("getValue");
                attr = ((Number) getVal.invoke(inst)).doubleValue();
            }
        } catch (Throwable ignored) {
        }

        double bps = attr * cfg.speedAttrToBps; // default 0.1 -> ~4.317 bps

        if (sprinting) bps *= cfg.sprintMult;
        if (sneaking) bps *= cfg.sneakMult;
        if (isUsingSlowItem(p)) bps *= cfg.useItemMult;

        int speedLv = getPotionLevel(p, "SPEED");
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
        public double useItemMult = 0.35;
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
