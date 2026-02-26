package net.haven.ac;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A more "sticky" setback system.
 *
 * Grim-style anti-cheats treat the server as authoritative and make sure
 * the client is forced back to a known-good position repeatedly so fast fly/blink
 * can't just keep drifting away.
 *
 * We cannot send custom packets here (no ProtocolLib), so we approximate by:
 *  - teleporting to last safe
 *  - zeroing velocity
 *  - freezing movement for a few ticks (cancel PlayerMoveEvent)
 *  - repeating teleports for a couple ticks to fight client-side prediction
 */
public final class SetbackManager {

    private final AntiCheatLitePlugin plugin;
    private final Map<UUID, Integer> freezeTicks = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastSetbackAt = new ConcurrentHashMap<>();

    // Tuning (loaded from config)
    private volatile int freezeTicksOnSetback = 10;
    private volatile int repeatTeleports = 3;
    private volatile long minSetbackIntervalMs = 150L;

    public SetbackManager(AntiCheatLitePlugin plugin) {
        this.plugin = plugin;
    }

    public void reloadFromConfig() {
        freezeTicksOnSetback = Math.max(0, plugin.getConfig().getInt("setback.freeze_ticks", 10));
        repeatTeleports = Math.max(0, plugin.getConfig().getInt("setback.repeat_teleports", 3));
        minSetbackIntervalMs = Math.max(0L, plugin.getConfig().getLong("setback.min_interval_ms", 150L));
    }

    public void tickDownFreeze(Player p) {
        if (p == null) return;
        UUID id = p.getUniqueId();
        Integer left = freezeTicks.get(id);
        if (left == null) return;
        if (left <= 1) freezeTicks.remove(id);
        else freezeTicks.put(id, left - 1);
    }

    public boolean isFrozen(Player p) {
        if (p == null) return false;
        Integer left = freezeTicks.get(p.getUniqueId());
        return left != null && left > 0;
    }

    public Location getLastSafe(Player p) {
        if (p == null) return null;
        return plugin.getLastSafeInternal(p.getUniqueId());
    }

    public void setback(Player p) {
        if (p == null || !p.isOnline()) return;

        Location safe = getLastSafe(p);
        if (safe == null || safe.getWorld() == null) return;
        if (p.getWorld() != null && !p.getWorld().equals(safe.getWorld())) return;

        long now = System.currentTimeMillis();
        long last = lastSetbackAt.getOrDefault(p.getUniqueId(), 0L);
        if (now - last < minSetbackIntervalMs) {
            // Still apply freeze so the player can't keep drifting.
            if (freezeTicksOnSetback > 0) freezeTicks.put(p.getUniqueId(), freezeTicksOnSetback);
            return;
        }
        lastSetbackAt.put(p.getUniqueId(), now);

        if (freezeTicksOnSetback > 0) {
            freezeTicks.put(p.getUniqueId(), freezeTicksOnSetback);
        }

        // Immediate teleport + a couple repeats on next ticks.
        hardTeleport(p, safe);
        for (int i = 1; i <= repeatTeleports; i++) {
            final int delay = i;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!p.isOnline()) return;
                Location s = getLastSafe(p);
                if (s == null || s.getWorld() == null) return;
                if (p.getWorld() != null && !p.getWorld().equals(s.getWorld())) return;
                hardTeleport(p, s);
            }, delay);
        }
    }

    private void hardTeleport(Player p, Location safe) {
        Location to = safe.clone();
        p.teleport(to, PlayerTeleportEvent.TeleportCause.PLUGIN);
        p.setVelocity(new Vector(0, 0, 0));
        p.setFallDistance(0f);
    }
}
