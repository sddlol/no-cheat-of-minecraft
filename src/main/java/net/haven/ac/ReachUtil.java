package net.haven.ac;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

public final class ReachUtil {

    private ReachUtil() {}

    /**
     * Distance from eye to the closest point on an entity's bounding box.
     */
    public static double eyeToHitboxDistance(Location attackerEye, Entity target) {
        BoundingBox bb = target.getBoundingBox();
        Vector eye = attackerEye.toVector();
        Vector closest = new Vector(
                clamp(eye.getX(), bb.getMinX(), bb.getMaxX()),
                clamp(eye.getY(), bb.getMinY(), bb.getMaxY()),
                clamp(eye.getZ(), bb.getMinZ(), bb.getMaxZ())
        );
        return eye.distance(closest);
    }

    public static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
