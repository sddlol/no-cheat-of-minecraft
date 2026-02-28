package net.haven.ac;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;

import java.text.DecimalFormat;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ClickListener implements Listener {

    private final AntiCheatLitePlugin plugin;
    private final ViolationManager vl;

    private final boolean alertsEnabled;
    private final String alertFormat;
    private final String bypassPermission;

    private final boolean enabled;
    private final int windowMs;
    private final int minCps;
    private final int instantCps;
    private final double maxStdDevMs;
    private final double instantMaxStdDevMs;
    private final double vlAdd;
    private final double vlAddInstant;

    private final boolean ignoreDigging;
    private final int digIgnoreMs;

    private final PunishAction punishAction;
    private final double punishThreshold;
    private final boolean setbackOnFlag;

    private final Map<UUID, ClickState> clickStates = new ConcurrentHashMap<>();

    private static final DecimalFormat DF2 = new DecimalFormat("0.00");

    public ClickListener(AntiCheatLitePlugin plugin, ViolationManager vl, FileConfiguration cfg) {
        this.plugin = plugin;
        this.vl = vl;

        this.alertsEnabled = cfg.getBoolean("alerts.enabled", true);
        this.alertFormat = cfg.getString("alerts.format", "&c[AC]&7 {player} &f{check} &7VL={vl} &8({details})");
        this.bypassPermission = cfg.getString("bypass_permission", "anticheatlite.bypass");

        this.enabled = cfg.getBoolean("checks.autoclicker.enabled", true);
        this.windowMs = cfg.getInt("checks.autoclicker.window_ms", 1000);
        this.minCps = cfg.getInt("checks.autoclicker.min_cps", 12);
        this.instantCps = cfg.getInt("checks.autoclicker.instant_cps", 14);
        this.maxStdDevMs = cfg.getDouble("checks.autoclicker.max_stddev_ms", 6.0);
        this.instantMaxStdDevMs = cfg.getDouble("checks.autoclicker.instant_max_stddev_ms", 3.5);
        this.vlAdd = cfg.getDouble("checks.autoclicker.vl_add", 1.2);
        this.vlAddInstant = cfg.getDouble("checks.autoclicker.vl_add_instant", 3.0);

        // Mining (left-click block) produces very stable high-rate arm swings on many clients.
        // To avoid false flags while digging, ignore arm swings shortly after a block-damage action.
        this.ignoreDigging = cfg.getBoolean("checks.autoclicker.ignore_digging", true);
        this.digIgnoreMs = cfg.getInt("checks.autoclicker.dig_ignore_ms", 350);

        this.punishAction = PunishAction.fromString(cfg.getString("punishments.action", "SETBACK"));
        this.punishThreshold = cfg.getDouble("punishments.threshold_vl", 6.0);
        this.setbackOnFlag = cfg.getBoolean("punishments.setback_on_flag", true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onAnim(PlayerAnimationEvent e) {
        if (!enabled) return;
        if (e.getAnimationType() != PlayerAnimationType.ARM_SWING) return;

        Player p = e.getPlayer();
        if (p.hasPermission(bypassPermission)) return;
        if (p.getGameMode().name().equalsIgnoreCase("CREATIVE") || p.getGameMode().name().equalsIgnoreCase("SPECTATOR")) return;

        ClickState st = clickStates.computeIfAbsent(p.getUniqueId(), k -> new ClickState());
        long now = System.currentTimeMillis();

        if (ignoreDigging && (now - st.lastDigMs) <= digIgnoreMs) {
            return;
        }
        st.times.addLast(now);
        while (st.times.size() > 60) st.times.removeFirst();

        while (!st.times.isEmpty() && (now - st.times.peekFirst()) > windowMs) {
            st.times.removeFirst();
        }

        int clicks = st.times.size();
        double cps = clicks / (windowMs / 1000.0);
        if (cps < minCps) return;

        double std = stdDevIntervalsMs(st.times);

        boolean instant = cps >= instantCps && std <= instantMaxStdDevMs;
        boolean stable = std <= maxStdDevMs;

        if (instant || stable) {
            double add = instant ? vlAddInstant : vlAdd;
            double next = vl.addVl(p.getUniqueId(), CheckType.AUTOCLICKER, add);
            alert(p, "AUTOCLICK", next, "cps=" + DF2.format(cps) + ", std=" + DF2.format(std) + "ms");

            if (punishAction == PunishAction.SETBACK && setbackOnFlag) {
                plugin.setback(p);
            } else {
                maybePunish(p);
            }
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBlockDamage(BlockDamageEvent e) {
        if (!enabled) return;
        Player p = e.getPlayer();
        ClickState st = clickStates.computeIfAbsent(p.getUniqueId(), k -> new ClickState());
        st.lastDigMs = System.currentTimeMillis();
    }

    private void maybePunish(Player p) {
        if (!plugin.canPunish()) return;
        double total = vl.getTotalVl(p.getUniqueId());
        if (total < punishThreshold) return;
        if (punishAction == PunishAction.KICK) {
            p.kickPlayer(AntiCheatLitePlugin.color("&c[AC] Suspicious clicking detected."));
        }
    }

    private static double stdDevIntervalsMs(Deque<Long> times) {
        if (times.size() < 6) return 9999.0;
        Long prev = null;
        double sum = 0.0;
        int n = 0;
        for (Long t : times) {
            if (prev != null) {
                sum += (t - prev);
                n++;
            }
            prev = t;
        }
        if (n <= 1) return 9999.0;
        double mean = sum / n;

        prev = null;
        double ss = 0.0;
        int m = 0;
        for (Long t : times) {
            if (prev != null) {
                double d = (t - prev) - mean;
                ss += d * d;
                m++;
            }
            prev = t;
        }
        if (m <= 1) return 9999.0;
        return Math.sqrt(ss / (m - 1));
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

    private static final class ClickState {
        private final Deque<Long> times = new ArrayDeque<>();
        private volatile long lastDigMs = 0L;
    }
}
