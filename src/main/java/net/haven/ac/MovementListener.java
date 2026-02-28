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
    private final boolean simSetbackToSimulated;
    private final double simSetbackExtraSlackBps;

    // MovementSimulator params
    private final double simSpeedAttrToBps;
    private final double simSprintMult;
    private final double simSneakMult;
    private final double simAirMult;
    private final double simHeadHitAirMult;
    private final double simSpeedPotionPerLevel;
    private final double simUseItemMult;
    private final double simSpecialEnvLooseMult;
    private final double simBaseSlackBps;

    // Blink (sudden move)
private final boolean blinkEnabled;
private final double blinkMaxDistance;
private final double blinkPunishDamage;
private final double blinkVlAdd;
private final long blinkIgnoreAfterVelocityMs;

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

        // Movement simulation config (prefer checks.movement_sim.*, fallback to legacy movement_sim.*)
        this.simEnabled = getBooleanCompat(cfg, "checks.movement_sim.enabled", "movement_sim.enabled", true);
        this.simIgnoreAfterVelocityMs = getLongCompat(cfg, "checks.movement_sim.ignore_after_velocity_ms", "movement_sim.ignore_after_velocity_ms", 1200L);
        this.simVlAdd = getDoubleCompat(cfg, "checks.movement_sim.vl_add", "movement_sim.violation_add", 1.0);

        this.simSampleWindowMs = getIntCompat(cfg, "checks.movement_sim.sample_window_ms", "movement_sim.sample_window_ms", 550);
        this.simMinSamples = getIntCompat(cfg, "checks.movement_sim.min_samples", "movement_sim.min_samples", 6);
        this.simMinMoveMs = getIntCompat(cfg, "checks.movement_sim.min_move_ms", "movement_sim.min_dt_ms", 20);
        this.simMaxMoveMs = getIntCompat(cfg, "checks.movement_sim.max_move_ms", "movement_sim.max_dt_ms", 220);
        this.simGraceBps = getDoubleCompat(cfg, "checks.movement_sim.grace_bps", "movement_sim.base_slack_bps", 0.75);
        this.simPeakSlackBps = getDoubleCompat(cfg, "checks.movement_sim.peak_slack_bps", "movement_sim.peak_slack_bps", 1.25);
        this.simSetbackToSimulated = getBooleanCompat(cfg, "checks.movement_sim.setback_to_simulated.enabled", "movement_sim.setback_to_simulated.enabled", true);
        this.simSetbackExtraSlackBps = getDoubleCompat(cfg, "checks.movement_sim.setback_to_simulated.extra_slack_bps", "movement_sim.setback_to_simulated.extra_slack_bps", 0.20);

        this.simSpeedAttrToBps = getDoubleCompat(cfg, "checks.movement_sim.speed_attr_to_bps", "movement_sim.speed_attr_to_bps", 43.17);
        this.simSprintMult = getDoubleCompat(cfg, "checks.movement_sim.sprint_mult", "movement_sim.sprint_mult", 1.30);
        this.simSneakMult = getDoubleCompat(cfg, "checks.movement_sim.sneak_mult", "movement_sim.sneak_mult", 0.30);
        this.simAirMult = getDoubleCompat(cfg, "checks.movement_sim.air_mult", "movement_sim.air_mult", 1.08);
        this.simHeadHitAirMult = getDoubleCompat(cfg, "checks.movement_sim.head_hit_air_mult", "movement_sim.head_hit_air_mult", 1.15);
        this.simSpeedPotionPerLevel = getDoubleCompat(cfg, "checks.movement_sim.speed_potion_per_level", "movement_sim.speed_potion_per_level", 0.20);
        this.simUseItemMult = getDoubleCompat(cfg, "checks.movement_sim.use_item_mult", "movement_sim.use_item_mult", 0.35);
        this.simSpecialEnvLooseMult = getDoubleCompat(cfg, "checks.movement_sim.special_env_loose_mult", "movement_sim.special_env_loose_mult", 1.35);
        this.simBaseSlackBps = getDoubleCompat(cfg, "checks.movement_sim.base_slack_bps", "movement_sim.base_slack_bps", 0.75);

        this.blinkEnabled = getBooleanCompat(cfg, "checks.blink.enabled", "blink.enabled", true);
        this.blinkMaxDistance = getDoubleCompat(cfg, "checks.blink.max_teleport_distance", "blink.max_teleport_distance", 20.0);
        this.blinkPunishDamage = getDoubleCompat(cfg, "checks.blink.punish_damage", "blink.punish_damage", 2.0);
        this.blinkVlAdd = getDoubleCompat(cfg, "checks.blink.vl_add", "blink.vl_add", 2.0);
        this.blinkIgnoreAfterVelocityMs = getLongCompat(cfg, "checks.blink.ignore_after_velocity_ms", "blink.ignore_after_velocity_ms", 1200L);

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
        if (Compat.isGliding(p)) return;

        // Sticky setback / freeze: keep fast fly/blink from drifting out of range.
        SetbackManager sm = plugin.getSetbackManager();
        if (sm != null) {
            sm.tickDownFreeze(p);
            if (sm.isFrozen(p)) {
                Location safe = sm.getLastSafe(p);
                Location to = e.getTo();
                if (safe != null && to != null && safe.getWorld() != null && p.getWorld() != null && p.getWorld().equals(safe.getWorld())) {
                    Location lock = safe.clone();
                    lock.setYaw(to.getYaw());
                    lock.setPitch(to.getPitch());
                    e.setTo(lock);
                }
                return;
            }
        }

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


        // BLINK: sudden large move (anti-blink). If moved too far, rollback + punish.
        if (blinkEnabled) {
            boolean inVelocityWindow = (now - st.lastVelocityAt) <= blinkIgnoreAfterVelocityMs;
            if (!inVelocityWindow) {
                double dist = st.lastLoc.distance(to);
                if (dist > blinkMaxDistance) {
                    double next = vl.addVl(p.getUniqueId(), CheckType.FLY, blinkVlAdd);
                    alert(p, "BLINK", next, "dist=" + DF2.format(dist) + ",max=" + DF2.format(blinkMaxDistance));
                    plugin.setback(p);

                    double dmg = blinkPunishDamage;
                    if (dmg > 0.0 && plugin.isAnnoyMode(p)) {
                        plugin.punishDamage(p, dmg, "BLINK flagged");
                    }

                    st.lastLoc = to.clone();
                    st.lastMoveAt = now;
                    return;
                }
            }
        }

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
                    PotionEffect spd = Compat.getPotionEffect(p, PotionEffectType.SPEED);
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
                    sc.useItemMult = simUseItemMult;
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

                            if (simSetbackToSimulated && plugin.canPunish()) {
                                Location simLoc = computeSimulatedLocation(st.lastLoc, to, dtMs, allowed + simSetbackExtraSlackBps);
                                if (simLoc != null) {
                                    simLoc.setYaw(to.getYaw());
                                    simLoc.setPitch(to.getPitch());
                                    e.setTo(simLoc);
                                }
                            }

                            flaggedThisMove = true;
                        }
                    }
                }
            }
        }

        if (flyEnabled) {
            if (isFlyExempt(p)) {
                st.airTicks = 0;
                st.hoverTicks = 0;
                st.flyBuffer = 0.0;
                st.hasVy = false;
            } else if (p.isOnGround()) {
                st.airTicks = 0;
                st.hoverTicks = 0;
                st.flyBuffer = 0.0;
                st.vy = 0.0;
                st.hasVy = true;
            } else {
                st.airTicks++;
                double dy = to.getY() - from.getY();

                // Seed predicted vertical motion from the first air tick (usually jump/step/knock).
                if (!st.hasVy || st.airTicks == 1) {
                    st.vy = dy;
                    st.hasVy = true;
                }

                // Predict using vanilla-like gravity/drag (1.16.x): v = (v - 0.08) * 0.98
                double expectedDy = st.vy;
                double predictedNextVy = (st.vy - 0.08D) * 0.98D;

                boolean recentVelocity = (now - st.lastVelocityAt) < 600L;

                // Hovering: near-zero dy for many ticks while in air (and not in liquids/ladders/web etc.)
                if (Math.abs(dy) < 0.02D) st.hoverTicks++; else st.hoverTicks = 0;

                double diff = Math.abs(dy - expectedDy);
                boolean badHover = st.airTicks >= flyMinAirTicks && st.hoverTicks >= 8;
                boolean badPhysics = st.airTicks >= flyMinAirTicks && !recentVelocity && diff > 0.22D;

                // Keep the old "small dy" heuristic as a weaker signal, but only after some air ticks.
                boolean badSmallDy = st.airTicks >= flyMinAirTicks && Math.abs(dy) <= flyMaxAbsYDelta;

                boolean suspiciousFly = !isInWeirdBlock(p) && (badHover || badPhysics || badSmallDy);
                if (suspiciousFly) {
                    // Grim-like buffering: require sustained bad air movement before VL.
                    st.flyBuffer = Math.min(4.0, st.flyBuffer + 1.0);
                    if (st.flyBuffer >= 2.0) {
                        double next = vl.addVl(p.getUniqueId(), CheckType.FLY, flyVlAdd);
                        alert(p, "FLY", next,
                                "airTicks=" + st.airTicks +
                                        ", dy=" + DF2.format(dy) +
                                        ", exp=" + DF2.format(expectedDy) +
                                        ", diff=" + DF2.format(diff) +
                                        ", buf=" + DF2.format(st.flyBuffer) +
                                        (recentVelocity ? ", vel" : "") +
                                        (badHover ? ", hover" : "") +
                                        (badPhysics ? ", phys" : ""));
                        flaggedThisMove = true;
                    }
                } else {
                    st.flyBuffer = Math.max(0.0, st.flyBuffer - 0.25);
                }

                st.vy = predictedNextVy;
                st.hasVy = true;
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

    private Location computeSimulatedLocation(Location from, Location to, long dtMs, double allowedBps) {
        if (from == null || to == null || from.getWorld() == null || to.getWorld() == null) return null;
        if (!from.getWorld().equals(to.getWorld())) return null;
        if (dtMs <= 0L) return to.clone();

        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);

        double maxHorizontal = Math.max(0.0, allowedBps) * (dtMs / 1000.0);
        if (horizontal <= 0.000001 || horizontal <= maxHorizontal) {
            return to.clone();
        }

        double scale = maxHorizontal / horizontal;
        Location out = from.clone();
        out.setX(from.getX() + dx * scale);
        out.setY(to.getY());
        out.setZ(from.getZ() + dz * scale);
        return out;
    }

    private void maybePunish(Player p) {
        if (!plugin.canPunish()) return;
        double total = vl.getTotalVl(p.getUniqueId());
        if (total < punishThreshold) return;
        if (punishAction == PunishAction.KICK) {
            p.kickPlayer(AntiCheatLitePlugin.color("&c[AC] Suspicious movement detected."));
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

    private boolean isInWeirdBlock(Player p) {
        Location loc = p.getLocation();
        Material feet = loc.getBlock().getType();
        Material head = loc.clone().add(0, 1, 0).getBlock().getType();

        if (feet == Material.WATER || feet == Material.LAVA) return true;
        if (head == Material.WATER || head == Material.LAVA) return true;
        if (feet == Material.LADDER || feet == Material.VINE) return true;
        if (feet == Compat.material("COBWEB","WEB")) return true;
        if (Compat.isOneOf(feet, "SLIME_BLOCK", "HONEY_BLOCK")) return true;

        return false;
    }

    private boolean isFlyExempt(Player p) {
        // Don't flag staff / creative / spectators, or players using legitimate movement mechanics.
        try {
            if (!p.isOnline()) return true;
            if (p.getAllowFlight() || p.isFlying()) return true;
            switch (p.getGameMode()) {
                case CREATIVE:
                case SPECTATOR:
                    return true;
                default:
                    break;
            }
            if (p.isInsideVehicle()) return true;
            if (Compat.isGliding(p)) return true;
            if (Compat.isSwimming(p)) return true;
            if (Compat.hasPotion(p, "LEVITATION")) return true;
            if (Compat.hasPotion(p, "SLOW_FALLING")) return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean hasPath(FileConfiguration cfg, String path) {
        return cfg != null && path != null && cfg.contains(path);
    }

    private static boolean getBooleanCompat(FileConfiguration cfg, String preferred, String legacy, boolean def) {
        if (hasPath(cfg, preferred)) return cfg.getBoolean(preferred);
        if (hasPath(cfg, legacy)) return cfg.getBoolean(legacy);
        return def;
    }

    private static int getIntCompat(FileConfiguration cfg, String preferred, String legacy, int def) {
        if (hasPath(cfg, preferred)) return cfg.getInt(preferred);
        if (hasPath(cfg, legacy)) return cfg.getInt(legacy);
        return def;
    }

    private static long getLongCompat(FileConfiguration cfg, String preferred, String legacy, long def) {
        if (hasPath(cfg, preferred)) return cfg.getLong(preferred);
        if (hasPath(cfg, legacy)) return cfg.getLong(legacy);
        return def;
    }

    private static double getDoubleCompat(FileConfiguration cfg, String preferred, String legacy, double def) {
        if (hasPath(cfg, preferred)) return cfg.getDouble(preferred);
        if (hasPath(cfg, legacy)) return cfg.getDouble(legacy);
        return def;
    }

    private static final class MoveState {
        private Location lastLoc;
        private long lastMoveAt;
        private long lastVelocityAt;
        private int airTicks;
        private int hoverTicks;
        private double flyBuffer;

        private double vy;
        private boolean hasVy;

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
            this.hoverTicks = 0;
            this.flyBuffer = 0.0;
            this.vy = 0.0;
            this.hasVy = false;
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
