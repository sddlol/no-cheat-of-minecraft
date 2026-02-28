package net.haven.ac;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.event.inventory.InventoryType;

import java.text.DecimalFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Inventory move check (lightweight).
 *
 * Vanilla players generally cannot move while a non-player container GUI is open.
 */
public final class InvMoveListener implements Listener {

    private final AntiCheatLitePlugin plugin;
    private final ViolationManager vl;

    private final boolean alertsEnabled;
    private final String alertFormat;
    private final String bypassPermission;

    private final boolean enabled;
    private final long ignoreAfterVelocityMs;
    private final long minMoveDtMs;
    private final long maxMoveDtMs;
    private final double minMoveDist;
    private final double bufferMin;
    private final double bufferDecay;
    private final double vlAdd;
    private final boolean cancelOnFlag;

    private final PunishAction punishAction;
    private final double punishThreshold;
    private final boolean setbackOnFlag;

    private final Map<UUID, State> states = new ConcurrentHashMap<UUID, State>();

    private static final DecimalFormat DF2 = new DecimalFormat("0.00");

    public InvMoveListener(AntiCheatLitePlugin plugin, ViolationManager vl, FileConfiguration cfg) {
        this.plugin = plugin;
        this.vl = vl;

        this.alertsEnabled = cfg.getBoolean("alerts.enabled", true);
        this.alertFormat = cfg.getString("alerts.format", "&c[AC]&7 {player} &f{check} &7VL={vl} &8({details})");
        this.bypassPermission = cfg.getString("bypass_permission", "anticheatlite.bypass");

        this.enabled = cfg.getBoolean("checks.invmove.enabled", true);
        this.ignoreAfterVelocityMs = cfg.getLong("checks.invmove.ignore_after_velocity_ms", 1200L);
        this.minMoveDtMs = cfg.getLong("checks.invmove.min_move_dt_ms", 25L);
        this.maxMoveDtMs = cfg.getLong("checks.invmove.max_move_dt_ms", 250L);
        this.minMoveDist = cfg.getDouble("checks.invmove.min_move_dist", 0.05);
        this.bufferMin = cfg.getDouble("checks.invmove.buffer_min", 2.0);
        this.bufferDecay = cfg.getDouble("checks.invmove.buffer_decay", 0.20);
        this.vlAdd = cfg.getDouble("checks.invmove.vl_add", 1.6);
        this.cancelOnFlag = cfg.getBoolean("checks.invmove.cancel_on_flag", true);

        this.punishAction = PunishAction.fromString(cfg.getString("punishments.action", "SETBACK"));
        this.punishThreshold = cfg.getDouble("punishments.threshold_vl", 6.0);
        this.setbackOnFlag = cfg.getBoolean("punishments.setback_on_flag", true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onVelocity(PlayerVelocityEvent e) {
        State st = states.computeIfAbsent(e.getPlayer().getUniqueId(), k -> new State());
        st.lastVelocityAt = System.currentTimeMillis();
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        Player p = (Player) e.getEntity();
        State st = states.computeIfAbsent(p.getUniqueId(), k -> new State());
        st.lastVelocityAt = System.currentTimeMillis();
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onMove(PlayerMoveEvent e) {
        if (!enabled) return;
        if (e.getTo() == null) return;

        Player p = e.getPlayer();
        if (p == null || shouldSkip(p)) return;

        Location from = e.getFrom();
        Location to = e.getTo();
        if (from.getWorld() == null || to.getWorld() == null || !from.getWorld().equals(to.getWorld())) return;

        State st = states.computeIfAbsent(p.getUniqueId(), k -> new State());
        long now = System.currentTimeMillis();
        long dt = st.lastMoveAt <= 0L ? 50L : Math.max(1L, now - st.lastMoveAt);
        st.lastMoveAt = now;

        if ((now - st.lastVelocityAt) <= ignoreAfterVelocityMs) return;
        if (dt < minMoveDtMs || dt > maxMoveDtMs) return;

        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        if (horiz < minMoveDist) return;

        if (!isContainerOpen(p)) {
            st.buffer = Math.max(0.0, st.buffer - bufferDecay);
            return;
        }

        st.buffer = Math.min(6.0, st.buffer + 1.0);
        if (st.buffer < bufferMin) return;

        st.buffer = Math.max(0.0, st.buffer - 0.5);

        double next = vl.addVl(p.getUniqueId(), CheckType.INVMOVE, vlAdd);
        alert(p, "INVMOVE", next,
                "dist=" + DF2.format(horiz) +
                        ", dt=" + dt + "ms" +
                        ", buf=" + DF2.format(st.buffer) +
                        ", inv=" + p.getOpenInventory().getTopInventory().getType().name());

        if (cancelOnFlag && plugin.canPunish()) {
            e.setTo(from);
        }

        if (punishAction == PunishAction.SETBACK && setbackOnFlag) {
            plugin.setback(p);
        } else {
            maybePunish(p);
        }
    }

    private boolean isContainerOpen(Player p) {
        if (p == null || p.getOpenInventory() == null || p.getOpenInventory().getTopInventory() == null) return false;
        InventoryType type = p.getOpenInventory().getTopInventory().getType();
        return type != InventoryType.CRAFTING && type != InventoryType.PLAYER && type != InventoryType.CREATIVE;
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
            p.kickPlayer(AntiCheatLitePlugin.color("&c[AC] Suspicious inventory movement detected."));
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
        private long lastVelocityAt = 0L;
        private long lastMoveAt = 0L;
        private double buffer = 0.0;
    }
}
