package net.haven.ac;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.util.Vector;

import java.text.DecimalFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight AntiKB / Velocity check.
 *
 * Idea:
 * - when player is damaged, wait for PlayerVelocityEvent
 * - compare expected horizontal knockback vs. actual moved distance in a short window
 * - use ratio + buffer-like sample requirements to reduce false positives
 */
public final class VelocityListener implements Listener {

    private final AntiCheatLitePlugin plugin;
    private final ViolationManager vl;

    private final boolean alertsEnabled;
    private final String alertFormat;
    private final String bypassPermission;

    private final boolean enabled;
    private final long maxPendingAfterHitMs;
    private final long sampleWindowMs;
    private final long evalDelayMs;
    private final int minSamples;
    private final double minExpectedHorizontal;
    private final double minTakeRatio;
    private final double vlAdd;
    private final boolean cancelOnFlag;

    private final PunishAction punishAction;
    private final double punishThreshold;
    private final boolean setbackOnFlag;

    private final Map<UUID, VelState> states = new ConcurrentHashMap<>();

    private static final DecimalFormat DF2 = new DecimalFormat("0.00");

    public VelocityListener(AntiCheatLitePlugin plugin, ViolationManager vl, FileConfiguration cfg) {
        this.plugin = plugin;
        this.vl = vl;

        this.alertsEnabled = cfg.getBoolean("alerts.enabled", true);
        this.alertFormat = cfg.getString("alerts.format", "&c[AC]&7 {player} &f{check} &7VL={vl} &8({details})");
        this.bypassPermission = cfg.getString("bypass_permission", "anticheatlite.bypass");

        this.enabled = cfg.getBoolean("checks.velocity.enabled", true);
        this.maxPendingAfterHitMs = cfg.getLong("checks.velocity.max_pending_after_hit_ms", 250L);
        this.sampleWindowMs = cfg.getLong("checks.velocity.sample_window_ms", 650L);
        this.evalDelayMs = cfg.getLong("checks.velocity.eval_delay_ms", 220L);
        this.minSamples = cfg.getInt("checks.velocity.min_samples", 3);
        this.minExpectedHorizontal = cfg.getDouble("checks.velocity.min_expected_horizontal", 0.10);
        this.minTakeRatio = cfg.getDouble("checks.velocity.min_take_ratio", 0.20);
        this.vlAdd = cfg.getDouble("checks.velocity.vl_add", 2.0);
        this.cancelOnFlag = cfg.getBoolean("checks.velocity.cancel_on_flag", false);

        this.punishAction = PunishAction.fromString(cfg.getString("punishments.action", "SETBACK"));
        this.punishThreshold = cfg.getDouble("punishments.threshold_vl", 6.0);
        this.setbackOnFlag = cfg.getBoolean("punishments.setback_on_flag", true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        Player p = (Player) e.getEntity();
        if (shouldSkip(p)) return;

        VelState st = states.computeIfAbsent(p.getUniqueId(), k -> new VelState());
        st.hitAt = System.currentTimeMillis();
        st.active = false;
        st.expectedHoriz = 0.0;
        st.movedHoriz = 0.0;
        st.samples = 0;
        st.lastLoc = p.getLocation().clone();
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onVelocity(PlayerVelocityEvent e) {
        if (!enabled) return;

        Player p = e.getPlayer();
        if (p == null || shouldSkip(p)) return;

        VelState st = states.computeIfAbsent(p.getUniqueId(), k -> new VelState());
        long now = System.currentTimeMillis();
        if ((now - st.hitAt) > maxPendingAfterHitMs) return;

        Vector v = e.getVelocity();
        double expected = Math.sqrt(v.getX() * v.getX() + v.getZ() * v.getZ());
        if (expected < minExpectedHorizontal) return;

        st.expectedHoriz = expected;
        st.movedHoriz = 0.0;
        st.samples = 0;
        st.velocityAt = now;
        st.lastLoc = p.getLocation().clone();
        st.active = true;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onMove(PlayerMoveEvent e) {
        if (!enabled) return;
        if (e.getTo() == null) return;

        Player p = e.getPlayer();
        if (p == null || shouldSkip(p)) return;

        VelState st = states.get(p.getUniqueId());
        if (st == null || !st.active) return;

        long now = System.currentTimeMillis();
        if ((now - st.velocityAt) > sampleWindowMs) {
            st.active = false;
            return;
        }

        if (st.lastLoc == null || st.lastLoc.getWorld() == null || e.getTo().getWorld() == null
                || !st.lastLoc.getWorld().equals(e.getTo().getWorld())) {
            st.lastLoc = e.getTo().clone();
            return;
        }

        double dx = e.getTo().getX() - st.lastLoc.getX();
        double dz = e.getTo().getZ() - st.lastLoc.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        st.movedHoriz += horizontal;
        if (horizontal > 0.0001) st.samples++;
        st.lastLoc = e.getTo().clone();

        if ((now - st.velocityAt) < evalDelayMs || st.samples < minSamples) return;

        st.active = false;

        if (isInWeirdBlock(p)) return;

        double ratio = st.expectedHoriz <= 0.0001 ? 1.0 : (st.movedHoriz / st.expectedHoriz);
        if (ratio >= minTakeRatio) return;

        double next = vl.addVl(p.getUniqueId(), CheckType.VELOCITY, vlAdd);
        alert(p, "VELOCITY", next,
                "exp=" + DF2.format(st.expectedHoriz) +
                        ", moved=" + DF2.format(st.movedHoriz) +
                        ", ratio=" + DF2.format(ratio));

        if (cancelOnFlag) {
            e.setTo(e.getFrom());
        }

        if (punishAction == PunishAction.SETBACK && setbackOnFlag) {
            plugin.setback(p);
        } else {
            maybePunish(p);
        }
    }

    private boolean shouldSkip(Player p) {
        if (!enabled) return true;
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

    private boolean isInWeirdBlock(Player p) {
        Location loc = p.getLocation();
        Material feet = loc.getBlock().getType();
        Material head = loc.clone().add(0, 1, 0).getBlock().getType();

        if (feet == Material.WATER || feet == Material.LAVA) return true;
        if (head == Material.WATER || head == Material.LAVA) return true;
        if (feet == Material.LADDER || feet == Material.VINE) return true;
        if (feet == Compat.material("COBWEB", "WEB")) return true;
        if (Compat.isOneOf(feet, "SLIME_BLOCK", "HONEY_BLOCK")) return true;

        return false;
    }

    private void maybePunish(Player p) {
        double total = vl.getTotalVl(p.getUniqueId());
        if (total < punishThreshold) return;
        if (punishAction == PunishAction.KICK) {
            p.kickPlayer(AntiCheatLitePlugin.color("&c[AC] Suspicious knockback behavior detected."));
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

    private static final class VelState {
        private long hitAt = 0L;
        private long velocityAt = 0L;
        private boolean active = false;
        private double expectedHoriz = 0.0;
        private double movedHoriz = 0.0;
        private int samples = 0;
        private Location lastLoc;
    }
}
