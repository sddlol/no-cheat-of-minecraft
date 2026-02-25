package net.haven.ac;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.text.DecimalFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class MovementListener implements Listener {

    private final AntiCheatLitePlugin plugin;
    private final ViolationManager vl;
    private final FileConfiguration cfg;

    private final boolean alertsEnabled;
    private final String alertFormat;
    private final String bypassPermission;

    // Existing speed check (kept)
    private final boolean speedEnabled;
    private final long speedIgnoreAfterVelocityMs;
    private final double speedVlAdd;

    private final double speedBaseWalkBps;
    private final double speedSprintMultiplier;
    private final double speedAirBonusBps;
    private final double speedPotionMultiplierPerLevel;
    private final int speedSampleWindowMs;
    private final int speedMinSamples;
    private final int speedMinMoveMs;
    private final int speedMaxMoveMs;
    private final double speedGraceBps;
    private final double speedPeakSlackBps;

    // New: movement simulation (stable / PVP-friendly)
    private final boolean simEnabled;
    private final long simIgnoreAfterVelocityMs;
    private final double simVlAdd;

    private final int simSampleWindowMs;
    private final int simMinSamples;
    private final int simMinMoveMs;
    private final int simMaxMoveMs;
    private final double simGraceBps;
    private final double simPeakSlackBps;

    // MovementSimulator params
    private final double simSpeedAttrToBps;
    private final double simSprintMult;
    private final double simSneakMult;
    private final double simAirMult;
    private final double simHeadHitAirMult;
    private final double simSpeedPotionPerLevel;
    private final double simSpecialEnvLooseMult;
    private final double simBaseSlackBps;

    private final boolean flyEnabled;
    private final int flyMinAirTicks;
    private final double flyMaxAbsYDelta;
    private final double flyVlAdd;

    private final PunishAction punishAction;
    private final double punishThreshold;
    private final boolean setbackOnFlag;

    private final Map<UUID, MoveState> states = new ConcurrentHashMap<>();

    private static final DecimalFormat DF2 = new DecimalFormat("0.00");

    public MovementListener(AntiCheatLitePlugin plugin, ViolationManager vl, FileConfiguration cfg) {
        this.plugin = plugin;
        this.vl = vl;
        this.cfg = cfg;

        this.alertsEnabled = cfg.getBoolean("alerts.enabled", true);
        this.alertFormat = cfg.getString("alerts.format", "&c[AC]&7 {player} &f{check} &7VL={vl} &8({details})");
        this.bypassPermission = cfg.getString("bypass_permission", "anticheatlite.bypass");

        this.speedEnabled = cfg.getBoolean("checks.speed.enabled", true);
        this.speedIgnoreAfterVelocityMs = cfg.getLong("checks.speed.ignore_after_velocity_ms", 1200L);
        this.speedVlAdd = cfg.getDouble("checks.speed.vl_add", 1.0);

        this.speedBaseWalkBps = cfg.getDouble("checks.speed.base_walk_bps", 4.32);
        this.speedSprintMultiplier = cfg.getDouble("checks.speed.sprint_multiplier", 1.30);
        this.speedAirBonusBps = cfg.getDouble("checks.speed.air_bonus_bps", 2.4);
        this.speedPotionMultiplierPerLevel = cfg.getDouble("checks.speed.speed_potion_multiplier_per_level", 0.20);
        this.speedSampleWindowMs = cfg.getInt("checks.speed.sample_window_ms", 450);
        this.speedMinSamples = cfg.getInt("checks.speed.min_samples", 3);
        this.speedMinMoveMs = cfg.getInt("checks.speed.min_move_ms", 35);
        this.speedMaxMoveMs = cfg.getInt("checks.speed.max_move_ms", 220);
        this.speedGraceBps = cfg.getDouble("checks.speed.grace_bps", 0.45);
        this.speedPeakSlackBps = cfg.getDouble("checks.speed.peak_slack_bps", 1.2);

        // Movement simulation config
        this.simEnabled = cfg.getBoolean("checks.movement_sim.enabled", true);
        this.simIgnoreAfterVelocityMs = cfg.getLong("checks.movement_sim.ignore_after_velocity_ms", 1200L);
        this.simVlAdd = cfg.getDouble("checks.movement_sim.vl_add", 1.0);

        this.simSampleWindowMs = cfg.getInt("checks.movement_sim.sample_window_ms", 550);
        this.simMinSamples = cfg.getInt("checks.movement_sim.min_samples", 6);
        this.simMinMoveMs = cfg.getInt("checks.movement_sim.min_move_ms", 20);
        this.simMaxMoveMs = cfg.getInt("checks.movement_sim.max_move_ms", 220);
        this.simGraceBps = cfg.getDouble("checks.movement_sim.grace_bps", 0.75);
        this.simPeakSlackBps = cfg.getDouble("checks.movement_sim.peak_slack_bps", 1.25);

        this.simSpeedAttrToBps = cfg.getDouble("checks.movement_sim.speed_attr_to_bps", 43.17);
        this.simSprintMult = cfg.getDouble("checks.movement_sim.sprint_mult", 1.30);
        this.simSneakMult = cfg.getDouble("checks.movement_sim.sneak_mult", 0.30);
        this.simAirMult = cfg.getDouble("checks.movement_sim.air_mult", 1.08);
        this.simHeadHitAirMult = cfg.getDouble("checks.movement_sim.head_hit_air_mult", 1.15);
        this.simSpeedPotionPerLevel = cfg.getDouble("checks.movement_sim.speed_potion_per_level", 0.20);
        this.simSpecialEnvLooseMult = cfg.getDouble("checks.movement_sim.special_env_loose_mult", 1.35);
        this.simBaseSlackBps = cfg.getDouble("checks.movement_sim.base_slack_bps", 0.75);

        this.flyEnabled = cfg.getBoolean("checks.fly.enabled", true);
        this.flyMinAirTicks = cfg.getInt("checks.fly.min_air_ticks", 14);
        this.flyMaxAbsYDelta = cfg.getDouble("checks.fly.max_abs_y_delta_per_tick", 0.02);
        this.flyVlAdd = cfg.getDouble("checks.fly.vl_add", 1.5);

        this.punishAction = PunishAction.fromString(cfg.getString("punishments.action", "SETBACK"));
        this.punishThreshold = cfg.getDouble("punishments.threshold_vl", 6.0);
        this.setbackOnFlag = cfg.getBoolean("punishments.setback_on_flag", true);

        for (Player p : Bukkit.getOnlinePlayers()) {
            states.put(p.getUniqueId(), new MoveState(p.getLocation(), System.currentTimeMillis()));
            plugin.updateLastSafe(p, p.getLocation());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        states.put(p.getUniqueId(), new MoveState(p.getLocation(), System.currentTimeMillis()));
        plugin.updateLastSafe(p, p.getLocation());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        states.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler(ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent e) {
        MoveState st = states.computeIfAbsent(e.getPlayer().getUniqueId(),
                k -> new MoveState(e.getPlayer().getLocation(), System.currentTimeMillis()));
        st.lastVelocityAt = System.currentTimeMillis();
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        Player p = (Player) e.getEntity();
        MoveState st = states.computeIfAbsent(p.getUniqueId(), k -> new MoveState(p.getLocation(), System.currentTimeMillis()));
        st.lastVelocityAt = System.currentTimeMillis();
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (p.hasPermission(bypassPermission)) return;
        if (p.getGameMode().name().equalsIgnoreCase("CREATIVE") || p.getGameMode().name().equalsIgnoreCase("SPECTATOR")) return;
        if (p.isInsideVehicle()) return;
        if (p.isFlying() && p.getAllowFlight()) return;
        if (p.isGliding()) return;

        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null) return;
        if (from.getWorld() == null || to.getWorld() == null) return;
        if (!from.getWorld().equals(to.getWorld())) {
            states.put(p.getUniqueId(), new MoveState(to, System.currentTimeMillis()));
            plugin.updateLastSafe(p, to);
            return;
        }

        MoveState st = states.computeIfAbsent(p.getUniqueId(), k -> new MoveState(from, System.currentTimeMillis()));
        long now = System.currentTimeMillis();

        st.lastYaw = to.getYaw();
        st.lastPitch = to.getPitch();
        st.lastRotAt = now;

        if (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ()) {
            return;
        }

        long dtMs = Math.max(1L, now - st.lastMoveAt);

        if (p.isOnGround() && !isInWeirdBlock(p)) {
            plugin.updateLastSafe(p, to);
        }

        boolean flaggedThisMove = false;

        if (speedEnabled) {
            boolean inVelocityWindow = (now - st.lastVelocityAt) <= speedIgnoreAfterVelocityMs;
            if (!inVelocityWindow) {
                if (dtMs < speedMinMoveMs || dtMs > speedMaxMoveMs) {
                    st.lastLoc = to.clone();
                    st.lastMoveAt = now;
                    return;
                }

                double dx = to.getX() - st.lastLoc.getX();
                double dz = to.getZ() - st.lastLoc.getZ();
                double horizontal = Math.sqrt(dx * dx + dz * dz);
                double bps = horizontal / (dtMs / 1000.0);

                st.addSpeedSample(now, bps, speedSampleWindowMs);
                if (st.speedSamplesSize() >= speedMinSamples) {
                    double avg = st.speedAvg();
                    double peak = st.speedPeak();

                    double max = speedBaseWalkBps;
                    if (p.isSprinting()) max *= speedSprintMultiplier;
                    if (!p.isOnGround()) max += speedAirBonusBps;

                    int level = 0;
                    PotionEffect spd = p.getPotionEffect(PotionEffectType.SPEED);
                    if (spd != null) level = spd.getAmplifier() + 1;
                    max *= (1.0 + (level * speedPotionMultiplierPerLevel));

                    if (!isInWeirdBlock(p) && (avg > max + speedGraceBps || peak > max + speedGraceBps + speedPeakSlackBps)) {
                        double next = vl.addVl(p.getUniqueId(), CheckType.SPEED, speedVlAdd);
                        alert(p, "SPEED", next,
                                "avg=" + DF2.format(avg) + ",peak=" + DF2.format(peak) + ",max=" + DF2.format(max) + ",lv=" + level);
                        flaggedThisMove = true;
                    }
                }
            }
        }

        if (simEnabled) {
            boolean inVelocityWindow = (now - st.lastVelocityAt) <= simIgnoreAfterVelocityMs;
            if (!inVelocityWindow) {
                if (dtMs >= simMinMoveMs && dtMs <= simMaxMoveMs) {
                    double dx = to.getX() - st.lastLoc.getX();
                    double dz = to.getZ() - st.lastLoc.getZ();
                    double horizontal = Math.sqrt(dx * dx + dz * dz);
                    double bps = horizontal / (dtMs / 1000.0);

                    MovementSimulator.Config sc = new MovementSimulator.Config();
                    sc.speedAttrToBps = simSpeedAttrToBps;
                    sc.sprintMult = simSprintMult;
                    sc.sneakMult = simSneakMult;
                    sc.airMult = simAirMult;
                    sc.headHitAirMult = simHeadHitAirMult;
                    sc.speedPotionPerLevel = simSpeedPotionPerLevel;
                    sc.specialEnvLooseMult = simSpecialEnvLooseMult;
                    sc.baseSlackBps = simBaseSlackBps;
                    sc.peakSlackBps = simPeakSlackBps;
                    sc.sampleWindowMs = simSampleWindowMs;
                    sc.minSamples = simMinSamples;

                    double allowed = MovementSimulator.allowedHorizontalBps(
                            p, p.isOnGround(), p.isSprinting(), p.isSneaking(), sc
                    );

                    st.addSimSample(now, bps, simSampleWindowMs);
                    if (st.simSamplesSize() >= simMinSamples) {
                        double avg = st.simAvg();
                        double peak = st.simPeak();

                        if (!isInWeirdBlock(p) && (avg > allowed + simGraceBps || peak > allowed + simGraceBps + simPeakSlackBps)) {
                            double next = vl.addVl(p.getUniqueId(), CheckType.MOVEMENT_SIM, simVlAdd);
                            alert(p, "MOVE_SIM", next,
                                    "avg=" + DF2.format(avg) + ",peak=" + DF2.format(peak) + ",allow=" + DF2.format(allowed));
                            flaggedThisMove = true;
                        }
                    }
                }
            }
        }

        if (flyEnabled) {
            boolean onGround = p.isOnGround();
            if (onGround) {
                st.airTicks = 0;
            } else {
                st.airTicks++;
                if (st.airTicks >= flyMinAirTicks) {
                    double dy = to.getY() - from.getY();
                    if (Math.abs(dy) <= flyMaxAbsYDelta && !isInWeirdBlock(p)) {
                        double next = vl.addVl(p.getUniqueId(), CheckType.FLY, flyVlAdd);
                        alert(p, "FLY", next, "airTicks=" + st.airTicks + ", dy=" + DF2.format(dy));
                        flaggedThisMove = true;
                    }
                }
            }
        }

        if (flaggedThisMove && punishAction == PunishAction.SETBACK && setbackOnFlag) {
            plugin.setback(p);
        } else {
            maybePunish(p);
        }

        st.lastLoc = to.clone();
        st.lastMoveAt = now;
    }

    private void maybePunish(Player p) {
        double total = vl.getTotalVl(p.getUniqueId());
        if (total < punishThreshold) return;
        if (punishAction == PunishAction.KICK) {
            p.kickPlayer(AntiCheatLitePlugin.color("&c[AC] Suspicious movement detected."));
        }
    }

    private void alert(Player suspected, String check, double checkVl, String details) {
        if (!alertsEnabled) return;
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

    private boolean isInWeirdBlock(Player p) {
        Location loc = p.getLocation();
        Material feet = loc.getBlock().getType();
        Material head = loc.clone().add(0, 1, 0).getBlock().getType();

        if (feet == Material.WATER || feet == Material.LAVA) return true;
        if (head == Material.WATER || head == Material.LAVA) return true;
        if (feet == Material.LADDER || feet == Material.VINE) return true;
        if (feet == Material.COBWEB) return true;
        if (feet == Material.SLIME_BLOCK) return true;
        if (feet == Material.HONEY_BLOCK) return true;

        return false;
    }

    private static final class MoveState {
        private Location lastLoc;
        private long lastMoveAt;
        private long lastVelocityAt;
        private int airTicks;

        private float lastYaw;
        private float lastPitch;
        private long lastRotAt;

        private final java.util.ArrayDeque<double[]> speedSamples = new java.util.ArrayDeque<>();
        private final java.util.ArrayDeque<double[]> simSamples = new java.util.ArrayDeque<>();

        private MoveState(Location lastLoc, long lastMoveAt) {
            this.lastLoc = lastLoc.clone();
            this.lastMoveAt = lastMoveAt;
            this.lastVelocityAt = 0L;
            this.airTicks = 0;
            this.lastYaw = lastLoc.getYaw();
            this.lastPitch = lastLoc.getPitch();
            this.lastRotAt = lastMoveAt;
        }

        private void addSpeedSample(long now, double bps, int windowMs) {
            speedSamples.addLast(new double[]{now, bps});
            while (!speedSamples.isEmpty() && (now - (long) speedSamples.peekFirst()[0]) > windowMs) {
                speedSamples.removeFirst();
            }
        }

        private int speedSamplesSize() {
            return speedSamples.size();
        }

        private double speedAvg() {
            if (speedSamples.isEmpty()) return 0.0;
            double sum = 0.0;
            for (double[] s : speedSamples) sum += s[1];
            return sum / speedSamples.size();
        }

        private double speedPeak() {
            double peak = 0.0;
            for (double[] s : speedSamples) if (s[1] > peak) peak = s[1];
            return peak;
        }

        private void addSimSample(long now, double bps, int windowMs) {
            simSamples.addLast(new double[]{now, bps});
            while (!simSamples.isEmpty() && (now - (long) simSamples.peekFirst()[0]) > windowMs) {
                simSamples.removeFirst();
            }
        }

        private int simSamplesSize() {
            return simSamples.size();
        }

        private double simAvg() {
            if (simSamples.isEmpty()) return 0.0;
            double sum = 0.0;
            for (double[] s : simSamples) sum += s[1];
            return sum / simSamples.size();
        }

        private double simPeak() {
            double peak = 0.0;
            for (double[] s : simSamples) if (s[1] > peak) peak = s[1];
            return peak;
        }
    }
}
