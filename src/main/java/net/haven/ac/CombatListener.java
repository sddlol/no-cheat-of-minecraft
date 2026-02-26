package net.haven.ac;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.util.Vector;

import java.text.DecimalFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static net.haven.ac.AntiCheatLitePlugin.color;

public final class CombatListener implements Listener {

    private final AntiCheatLitePlugin plugin;
    private final ViolationManager vl;

    private final boolean alertsEnabled;
    private final String alertFormat;
    private final String bypassPermission;

    // Reach
    private final boolean reachEnabled;
    private final double reachBase;
    private final double reachPingCompPerMs;
    private final double reachPingCompCap;
    private final double reachVlAdd;
    private final boolean reachCancelOnFlag;

    // KillAura heuristics
    private final boolean auraEnabled;
    private final double auraMaxAngleDeg;
    private final long auraSwitchWindowMs;
    private final double auraVlAdd;
    private final boolean auraCancelOnFlag;
    private final boolean auraCheckLineOfSight;
    private final boolean auraNullifyDamageOnBlocked;
    private final double auraAnnoyDamage;

    private final PunishAction punishAction;
    private final double punishThreshold;
    private final boolean setbackOnFlag;

    private final Map<UUID, AuraState> auraStates = new ConcurrentHashMap<>();

    private static final DecimalFormat DF2 = new DecimalFormat("0.00");

    public CombatListener(AntiCheatLitePlugin plugin, ViolationManager vl, FileConfiguration cfg) {
        this.plugin = plugin;
        this.vl = vl;

        this.alertsEnabled = cfg.getBoolean("alerts.enabled", true);
        this.alertFormat = cfg.getString("alerts.format", "&c[AC]&7 {player} &f{check} &7VL={vl} &8({details})");
        this.bypassPermission = cfg.getString("bypass_permission", "anticheatlite.bypass");

        this.reachEnabled = cfg.getBoolean("checks.reach.enabled", true);
        this.reachBase = cfg.getDouble("checks.reach.base_blocks", 3.20);
        this.reachPingCompPerMs = cfg.getDouble("checks.reach.ping_comp_blocks_per_ms", 0.0015);
        this.reachPingCompCap = cfg.getDouble("checks.reach.ping_comp_cap_blocks", 0.35);
        this.reachVlAdd = cfg.getDouble("checks.reach.vl_add", 2.0);
        this.reachCancelOnFlag = cfg.getBoolean("checks.reach.cancel_on_flag", false);

        this.auraEnabled = cfg.getBoolean("checks.killaura.enabled", true);
        this.auraMaxAngleDeg = cfg.getDouble("checks.killaura.max_angle_deg", 65.0);
        this.auraSwitchWindowMs = cfg.getLong("checks.killaura.switch_window_ms", 250L);
        this.auraVlAdd = cfg.getDouble("checks.killaura.vl_add", 1.5);
        this.auraCancelOnFlag = cfg.getBoolean("checks.killaura.cancel_on_flag", false);
        this.auraCheckLineOfSight = cfg.getBoolean("checks.killaura.check_line_of_sight", true);
        this.auraNullifyDamageOnBlocked = cfg.getBoolean("checks.killaura.nullify_damage_on_blocked", true);
        this.auraAnnoyDamage = cfg.getDouble("checks.killaura.annoy_damage", AntiCheatLitePlugin.DEFAULT_PUNISH_DAMAGE);

        this.punishAction = PunishAction.fromString(cfg.getString("punishments.action", "SETBACK"));
        this.punishThreshold = cfg.getDouble("punishments.threshold_vl", 6.0);
        this.setbackOnFlag = cfg.getBoolean("punishments.setback_on_flag", true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player)) return;
        if (!(e.getEntity() instanceof LivingEntity)) return;

        Player p = (Player) e.getDamager();
        if (p.hasPermission(bypassPermission)) return;
        if (p.getGameMode().name().equalsIgnoreCase("CREATIVE") || p.getGameMode().name().equalsIgnoreCase("SPECTATOR")) return;

        Entity target = e.getEntity();

        boolean flagged = false;

