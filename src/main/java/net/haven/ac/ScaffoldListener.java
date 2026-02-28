package net.haven.ac;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;

import java.text.DecimalFormat;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Scaffold detection (heuristics):
 * - Looks for rapid bridging placements while looking down
 * - Flags if yaw/pitch is "too stable" (low variance) or very snappy across placements
 */
public final class ScaffoldListener implements Listener {

    private final AntiCheatLitePlugin plugin;
    private final ViolationManager vl;

    private final boolean alertsEnabled;
    private final String alertFormat;
    private final String bypassPermission;

    private final boolean enabled;
    private final double scaffoldPunishDamage;
    private final boolean scaffoldBreakLastBlock;
    private final int windowMs;
    private final int minPlaces;

    private final double minPitch;
    private final double maxYawStd;
    private final double maxPitchStd;
    private final double snapYaw;
    private final double snapPitch;

    private final double maxHoriz;
    private final double maxVert;

    // Prediction-like checks (Grim-inspired lightweight constraints)
    private final boolean predictionEnabled;
    private final double predictionMaxReachBlocks;
    private final double predictionMaxLookAngleDeg;
    private final double predictionBufferMin;
    private final double predictionBufferDecay;

    private final double vlAdd;
    private final boolean cancelOnFlag;

    private final PunishAction punishAction;
    private final boolean setbackOnFlag;

    private final Map<UUID, ScaffoldState> states = new ConcurrentHashMap<>();

    private static final DecimalFormat DF2 = new DecimalFormat("0.00");

