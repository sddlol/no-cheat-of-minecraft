package net.haven.ac;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/**
 * Cross-version reach helper.
 *
 * <p>On modern servers (1.13+) we try to use ray-trace / bounding-box via reflection.
 * On 1.8.x we fall back to a conservative distance + hasLineOfSight check.</p>
 */
public final class ReachUtil {

    private ReachUtil() {}

    /**
     * Estimate the minimum distance from attacker's eye to target hitbox.
     * If hitbox API isn't available, falls back to eye-to-location distance.
     */
    public static double distanceToTarget(Player attacker, Entity target) {
        if (attacker == null || target == null) return 0.0;
        Location eye = attacker.getEyeLocation();

        Double bbDist = Compat.distanceToEntityHitboxIfAvailable(eye, target);
        if (bbDist != null) return bbDist;

        // 1.8 fallback: use target's center-ish location with a small reduction.
        Location tLoc = target.getLocation();
        double d = eye.distance(tLoc);
        return Math.max(0.0, d - 0.35);
    }

    /**
     * Returns true if we can reasonably say the attacker is hitting through a wall.
     */
    public static boolean isThroughWall(Player attacker, Entity target, double maxDistance) {
        if (attacker == null || target == null) return false;

        // If the old API says there is LOS, allow.
        try {
            if (attacker.hasLineOfSight(target)) return false;
        } catch (Throwable ignored) {
            // Some forks behave oddly; continue to ray-trace attempt.
        }

        // Modern attempt: ray trace from eye towards target.
        try {
            Location eye = attacker.getEyeLocation();
            double h = 1.0;
            try {
                h = ((Number) target.getClass().getMethod("getHeight").invoke(target)).doubleValue();
            } catch (Throwable ignored) {
            }
            Location t = target.getLocation().add(0, h * 0.5, 0);
            Vector dir = t.toVector().subtract(eye.toVector());
            double dist = dir.length();
            if (dist <= 0.001) return false;
            if (maxDistance > 0 && dist > maxDistance) dist = maxDistance;
            dir.normalize();

            World w = eye.getWorld();
            if (w != null && Compat.rayTraceBlocksHits(w, eye, dir, dist)) {
                // If there is a block hit AND hasLineOfSight was false, likely wall.
                return true;
            }
        } catch (Throwable ignored) {
        }

        // Conservative 1.8 fallback: if hasLineOfSight already false, treat as wall.
        return true;
    }
}