        // REACH
        if (reachEnabled) {
            ReachUtil.ReachResult rr = ReachUtil.computeReach(p, (LivingEntity) target);
            double reach = rr.distance;

            int ping = PingUtil.getPingMs(p);
            double pingExtra = Math.min(reachPingCompCap, ping * reachPingCompPerMs);
            double allowed = reachBase + pingExtra;

            if (reach > allowed) {
                double next = vl.addVl(p.getUniqueId(), CheckType.REACH, reachVlAdd);
                alert(p, "REACH", next,
                        "reach=" + DF2.format(reach) + ">" + DF2.format(allowed) +
                                ", ping=" + ping + "ms" +
                                (rr.rayIntersects ? ", ray" : ", fallback") +
                                (rr.blocked ? ", blocked" : ""));
                flagged = true;
                if (reachCancelOnFlag) e.setCancelled(true);
            }

            // If the hit is literally blocked by blocks, treat it as suspicious for aura too.
            if (rr.blocked) flagged = true;
        }

        // KILLAURA heuristics
        if (auraEnabled) {
            Location eye = p.getEyeLocation();

            Vector look = eye.getDirection().normalize();
            Vector to = target.getLocation().add(0, ((LivingEntity) target).getEyeHeight() * 0.5, 0).toVector().subtract(eye.toVector()).normalize();
            double dot = clamp(look.dot(to), -1.0, 1.0);
            double angle = Math.toDegrees(Math.acos(dot));

            AuraState st = auraStates.computeIfAbsent(p.getUniqueId(), k -> new AuraState());
            long now = System.currentTimeMillis();

            boolean suspiciousAngle = angle > auraMaxAngleDeg;
            boolean suspiciousSwitch = (st.lastTargetId != null && !st.lastTargetId.equals(target.getUniqueId()) && (now - st.lastHitAt) <= auraSwitchWindowMs);

            boolean suspiciousLos = false;
            if (auraCheckLineOfSight) {
                double dist = eye.distance(target.getLocation());
                suspiciousLos = ReachUtil.isThroughWall(p, target, dist);
            }

            if (suspiciousAngle || suspiciousSwitch || suspiciousLos) {
                double next = vl.addVl(p.getUniqueId(), CheckType.KILLAURA, auraVlAdd);
                StringBuilder details = new StringBuilder();
                if (suspiciousAngle) {
                    details.append("angle=").append(DF2.format(angle)).append(">")
                            .append(DF2.format(auraMaxAngleDeg));
                }
                if (suspiciousSwitch) {
                    if (details.length() > 0) details.append(", ");
                    details.append("switch<").append(auraSwitchWindowMs).append("ms");
                }
                if (suspiciousLos) {
                    if (details.length() > 0) details.append(", ");
                    details.append("blocked");
                }
                alert(p, "KILLAURA", next, details.toString());
                flagged = true;
                if (auraCancelOnFlag) e.setCancelled(true);

                // Grim-style annoyance: if the hit is blocked by blocks, set damage to 0.
                if (auraNullifyDamageOnBlocked && suspiciousLos && !e.isCancelled()) {
                    e.setDamage(0.0);
                }

                // Annoy damage to attacker.
                if (auraAnnoyDamage > 0.0) {
                    plugin.punishDamage(p, auraAnnoyDamage, "KILLAURA flagged" + (suspiciousLos ? " (blocked)" : ""));
                }
            }

            st.lastHitAt = now;
            st.lastTargetId = target.getUniqueId();
        }

        // Immediate setback-on-flag (your request)
        if (flagged && punishAction == PunishAction.SETBACK && setbackOnFlag) {
            plugin.setback(p);
        } else {
            maybePunish(p);
        }
    }

    private void maybePunish(Player p) {
        double total = vl.getTotalVl(p.getUniqueId());
        if (total < punishThreshold) return;
        if (punishAction == PunishAction.KICK) {
            p.kickPlayer(color("&c[AC] Suspicious combat detected."));
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
            if (online.hasPermission("anticheatlite.alert")) {
                online.sendMessage(msg);
            }
        }
        plugin.getLogger().info("[AC] " + suspected.getName() + " " + check + " VL=" + DF2.format(checkVl) + " (" + details + ")");
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private static final class AuraState {
        private long lastHitAt = 0L;
        private UUID lastTargetId = null;
    }
}