    public ScaffoldListener(AntiCheatLitePlugin plugin, ViolationManager vl, FileConfiguration cfg) {
        this.plugin = plugin;
        this.vl = vl;

        this.alertsEnabled = cfg.getBoolean("alerts.enabled", true);
        this.alertFormat = cfg.getString("alerts.format", "&c[AC]&7 {player} &f{check} &7VL={vl} &8({details})");
        this.bypassPermission = cfg.getString("bypass_permission", "anticheatlite.bypass");

        this.enabled = cfg.getBoolean("checks.scaffold.enabled", true);
        this.scaffoldPunishDamage = cfg.contains("checks.scaffold.annoy_damage")
                ? cfg.getDouble("checks.scaffold.annoy_damage", 2.0)
                : cfg.getDouble("checks.scaffold.punish_damage", 2.0);
        this.scaffoldBreakLastBlock = cfg.getBoolean("checks.scaffold.break_last_block", true);
        this.windowMs = cfg.getInt("checks.scaffold.window_ms", 1200);
        this.minPlaces = cfg.getInt("checks.scaffold.min_places", 6);

        this.minPitch = cfg.getDouble("checks.scaffold.min_pitch_deg", 75.0);
        this.maxYawStd = cfg.getDouble("checks.scaffold.max_yaw_stddev_deg", 1.5);
        this.maxPitchStd = cfg.getDouble("checks.scaffold.max_pitch_stddev_deg", 1.2);
        this.snapYaw = cfg.getDouble("checks.scaffold.snap_yaw_deg", 35.0);
        this.snapPitch = cfg.getDouble("checks.scaffold.snap_pitch_deg", 25.0);

        this.maxHoriz = cfg.getDouble("checks.scaffold.max_horizontal_dist_blocks", 1.6);
        this.maxVert = cfg.getDouble("checks.scaffold.max_vertical_offset_blocks", 2.0);

        this.predictionEnabled = cfg.getBoolean("checks.scaffold.prediction.enabled", true);
        this.predictionMaxReachBlocks = cfg.getDouble("checks.scaffold.prediction.max_reach_blocks", 4.8);
        this.predictionMaxLookAngleDeg = cfg.getDouble("checks.scaffold.prediction.max_look_angle_deg", 82.0);
        this.predictionBufferMin = cfg.getDouble("checks.scaffold.prediction.buffer_min", 2.0);
        this.predictionBufferDecay = cfg.getDouble("checks.scaffold.prediction.buffer_decay", 0.20);

        this.vlAdd = cfg.getDouble("checks.scaffold.vl_add", 2.0);
        this.cancelOnFlag = cfg.getBoolean("checks.scaffold.cancel_on_flag", true);

        this.punishAction = PunishAction.fromString(cfg.getString("punishments.action", "SETBACK"));
        this.setbackOnFlag = cfg.getBoolean("punishments.setback_on_flag", true);

        for (Player p : Bukkit.getOnlinePlayers()) {
            states.put(p.getUniqueId(), new ScaffoldState());
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onPlace(BlockPlaceEvent e) {
        if (!enabled) return;

        Player p = e.getPlayer();
        if (p == null) return;
        if (p.hasPermission(bypassPermission)) return;
        if (p.getGameMode().name().equalsIgnoreCase("CREATIVE") || p.getGameMode().name().equalsIgnoreCase("SPECTATOR")) return;

        // Only focus on "bridging" placements near feet / below
        Block placed = e.getBlockPlaced();
        Location pl = p.getLocation();

        double dx = (placed.getX() + 0.5) - pl.getX();
        double dz = (placed.getZ() + 0.5) - pl.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        double vert = pl.getY() - (placed.getY() + 0.0);

        // Typical scaffold: block is at/below feet and close horizontally.
        if (horiz > maxHoriz) return;
        if (vert < 0.0 || vert > maxVert) return;

        float yaw = normalizeYaw(pl.getYaw());
        float pitch = pl.getPitch();
        if (pitch < minPitch) return; // must be looking down

        ScaffoldState st = states.computeIfAbsent(p.getUniqueId(), k -> new ScaffoldState());
        long now = System.currentTimeMillis();

        st.samples.addLast(new RotSample(now, yaw, pitch));
        while (st.samples.size() > 40) st.samples.removeFirst();

        // prune window
        while (!st.samples.isEmpty() && (now - st.samples.peekFirst().t) > windowMs) {
            st.samples.removeFirst();
        }

        if (st.samples.size() < minPlaces) {
            st.lastYaw = yaw;
            st.lastPitch = pitch;
            st.lastAt = now;
            return;
        }

        double yawStd = stdDev(st.samples, true);
        double pitchStd = stdDev(st.samples, false);

        boolean tooStable = yawStd <= maxYawStd && pitchStd <= maxPitchStd;

        boolean snapped = false;
        if (st.lastAt > 0L && (now - st.lastAt) <= 250L) {
            double dyaw = Math.abs(deltaYaw(yaw, st.lastYaw));
            double dpitch = Math.abs(pitch - st.lastPitch);
            if (dyaw >= snapYaw || dpitch >= snapPitch) {
                snapped = true;
            }
        }

        st.lastYaw = yaw;
        st.lastPitch = pitch;
        st.lastAt = now;

        boolean impossiblePlacement = false;
        String impossibleDetails = "";
        if (predictionEnabled) {
            Location eye = p.getEyeLocation();
            org.bukkit.util.Vector look = eye.getDirection().normalize();
            org.bukkit.util.Vector toBlock = placed.getLocation().add(0.5, 0.5, 0.5).toVector().subtract(eye.toVector());
            double reachDist = toBlock.length();
            double lookAngle = 180.0;
            if (reachDist > 0.001) {
                org.bukkit.util.Vector dir = toBlock.clone().normalize();
                double dot = Math.max(-1.0, Math.min(1.0, look.dot(dir)));
                lookAngle = Math.toDegrees(Math.acos(dot));
            }

            boolean badReach = reachDist > predictionMaxReachBlocks;
            boolean badAngle = lookAngle > predictionMaxLookAngleDeg;

            if (badReach || badAngle) {
                st.predictionBuffer = Math.min(6.0, st.predictionBuffer + 1.0);
            } else {
                st.predictionBuffer = Math.max(0.0, st.predictionBuffer - predictionBufferDecay);
            }

            if (st.predictionBuffer >= predictionBufferMin) {
                impossiblePlacement = true;
                impossibleDetails = "pred(reach=" + DF2.format(reachDist) + ",angle=" + DF2.format(lookAngle) + ",buf=" + DF2.format(st.predictionBuffer) + ")";
            }
        }

        if (tooStable || snapped || impossiblePlacement) {
            double next = vl.addVl(p.getUniqueId(), CheckType.SCAFFOLD, vlAdd);
            String details = "places=" + st.samples.size() + ", yawStd=" + DF2.format(yawStd) + ", pitchStd=" + DF2.format(pitchStd);
            if (snapped) details += ", snapped";
            if (impossiblePlacement) details += ", " + impossibleDetails;
            alert(p, "SCAFFOLD", next, details);

            boolean annoyMode = plugin.isAnnoyMode(p);

            // "Annoy" punishment: break the just-placed block (only in annoy mode).
            if (annoyMode && scaffoldBreakLastBlock && !e.isCancelled()) {
                final Block b = e.getBlockPlaced();
                final Material type = b.getType();
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (b.getType() == type) {
                        b.setType(Material.AIR, false);
                    }
                });
            }

            // "Annoy" damage (only in annoy mode).
            if (annoyMode && scaffoldPunishDamage > 0.0) {
                plugin.punishDamage(p, scaffoldPunishDamage, "SCAFFOLD flagged");
            }

            if (cancelOnFlag) {
                e.setCancelled(true);
            }

            if (punishAction == PunishAction.SETBACK && setbackOnFlag) {
                plugin.setback(p);
            }
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

    private static double stdDev(Deque<RotSample> samples, boolean yaw) {
        if (samples.size() < 3) return 9999.0;
        double sum = 0.0;
        int n = 0;
        for (RotSample s : samples) {
            sum += yaw ? s.yaw : s.pitch;
            n++;
        }
        double mean = sum / n;
        double ss = 0.0;
        int m = 0;
        for (RotSample s : samples) {
            double d = (yaw ? s.yaw : s.pitch) - mean;
            ss += d * d;
            m++;
        }
        if (m <= 1) return 9999.0;
        return Math.sqrt(ss / (m - 1));
    }

    private static final class ScaffoldState {
        private final Deque<RotSample> samples = new ArrayDeque<>();
        private long lastAt = 0L;
        private float lastYaw = 0f;
        private float lastPitch = 0f;
        private double predictionBuffer = 0.0;
    }

    private static final class RotSample {
        private final long t;
        private final float yaw;
        private final float pitch;

        private RotSample(long t, float yaw, float pitch) {
            this.t = t;
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }
}
