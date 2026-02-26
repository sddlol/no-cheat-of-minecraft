package net.haven.ac;

import org.bukkit.Material;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.lang.reflect.Method;
import java.util.Locale;

public final class Compat {
    private Compat() {}

    public static boolean isOneOf(Material m, String... names) {
        if (m == null || names == null) return false;
        String mn = m.name();
        for (String n : names) {
            if (n != null && mn.equalsIgnoreCase(n)) return true;
        }
        return false;
    }

    public static Material material(String... names) {
        if (names == null) return null;
        for (String n : names) {
            if (n == null) continue;
            try { return Material.valueOf(n.toUpperCase(Locale.ROOT)); } catch (Throwable ignored) {}
        }
        return null;
    }

    public static boolean isCobweb(Material m) {
        return isOneOf(m, "COBWEB", "WEB");
    }

    public static PotionEffectType potionType(String name) {
        if (name == null) return null;
        PotionEffectType t = PotionEffectType.getByName(name.toUpperCase(Locale.ROOT));
        if (t != null) return t;
        try { return (PotionEffectType) PotionEffectType.class.getField(name.toUpperCase(Locale.ROOT)).get(null); } catch (Throwable ignored) {}
        return null;
    }

    public static boolean hasPotion(Player p, String typeName) {
        PotionEffectType t = potionType(typeName);
        if (p == null || t == null) return false;
        for (PotionEffect pe : p.getActivePotionEffects()) {
            if (pe != null && pe.getType() != null && pe.getType().equals(t)) return true;
        }
        return false;
    }

    public static int potionAmplifier(Player p, String typeName) {
        PotionEffectType t = potionType(typeName);
        if (p == null || t == null) return 0;
        int best = 0;
        for (PotionEffect pe : p.getActivePotionEffects()) {
            if (pe != null && pe.getType() != null && pe.getType().equals(t)) best = Math.max(best, pe.getAmplifier());
        }
        return best;
    }

    public static PotionEffect getPotionEffect(Player p, PotionEffectType t) {
        if (p == null || t == null) return null;
        try {
            Method m = p.getClass().getMethod("getPotionEffect", PotionEffectType.class);
            Object o = m.invoke(p, t);
            if (o instanceof PotionEffect) return (PotionEffect) o;
        } catch (Throwable ignored) {}
        for (PotionEffect pe : p.getActivePotionEffects()) {
            if (pe != null && pe.getType() != null && pe.getType().equals(t)) return pe;
        }
        return null;
    }

    public static boolean isGliding(Player p) {
        try {
            Method m = p.getClass().getMethod("isGliding");
            Object o = m.invoke(p);
            return o instanceof Boolean && (Boolean) o;
        } catch (Throwable ignored) {}
        return false;
    }

    public static boolean isSwimming(Player p) {
        try {
            Method m = p.getClass().getMethod("isSwimming");
            Object o = m.invoke(p);
            return o instanceof Boolean && (Boolean) o;
        } catch (Throwable ignored) {}
        return false;
    }

    public static boolean isRiptiding(Player p) {
        try {
            Method m = p.getClass().getMethod("isRiptiding");
            Object o = m.invoke(p);
            return o instanceof Boolean && (Boolean) o;
        } catch (Throwable ignored) {}
        return false;
    }

    public static boolean hasLineOfSight(Player p, Entity e) {
        if (p == null || e == null) return true;
        try { return p.hasLineOfSight(e); } catch (Throwable ignored) { return true; }
    }

    /**
     * True if a ray from start in direction hits a block before maxDistance.
     * Uses World#rayTraceBlocks when available (1.13+), otherwise falls back to a simple BlockIterator scan.
     */
    public static boolean rayTraceBlocksHits(World w, Location start, Vector dir, double maxDistance) {
        if (w == null || start == null || dir == null) return false;
        // Modern API (1.13+): World#rayTraceBlocks(Location, Vector, double)
        try {
            Method m = w.getClass().getMethod("rayTraceBlocks", Location.class, Vector.class, double.class);
            Object res = m.invoke(w, start, dir, maxDistance);
            return res != null;
        } catch (Throwable ignored) {
            // Legacy fallback
        }

        try {
            // org.bukkit.util.BlockIterator exists in 1.8+ but constructors vary by version.
            Class<?> itClz = Class.forName("org.bukkit.util.BlockIterator");
            Object it;
            int maxSteps = Math.max(1, (int) Math.ceil(maxDistance * 4.0));
            try {
                it = itClz.getConstructor(World.class, Vector.class, Vector.class, double.class, int.class)
                        .newInstance(w, start.toVector(), dir, 0.0, maxSteps);
            } catch (Throwable ctor1) {
                try {
                    it = itClz.getConstructor(World.class, Location.class, Vector.class, double.class, int.class)
                            .newInstance(w, start, dir, 0.0, maxSteps);
                } catch (Throwable ctor2) {
                    // last resort: (World, Location, Vector)
                    it = itClz.getConstructor(World.class, Location.class, Vector.class)
                            .newInstance(w, start, dir);
                }
            }

            Method hasNext = itClz.getMethod("hasNext");
            Method next = itClz.getMethod("next");
            while ((Boolean) hasNext.invoke(it)) {
                Object block = next.invoke(it);
                if (block == null) continue;
                Method getType = block.getClass().getMethod("getType");
                Object mt = getType.invoke(block);
                if (mt instanceof Material) {
                    Material mat = (Material) mt;
                    if (mat != Material.AIR) {
                        // treat any non-air as blocking (cheap + safe for reach)
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
            // If even legacy iterator fails, assume no hit.
        }
        return false;
    }

    /**
     * Attempts to compute distance from a point to an entity hitbox using Entity#getBoundingBox (1.13+).
     * Falls back to distance to entity location on legacy versions.
     */
    public static double distanceToEntityHitboxIfAvailable(Location point, Entity e) {
        if (point == null || e == null) return Double.MAX_VALUE;
        try {
            Method getBb = e.getClass().getMethod("getBoundingBox");
            Object bb = getBb.invoke(e);
            if (bb != null) {
                // BoundingBox has getters: getMinX/Y/Z, getMaxX/Y/Z
                double minX = (Double) bb.getClass().getMethod("getMinX").invoke(bb);
                double minY = (Double) bb.getClass().getMethod("getMinY").invoke(bb);
                double minZ = (Double) bb.getClass().getMethod("getMinZ").invoke(bb);
                double maxX = (Double) bb.getClass().getMethod("getMaxX").invoke(bb);
                double maxY = (Double) bb.getClass().getMethod("getMaxY").invoke(bb);
                double maxZ = (Double) bb.getClass().getMethod("getMaxZ").invoke(bb);

                double px = point.getX(), py = point.getY(), pz = point.getZ();
                // clamp point to AABB
                double cx = (px < minX) ? minX : Math.min(px, maxX);
                double cy = (py < minY) ? minY : Math.min(py, maxY);
                double cz = (pz < minZ) ? minZ : Math.min(pz, maxZ);

                double dx = px - cx, dy = py - cy, dz = pz - cz;
                return Math.sqrt(dx * dx + dy * dy + dz * dz);
            }
        } catch (Throwable ignored) {}

        try {
            return point.distance(e.getLocation());
        } catch (Throwable ignored) {
            return Double.MAX_VALUE;
        }
    }
}
