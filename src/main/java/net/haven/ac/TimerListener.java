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
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight timer/tickrate check from move cadence.
 *
 * Without packet hooks we estimate suspicious timer behavior by looking at
 * repeatedly too-short move intervals while player is actually moving.
 */
public final class TimerListener implements Listener {

    private final AntiCheatLitePlugin plugin;
    private final ViolationManager vl;

    private final boolean alertsEnabled;
    private final String alertFormat;
    private final String bypassPermission;

    private final boolean enabled;
    private final int windowMs;
    private final int minSamples;
    private final int minMoveDtMs;
    private final double maxLowDtRatio;
    private final double minHorizPerSample;
    private final double bufferMin;
    private final double bufferDecay;
    private final double vlAdd;
    private final boolean cancelOnFlag;

    private final PunishAction punishAction;
    private final double punishThreshold;
    private final boolean setbackOnFlag;

    private final Map<UUID, State> states = new ConcurrentHashMap<UUID, State>();

    private static final DecimalFormat DF2 = new DecimalFormat("0.00");

    public TimerListener(AntiCheatLitePlugin plugin, ViolationManager vl, FileConfiguration cfg) {
        this.plugin = plugin;
        this.vl = vl;

        this.alertsEnabled = cfg.getBoolean("alerts.enabled", true);
        this.alertFormat = cfg.getString("alerts.format", "&c[AC]&7 {player} &f{check} &7VL={vl} &8({details})");
        this.bypassPermission = cfg.getString("bypass_permission", "anticheatlite.bypass");

        this.enabled = cfg.getBoolean("checks.timer.enabled", true);
        this.windowMs = cfg.getInt("checks.timer.window_ms", 1500);
        this.minSamples = cfg.getInt("checks.timer.min_samples", 14);
        this.minMoveDtMs = cfg.getInt("checks.timer.min_move_dt_ms", 45);
        this.maxLowDtRatio = cfg.getDouble("checks.timer.max_low_dt_ratio", 0.45);
        this.minHorizPerSample = cfg.getDouble("checks.timer.min_horiz_per_sample", 0.03);
        this.bufferMin = cfg.getDouble("checks.timer.buffer_min", 2.0);
        this.bufferDecay = cfg.getDouble("checks.timer.buffer_decay", 0.20);
        this.vlAdd = cfg.getDouble("checks.timer.vl_add", 1.2);
        this.cancelOnFlag = cfg.getBoolean("checks.timer.cancel_on_flag", false);

        this.punishAction = PunishAction.fromString(cfg.getString("punishments.action", "SETBACK"));
        this.punishThreshold = cfg.getDouble("punishments.threshold_vl", 6.0);
        this.setbackOnFlag = cfg.getBoolean("punishments.setback_on_flag", true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onMove(PlayerMoveEvent e) {
        if (!enabled) return;
        if (e.getTo() == null) return;

        Player p = e.getPlayer();
        if (p == null || shouldSkip(p)) return;

        Location from = e.getFrom();
        Location to = e.getTo();
        if (from.getWorld() == null || to.getWorld() == null || !from.getWorld().equals(to.getWorld())) return;

        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        if (horiz < minHorizPerSample) return;

        long now = System.currentTimeMillis();
        State st = states.computeIfAbsent(p.getUniqueId(), k -> new State());

        if (st.lastMoveAt <= 0L) {
            st.lastMoveAt = now;
            return;
        }

        long dt = Math.max(1L, now - st.lastMoveAt);
        st.lastMoveAt = now;

        st.samples.addLast(new Sample(now, dt, horiz));
        while (!st.samples.isEmpty() && (now - st.samples.peekFirst().t) > windowMs) st.samples.removeFirst();

        if (st.samples.size() < minSamples) return;

        double avgDt = 0.0;
        int low = 0;
        double moveSum = 0.0;
        int n = 0;
        for (Sample s : st.samples) {
            avgDt += s.dt;
            moveSum += s.horiz;
            if (s.dt < minMoveDtMs) low++;
            n++;
        }
        if (n <= 0) return;
        avgDt /= n;
        double lowRatio = low * 1.0 / n;

        boolean suspicious = avgDt < minMoveDtMs && lowRatio > maxLowDtRatio && moveSum > (n * minHorizPerSample * 1.2);
        if (suspicious) {
            st.buffer = Math.min(6.0, st.buffer + 1.0);
        } else {
            st.buffer = Math.max(0.0, st.buffer - bufferDecay);
        }

        if (st.buffer < bufferMin) return;

        st.buffer = Math.max(0.0, st.buffer - 0.5);

        double next = vl.addVl(p.getUniqueId(), CheckType.TIMER, vlAdd);
        alert(p, "TIMER", next,
                "avgDt=" + DF2.format(avgDt) +
                        ", lowRatio=" + DF2.format(lowRatio) +
                        ", n=" + n +
                        ", buf=" + DF2.format(st.buffer));

        if (cancelOnFlag && plugin.canPunish()) {
            e.setTo(from);
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
        if (Compat.isGliding(p) || Compat.isSwimming(p) || Compat.isRiptiding(p)) return true;
        if (p.isDead()) return true;
        return false;
    }

    private void maybePunish(Player p) {
        if (!plugin.canPunish()) return;
        double total = vl.getTotalVl(p.getUniqueId());
        if (total < punishThreshold) return;
        if (punishAction == PunishAction.KICK) {
            p.kickPlayer(AntiCheatLitePlugin.color("&c[AC] Suspicious timer behavior detected."));
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

    private static final class State {
        private long lastMoveAt = 0L;
        private double buffer = 0.0;
        private final Deque<Sample> samples = new ArrayDeque<Sample>();
    }

    private static final class Sample {
        private final long t;
        private final long dt;
        private final double horiz;

        private Sample(long t, long dt, double horiz) {
            this.t = t;
            this.dt = dt;
            this.horiz = horiz;
        }
    }
}
