package net.haven.ac;

import org.bukkit.entity.Player;

import java.lang.reflect.Method;

/**
 * Ping accessor that works on Paper/Spigot 1.16.5 without hard NMS imports.
 */
public final class PingUtil {

    private static volatile Method cachedGetPing;

    private PingUtil() {}

    public static int getPingMs(Player player) {
        // Paper exposes getPing() on modern versions; 1.16.5 may not.
        // Try reflection for compatibility.
        try {
            Method m = cachedGetPing;
            if (m == null) {
                m = player.getClass().getMethod("getPing");
                m.setAccessible(true);
                cachedGetPing = m;
            }
            Object v = m.invoke(player);
            if (v instanceof Integer) return Math.max(0, (Integer) v);
        } catch (Throwable ignored) {
            // fall through to CraftPlayer field access
        }

        // Fallback: CraftPlayer has a public 'ping' field in many versions.
        try {
            Object craftPlayer = player;
            // Bukkit proxy is already CraftPlayer on CraftBukkit/Paper.
            return Math.max(0, craftPlayer.getClass().getField("ping").getInt(craftPlayer));
        } catch (Throwable ignored) {
            return 0;
        }
    }
}
