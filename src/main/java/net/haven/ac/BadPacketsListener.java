package net.haven.ac;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

import java.text.DecimalFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight BadPackets-style sanity checks via Bukkit events.
 *
 * Notes:
 * - This is not raw packet inspection (no ProtocolLib dependency)
 * - It still catches impossible / highly abnormal movement+rotation states
 */
public final class BadPacketsListener implements Listener {

    private final AntiCheatLitePlugin plugin;
    private final ViolationManager vl;

    private final boolean alertsEnabled;
    private final String alertFormat;
    private final String bypassPermission;

    private final boolean enabled;
    private final double maxAbsCoord;
    private final double invalidPitchLimit;
    private final double invalidPitchVlAdd;

    private final long snapWindowMs;
    private final double snapYawDeg;
    private final double snapPitchDeg;
    private final double snapBufferMin;
    private final double snapBufferDecay;
    private final double snapVlAdd;

    private final boolean cancelOnFlag;

    private final PunishAction punishAction;
    private final double punishThreshold;
    private final boolean setbackOnFlag;

    private final Map<UUID, State> states = new ConcurrentHashMap<UUID, State>();

    private static final DecimalFormat DF2 = new DecimalFormat("0.00");

    public BadPacketsListener(AntiCheatLitePlugin plugin, ViolationManager vl, FileConfiguration cfg) {
        this.plugin = plugin;
        this.vl = vl;

        this.alertsEnabled = cfg.getBoolean("alerts.enabled", true);
        this.alertFormat = cfg.getString("alerts.format", "&c[AC]&7 {player} &f{check} &7VL={vl} &8({details})");
        this.bypassPermission = cfg.getString("bypass_permission", "anticheatlite.bypass");

        this.enabled = cfg.getBoolean("checks.badpackets.enabled", true);
        this.maxAbsCoord = cfg.getDouble("checks.badpackets.max_abs_coord", 30000000.0);
        this.invalidPitchLimit = cfg.getDouble("checks.badpackets.invalid_pitch_limit", 90.0);
        this.invalidPitchVlAdd = cfg.getDouble("checks.badpackets.invalid_pitch_vl_add", 3.0);

        this.snapWindowMs = cfg.getLong("checks.badpackets.snap_window_ms", 120L);
        this.snapYawDeg = cfg.getDouble("checks.badpackets.snap_yaw_deg", 220.0);
        this.snapPitchDeg = cfg.getDouble("checks.badpackets.snap_pitch_deg", 120.0);
        this.snapBufferMin = cfg.getDouble("checks.badpackets.snap_buffer_min", 2.0);
        this.snapBufferDecay = cfg.getDouble("checks.badpackets.snap_buffer_decay", 0.25);
        this.snapVlAdd = cfg.getDouble("checks.badpackets.snap_vl_add", 1.2);

        this.cancelOnFlag = cfg.getBoolean("checks.badpackets.cancel_on_flag", true);

        this.punishAction = PunishAction.fromString(cfg.getString("punishments.action", "SETBACK"));
        this.punishThreshold = cfg.getDouble("punishments.threshold_vl", 6.0);
        this.setbackOnFlag = cfg.getBoolean("punishments.setback_on_flag", true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onMove(PlayerMoveEvent e) {
        if (!enabled) return;
        if (e.getTo() == null) return;

        Player p = e.getPlayer();
        if (p == null || shouldSkip(p)) return;

        Location to = e.getTo();
        Location from = e.getFrom();

        if (!isFiniteLocation(to) || !isFiniteLocation(from)) {
            flag(p, e, invalidPitchVlAdd, "non-finite location");
            return;
        }

        if (Math.abs(to.getX()) > maxAbsCoord || Math.abs(to.getY()) > maxAbsCoord || Math.abs(to.getZ()) > maxAbsCoord) {
            flag(p, e, invalidPitchVlAdd, "coord overflow");
            return;
        }

        float pitch = to.getPitch();
        if (pitch > invalidPitchLimit || pitch < -invalidPitchLimit) {
            flag(p, e, invalidPitchVlAdd, "pitch=" + DF2.format(pitch));
            return;
        }

        if (from.getWorld() == null || to.getWorld() == null || !from.getWorld().equals(to.getWorld())) {
            states.remove(p.getUniqueId());
            return;
        }

        State st = states.computeIfAbsent(p.getUniqueId(), k -> new State());
        long now = System.currentTimeMillis();

        if (st.hasRot) {
            long dt = Math.max(1L, now - st.lastRotAt);
            double dyaw = Math.abs(deltaYaw(normalizeYaw(to.getYaw()), st.lastYaw));
            double dpitch = Math.abs(to.getPitch() - st.lastPitch);

            if (dt <= snapWindowMs && (dyaw >= snapYawDeg || dpitch >= snapPitchDeg)) {
                st.snapBuffer = Math.min(6.0, st.snapBuffer + 1.0);
            } else {
                st.snapBuffer = Math.max(0.0, st.snapBuffer - snapBufferDecay);
            }

            if (st.snapBuffer >= snapBufferMin) {
                flag(p, e, snapVlAdd,
                        "snap(yaw=" + DF2.format(dyaw) +
                                ",pitch=" + DF2.format(dpitch) +
                                ",dt=" + dt + "ms,buf=" + DF2.format(st.snapBuffer) + ")");
                st.snapBuffer = 0.0;
            }
        }

        st.hasRot = true;
        st.lastYaw = normalizeYaw(to.getYaw());
        st.lastPitch = to.getPitch();
        st.lastRotAt = now;
    }

    private void flag(Player p, PlayerMoveEvent e, double add, String details) {
        double next = vl.addVl(p.getUniqueId(), CheckType.BADPACKETS, add);
        alert(p, "BADPACKETS", next, details);

        if (cancelOnFlag && plugin.canPunish()) {
            e.setTo(e.getFrom());
        }

        if (punishAction == PunishAction.SETBACK && setbackOnFlag) {
            plugin.setback(p);
        } else {
            maybePunish(p);
        }
    }

    private boolean shouldSkip(Player p) {
        if (p == null || !p.isOnline()) return true;
        if (p.hasPermission(bypassPermission)) return true;

        GameMode gm = p.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) return true;
        if (p.isInsideVehicle()) return true;
        if (p.getAllowFlight() || p.isFlying()) return true;
        if (p.isDead()) return true;

        return false;
    }

    private void maybePunish(Player p) {
        if (!plugin.canPunish()) return;
        double total = vl.getTotalVl(p.getUniqueId());
        if (total < punishThreshold) return;
        if (punishAction == PunishAction.KICK) {
            p.kickPlayer(AntiCheatLitePlugin.color("&c[AC] Invalid movement packets detected."));
        }
    }

    private void alert(Player suspected, String check, double checkVl, String details) {
        if (!alertsEnabled || !plugin.isChatDebugEnabled()) return;
        String msg = alertFormat
                .replace("{player}", suspected.getName())
                .replace("{check}", check)
                .replace("{vl}", DF2.format(checkVl))
                .replace("{details}", details);
        msg = AntiCheatLitePlugin.color(msg);

        for (Player online : Bukkit.getOnlinePlayers()) {
            online.sendMessage(msg);
        }
        plugin.recordLastFlag(suspected, check, details);
        plugin.getLogger().info("[AC] " + suspected.getName() + " " + check + " VL=" + DF2.format(checkVl) + " (" + details + ")");
    }

    private static boolean isFiniteLocation(Location l) {
        if (l == null) return false;
        return Double.isFinite(l.getX()) && Double.isFinite(l.getY()) && Double.isFinite(l.getZ())
                && Float.isFinite(l.getYaw()) && Float.isFinite(l.getPitch());
    }

    private static float normalizeYaw(float yaw) {
        float y = yaw;
        while (y <= -180f) y += 360f;
        while (y > 180f) y -= 360f;
        return y;
    }

    private static double deltaYaw(float a, float b) {
        double d = a - b;
        while (d <= -180.0) d += 360.0;
        while (d > 180.0) d -= 360.0;
        return d;
    }

    private static final class State {
        private boolean hasRot = false;
        private float lastYaw = 0f;
        private float lastPitch = 0f;
        private long lastRotAt = 0L;
        private double snapBuffer = 0.0;
    }
}
