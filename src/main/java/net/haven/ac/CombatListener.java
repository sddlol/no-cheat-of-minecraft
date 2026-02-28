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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
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
    private final double reachSoloWeight;
    private final double reachComboWeight;

    // KillAura heuristics
    private final boolean auraEnabled;
    private final double auraMaxAngleDeg;
    private final long auraSwitchWindowMs;
    private final double auraVlAdd;
    private final boolean auraCancelOnFlag;
    private final double auraSoloWeight;
    private final double auraComboWeight;
    private final boolean auraCheckLineOfSight;
    private final boolean auraNullifyDamageOnBlocked;
    private final double auraAnnoyDamage;

    // Rotation smoothness (aim-assist style)
    private final boolean auraSmoothEnabled;
    private final int auraSmoothWindowMs;
    private final int auraSmoothMinSamples;
    private final double auraSmoothMaxYawStdDeg;
    private final double auraSmoothMaxPitchStdDeg;
    private final double auraSmoothMinAvgYawDeg;
    private final double auraSmoothMinAvgPitchDeg;
    private final double auraSmoothVlAdd;

    // Rotation step quantization (GCD-like)
    private final boolean auraGcdEnabled;
    private final int auraGcdMinSamples;
    private final double auraGcdMinStepDeg;
    private final double auraGcdMaxNormRemainder;
    private final double auraGcdVlAdd;

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
        this.reachSoloWeight = cfg.getDouble("checks.reach.solo_weight", 0.90);
        this.reachComboWeight = cfg.getDouble("checks.reach.combo_weight", 1.20);

        this.auraEnabled = cfg.getBoolean("checks.killaura.enabled", true);
        this.auraMaxAngleDeg = cfg.getDouble("checks.killaura.max_angle_deg", 65.0);
        this.auraSwitchWindowMs = cfg.getLong("checks.killaura.switch_window_ms", 250L);
        this.auraVlAdd = cfg.getDouble("checks.killaura.vl_add", 1.5);
        this.auraCancelOnFlag = cfg.getBoolean("checks.killaura.cancel_on_flag", false);
        this.auraSoloWeight = cfg.getDouble("checks.killaura.solo_weight", 0.90);
        this.auraComboWeight = cfg.getDouble("checks.killaura.combo_weight", 1.20);
        this.auraCheckLineOfSight = cfg.getBoolean("checks.killaura.check_line_of_sight", true);
        this.auraNullifyDamageOnBlocked = cfg.getBoolean("checks.killaura.nullify_damage_on_blocked", true);
        this.auraAnnoyDamage = cfg.getDouble("checks.killaura.annoy_damage", AntiCheatLitePlugin.DEFAULT_PUNISH_DAMAGE);

        this.auraSmoothEnabled = cfg.getBoolean("checks.killaura.smooth_rotation.enabled", true);
        this.auraSmoothWindowMs = cfg.getInt("checks.killaura.smooth_rotation.window_ms", 1400);
        this.auraSmoothMinSamples = cfg.getInt("checks.killaura.smooth_rotation.min_samples", 5);
        this.auraSmoothMaxYawStdDeg = cfg.getDouble("checks.killaura.smooth_rotation.max_yaw_stddev_deg", 0.90);
        this.auraSmoothMaxPitchStdDeg = cfg.getDouble("checks.killaura.smooth_rotation.max_pitch_stddev_deg", 0.70);
        this.auraSmoothMinAvgYawDeg = cfg.getDouble("checks.killaura.smooth_rotation.min_avg_yaw_delta_deg", 1.80);
        this.auraSmoothMinAvgPitchDeg = cfg.getDouble("checks.killaura.smooth_rotation.min_avg_pitch_delta_deg", 0.25);
        this.auraSmoothVlAdd = cfg.getDouble("checks.killaura.smooth_rotation.vl_add", 1.0);

        this.auraGcdEnabled = cfg.getBoolean("checks.killaura.smooth_rotation.gcd.enabled", true);
        this.auraGcdMinSamples = cfg.getInt("checks.killaura.smooth_rotation.gcd.min_samples", 7);
        this.auraGcdMinStepDeg = cfg.getDouble("checks.killaura.smooth_rotation.gcd.min_step_deg", 0.06);
        this.auraGcdMaxNormRemainder = cfg.getDouble("checks.killaura.smooth_rotation.gcd.max_norm_remainder", 0.12);
        this.auraGcdVlAdd = cfg.getDouble("checks.killaura.smooth_rotation.gcd.vl_add", 0.8);

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
        boolean reachFlag = false;
        boolean auraFlag = false;
        String reachDetail = "";
        String auraDetail = "";
        double auraBaseAdd = auraVlAdd;

        ReachUtil.ReachResult rr = null;

        // REACH pre-analysis
        if (reachEnabled) {
            rr = ReachUtil.computeReach(p, (LivingEntity) target);
            double reach = rr.distance;

            int ping = PingUtil.getPingMs(p);
            double pingExtra = Math.min(reachPingCompCap, ping * reachPingCompPerMs);
            double allowed = reachBase + pingExtra;

            if (reach > allowed) {
                reachFlag = true;
                reachDetail = "reach=" + DF2.format(reach) + ">" + DF2.format(allowed) +
                        ", ping=" + ping + "ms" +
                        (rr.rayIntersects ? ", ray" : ", fallback") +
                        (rr.blocked ? ", blocked" : "");
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

            boolean suspiciousSmooth = false;
            boolean suspiciousGcd = false;
            double yawStd = 999.0;
            double pitchStd = 999.0;
            double yawAvg = 0.0;
            double pitchAvg = 0.0;
            double gcdYawStep = 0.0;
            double gcdPitchStep = 0.0;
            double gcdYawRem = 1.0;
            double gcdPitchRem = 1.0;
            if (auraSmoothEnabled) {
                float yawNow = normalizeYaw(eye.getYaw());
                float pitchNow = eye.getPitch();
                if (st.hasLastRot && (now - st.lastHitAt) <= auraSmoothWindowMs) {
                    double dyaw = Math.abs(deltaYaw(yawNow, st.lastYaw));
                    double dpitch = Math.abs(pitchNow - st.lastPitch);
                    st.rotDeltas.addLast(new RotDelta(now, dyaw, dpitch));
                    while (!st.rotDeltas.isEmpty() && (now - st.rotDeltas.peekFirst().t) > auraSmoothWindowMs) {
                        st.rotDeltas.removeFirst();
                    }

                    if (st.rotDeltas.size() >= auraSmoothMinSamples) {
                        yawStd = stdDev(st.rotDeltas, true);
                        pitchStd = stdDev(st.rotDeltas, false);
                        yawAvg = avg(st.rotDeltas, true);
                        pitchAvg = avg(st.rotDeltas, false);

                        boolean veryStable = yawStd <= auraSmoothMaxYawStdDeg && pitchStd <= auraSmoothMaxPitchStdDeg;
                        boolean enoughMovement = yawAvg >= auraSmoothMinAvgYawDeg || pitchAvg >= auraSmoothMinAvgPitchDeg;
                        suspiciousSmooth = veryStable && enoughMovement;
                    }

                    if (auraGcdEnabled && st.rotDeltas.size() >= auraGcdMinSamples && suspiciousSmooth) {
                        gcdYawStep = estimateStep(st.rotDeltas, true, auraGcdMinStepDeg);
                        gcdPitchStep = estimateStep(st.rotDeltas, false, auraGcdMinStepDeg);
                        if (gcdYawStep >= auraGcdMinStepDeg) {
                            gcdYawRem = normalizedRemainder(st.rotDeltas, true, gcdYawStep);
                        }
                        if (gcdPitchStep >= auraGcdMinStepDeg) {
                            gcdPitchRem = normalizedRemainder(st.rotDeltas, false, gcdPitchStep);
                        }

                        boolean yawQuant = gcdYawStep >= auraGcdMinStepDeg && gcdYawRem <= auraGcdMaxNormRemainder;
                        boolean pitchQuant = gcdPitchStep >= auraGcdMinStepDeg && gcdPitchRem <= auraGcdMaxNormRemainder;
                        suspiciousGcd = yawQuant || pitchQuant;
                    }
                }
                st.lastYaw = yawNow;
                st.lastPitch = pitchNow;
                st.hasLastRot = true;
            }

            if (suspiciousAngle || suspiciousSwitch || suspiciousLos || suspiciousSmooth || suspiciousGcd) {
                auraFlag = true;
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
                if (suspiciousSmooth) {
                    if (details.length() > 0) details.append(", ");
                    details.append("smooth(yStd=").append(DF2.format(yawStd))
                            .append(",pStd=").append(DF2.format(pitchStd))
                            .append(",yAvg=").append(DF2.format(yawAvg))
                            .append(",pAvg=").append(DF2.format(pitchAvg)).append(")");
                    if (!suspiciousAngle && !suspiciousSwitch && !suspiciousLos) {
                        auraBaseAdd = auraSmoothVlAdd;
                    }
                }
                if (suspiciousGcd) {
                    if (details.length() > 0) details.append(", ");
                    details.append("gcd(yStep=").append(DF2.format(gcdYawStep))
                            .append(",pStep=").append(DF2.format(gcdPitchStep))
                            .append(",yRem=").append(DF2.format(gcdYawRem))
                            .append(",pRem=").append(DF2.format(gcdPitchRem)).append(")");
                    auraBaseAdd += auraGcdVlAdd;
                }

                auraDetail = details.toString();
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

        if (reachFlag) {
            boolean combo = auraFlag;
            double add = reachVlAdd * (combo ? reachComboWeight : reachSoloWeight);
            double next = vl.addVl(p.getUniqueId(), CheckType.REACH, add);
            alert(p, "REACH", next, reachDetail + (combo ? ", combo" : ", solo"));
            flagged = true;
            if (reachCancelOnFlag) e.setCancelled(true);
        }

        if (auraFlag) {
            boolean combo = reachFlag;
            double add = auraBaseAdd * (combo ? auraComboWeight : auraSoloWeight);
            double next = vl.addVl(p.getUniqueId(), CheckType.KILLAURA, add);
            alert(p, "KILLAURA", next, auraDetail + (combo ? ", combo" : ", solo"));
            flagged = true;
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
            online.sendMessage(msg);
        }
        plugin.recordLastFlag(suspected, check, details);
        plugin.getLogger().info("[AC] " + suspected.getName() + " " + check + " VL=" + DF2.format(checkVl) + " (" + details + ")");
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
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

    private static double avg(Deque<RotDelta> samples, boolean yaw) {
        if (samples.isEmpty()) return 0.0;
        double sum = 0.0;
        int n = 0;
        for (RotDelta s : samples) {
            sum += yaw ? s.yawDelta : s.pitchDelta;
            n++;
        }
        return n <= 0 ? 0.0 : (sum / n);
    }

    private static double stdDev(Deque<RotDelta> samples, boolean yaw) {
        if (samples.size() < 2) return 9999.0;
        double mean = avg(samples, yaw);
        double ss = 0.0;
        int n = 0;
        for (RotDelta s : samples) {
            double d = (yaw ? s.yawDelta : s.pitchDelta) - mean;
            ss += d * d;
            n++;
        }
        if (n <= 1) return 9999.0;
        return Math.sqrt(ss / (n - 1));
    }

    private static double estimateStep(Deque<RotDelta> samples, boolean yaw, double minStep) {
        List<Double> vals = new ArrayList<Double>();
        for (RotDelta s : samples) {
            double v = yaw ? s.yawDelta : s.pitchDelta;
            if (v >= minStep) vals.add(v);
        }
        if (vals.size() < 3) return 0.0;
        Collections.sort(vals);
        int idx = (int) Math.floor((vals.size() - 1) * 0.2);
        return vals.get(Math.max(0, idx));
    }

    private static double normalizedRemainder(Deque<RotDelta> samples, boolean yaw, double step) {
        if (step <= 0.0) return 1.0;
        double sum = 0.0;
        int n = 0;
        for (RotDelta s : samples) {
            double v = yaw ? s.yawDelta : s.pitchDelta;
            if (v < step) continue;
            double mul = Math.round(v / step);
            if (mul <= 0.0) continue;
            double rem = Math.abs(v - (mul * step));
            sum += Math.min(1.0, rem / step);
            n++;
        }
        if (n <= 0) return 1.0;
        return sum / n;
    }

    private static final class AuraState {
        private long lastHitAt = 0L;
        private UUID lastTargetId = null;
        private boolean hasLastRot = false;
        private float lastYaw = 0f;
        private float lastPitch = 0f;
        private final Deque<RotDelta> rotDeltas = new ArrayDeque<>();
    }

    private static final class RotDelta {
        private final long t;
        private final double yawDelta;
        private final double pitchDelta;

        private RotDelta(long t, double yawDelta, double pitchDelta) {
            this.t = t;
            this.yawDelta = yawDelta;
            this.pitchDelta = pitchDelta;
        }
    }
}
