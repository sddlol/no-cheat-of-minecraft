package net.haven.ac;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * Reach / line-of-sight helpers.
 *
 * Lightweight (not full GrimAC), but does:
 *  - true ray-vs-AABB intersection from eye direction
 *  - optional block ray trace to detect "through wall" hits
 */
public final class ReachUtil {

    private ReachUtil() {}

    /**
     * Distance from eye to the closest point on an entity's bounding box (fallback).
     */
    public static double eyeToHitboxDistance(Location attackerEye, Entity target) {
        BoundingBox bb = target.getBoundingBox();
        Vector eye = attackerEye.toVector();
        double cx = clamp(eye.getX(), bb.getMinX(), bb.getMaxX());
        double cy = clamp(eye.getY(), bb.getMinY(), bb.getMaxY());
        double cz = clamp(eye.getZ(), bb.getMinZ(), bb.getMaxZ());
        return eye.distance(new Vector(cx, cy, cz));
    }

    /**
     * Computes the distance along the attacker's look ray to the target hitbox.
     * Returns POSITIVE_INFINITY if no intersection.
     *
     * We expand the hitbox slightly to be tolerant to interpolation / latency.
     */
    public static double eyeRayToHitboxDistance(Player attacker, Entity target, double hitboxExpand) {
        Location eyeLoc = attacker.getEyeLocation();
        Vector origin = eyeLoc.toVector();
        Vector dir = eyeLoc.getDirection();
        if (dir == null) return Double.POSITIVE_INFINITY;
        dir = dir.clone();
        if (dir.lengthSquared() < 1e-9) return Double.POSITIVE_INFINITY;
        dir.normalize();

        BoundingBox bb = target.getBoundingBox();
        if (hitboxExpand > 0.0) bb = bb.expand(hitboxExpand);

        Double t = rayIntersectAabb(origin, dir, bb, 0.0, 8.0); // hard cap for safety
        return t == null ? Double.POSITIVE_INFINITY : t.doubleValue();
    }

    /**
     * True if there is a solid block between attacker eye and distance maxDist along direction.
     */
    public static boolean isBlockedByWorld(World world, Location eyeLoc, Vector dirNorm, double maxDist) {
        if (world == null || eyeLoc == null || dirNorm == null) return false;
        if (maxDist <= 0.0) return false;

        RayTraceResult hit = world.rayTraceBlocks(
                eyeLoc,
                dirNorm,
                maxDist,
                FluidCollisionMode.NEVER,
                true
        );
        return hit != null && hit.getHitBlock() != null;
    }

    /**
     * Convenience: returns ray distance and whether LoS is blocked.
     */
    public static ReachResult computeReach(Player attacker, LivingEntity target) {
        double t = eyeRayToHitboxDistance(attacker, target, 0.10); // small tolerance
        if (!Double.isFinite(t)) {
            double fallback = eyeToHitboxDistance(attacker.getEyeLocation(), target);
            return new ReachResult(fallback, false, false);
        }

        Location eye = attacker.getEyeLocation();
        Vector dir = eye.getDirection().clone().normalize();
        boolean blocked = isBlockedByWorld(attacker.getWorld(), eye, dir, t);
        return new ReachResult(t, true, blocked);
    }

    public static final class ReachResult {
        public final double distance;
        public final boolean rayIntersects;
        public final boolean blocked;

        public ReachResult(double distance, boolean rayIntersects, boolean blocked) {
            this.distance = distance;
            this.rayIntersects = rayIntersects;
            this.blocked = blocked;
        }
    }

    // --- math helpers ---

    private static double clamp(double v, double min, double max) {
        return v < min ? min : (v > max ? max : v);
    }

    /**
     * Ray-AABB intersection using the slab method.
     * Returns the first intersection t in [tMin, tMax], or null if none.
     */
    private static Double rayIntersectAabb(Vector origin, Vector dir, BoundingBox bb, double tMin, double tMax) {
        double ox = origin.getX(), oy = origin.getY(), oz = origin.getZ();
        double dx = dir.getX(), dy = dir.getY(), dz = dir.getZ();

        // X slab
        if (Math.abs(dx) < 1e-9) {
            if (ox < bb.getMinX() || ox > bb.getMaxX()) return null;
        } else {
            double inv = 1.0 / dx;
            double t1 = (bb.getMinX() - ox) * inv;
            double t2 = (bb.getMaxX() - ox) * inv;
            if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);
            if (tMax < tMin) return null;
        }

        // Y slab
        if (Math.abs(dy) < 1e-9) {
            if (oy < bb.getMinY() || oy > bb.getMaxY()) return null;
        } else {
            double inv = 1.0 / dy;
            double t1 = (bb.getMinY() - oy) * inv;
            double t2 = (bb.getMaxY() - oy) * inv;
            if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);
            if (tMax < tMin) return null;
        }

        // Z slab
        if (Math.abs(dz) < 1e-9) {
            if (oz < bb.getMinZ() || oz > bb.getMaxZ()) return null;
        } else {
            double inv = 1.0 / dz;
            double t1 = (bb.getMinZ() - oz) * inv;
            double t2 = (bb.getMaxZ() - oz) * inv;
            if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);
            if (tMax < tMin) return null;
        }

        if (tMax < 0) return null;
        double tHit = tMin >= 0 ? tMin : tMax;
        if (tHit < 0) return null;
        if (tHit < tMin || tHit > tMax) return null;
        return tHit;
    }
}
