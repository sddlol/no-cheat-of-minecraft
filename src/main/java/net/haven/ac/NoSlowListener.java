package net.haven.ac;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerMoveEvent;

import java.text.DecimalFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Grim-like lightweight NoSlow:
 * - predict allowed horizontal speed from simplified vanilla movement model
 * - derive expected speed while using item (no-item prediction * item-use multiplier)
 * - use offset buffer instead of single-tick threshold
 */
public final class NoSlowListener implements Listener {

    private final AntiCheatLitePlugin plugin;
    private final ViolationManager vl;

    private final boolean alertsEnabled;
    private final String alertFormat;
    private final String bypassPermission;

    private final boolean enabled;
    private final boolean detectSword;
    private final boolean detectFood;
    private final boolean detectPotion;

    private final int sampleWindowMs;
    private final int minSamples;
    private final int minMoveMs;
    private final int maxMoveMs;

    private final double swordUseMult;
    private final double foodUseMult;
    private final double potionUseMult;
    private final double baseOffsetBps;
    private final double peakOffsetBps;
    private final double vlAdd;

    private final double swordBufferMin;
    private final double foodBufferMin;
    private final double potionBufferMin;

    private final MovementSimulator.Config simCfg = new MovementSimulator.Config();

    private final Map<UUID, State> states = new ConcurrentHashMap<>();

    private static final DecimalFormat DF2 = new DecimalFormat("0.00");

    public NoSlowListener(AntiCheatLitePlugin plugin, ViolationManager vl, FileConfiguration cfg) {
        this.plugin = plugin;
        this.vl = vl;

        this.alertsEnabled = cfg.getBoolean("alerts.enabled", true);
        this.alertFormat = cfg.getString("alerts.format", "&c[AC]&7 {player} &f{check} &7VL={vl} &8({details})");
        this.bypassPermission = cfg.getString("bypass_permission", "anticheatlite.bypass");

        this.enabled = cfg.getBoolean("checks.noslow.enabled", true);
        this.detectSword = cfg.getBoolean("checks.noslow.detect_sword", true);
        this.detectFood = cfg.getBoolean("checks.noslow.detect_food", true);
        this.detectPotion = cfg.getBoolean("checks.noslow.detect_potion", true);

        this.sampleWindowMs = cfg.getInt("checks.noslow.sample_window_ms", 900);
        this.minSamples = cfg.getInt("checks.noslow.min_samples", 4);
        this.minMoveMs = cfg.getInt("checks.noslow.min_move_ms", 25);
        this.maxMoveMs = cfg.getInt("checks.noslow.max_move_ms", 220);

        this.swordUseMult = cfg.getDouble("checks.noslow.sword_use_mult", 0.35);
        this.foodUseMult = cfg.getDouble("checks.noslow.food_use_mult", 0.20);
        this.potionUseMult = cfg.getDouble("checks.noslow.potion_use_mult", 0.20);
        this.baseOffsetBps = cfg.getDouble("checks.noslow.base_offset_bps", 0.20);
        this.peakOffsetBps = cfg.getDouble("checks.noslow.peak_offset_bps", 0.38);
        this.vlAdd = cfg.getDouble("checks.noslow.vl_add", 1.4);

        this.swordBufferMin = cfg.getDouble("checks.noslow.sword_buffer_min", 2.0);
        this.foodBufferMin = cfg.getDouble("checks.noslow.food_buffer_min", 1.5);
        this.potionBufferMin = cfg.getDouble("checks.noslow.potion_buffer_min", 1.5);

        // Keep simulation consistent with movement_sim tuneables.
        simCfg.speedAttrToBps = cfg.getDouble("movement_sim.speed_attr_to_bps", 43.17);
        simCfg.sprintMult = cfg.getDouble("movement_sim.sprint_mult", 1.30);
        simCfg.sneakMult = cfg.getDouble("movement_sim.sneak_mult", 0.30);
        simCfg.airMult = cfg.getDouble("movement_sim.air_mult", 1.08);
        simCfg.headHitAirMult = cfg.getDouble("movement_sim.head_hit_air_mult", 1.15);
        simCfg.speedPotionPerLevel = cfg.getDouble("movement_sim.speed_potion_per_level", 0.20);
        simCfg.specialEnvLooseMult = cfg.getDouble("movement_sim.special_env_loose_mult", 1.35);
        simCfg.baseSlackBps = cfg.getDouble("movement_sim.base_slack_bps", 0.75);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onConsume(PlayerItemConsumeEvent e) {
        State st = states.computeIfAbsent(e.getPlayer().getUniqueId(), k -> new State(e.getPlayer().getLocation().clone(), System.currentTimeMillis()));
        st.consumeGraceUntil = System.currentTimeMillis() + 300L;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onMove(PlayerMoveEvent e) {
        if (!enabled) return;

        Player p = e.getPlayer();
        if (p == null || !p.isOnline()) return;
        if (p.hasPermission(bypassPermission)) return;

        GameMode gm = p.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) return;
        if (p.isInsideVehicle()) return;
        if (p.getAllowFlight() || p.isFlying()) return;
        if (Compat.isGliding(p) || Compat.isSwimming(p) || Compat.isRiptiding(p)) return;
        if (e.getTo() == null) return;
        if (e.getFrom().getWorld() != e.getTo().getWorld()) return;

        long now = System.currentTimeMillis();
        State st = states.computeIfAbsent(p.getUniqueId(), k -> new State(e.getFrom().clone(), now));

        long dt = Math.max(1L, now - st.lastMoveAt);
        if (dt < minMoveMs || dt > maxMoveMs) {
            st.lastLoc = e.getTo().clone();
            st.lastMoveAt = now;
            return;
        }

        double dx = e.getTo().getX() - st.lastLoc.getX();
        double dz = e.getTo().getZ() - st.lastLoc.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double bps = horizontal / (dt / 1000.0);

        st.addSample(now, bps, sampleWindowMs);

        ItemGroup group = currentItemGroup(p);
        boolean itemUsing = group != ItemGroup.NONE;
        boolean inConsumeGrace = now <= st.consumeGraceUntil;

        if (itemUsing && !inConsumeGrace && st.samplesSize() >= minSamples) {
            // Predict "normal" allowed movement first, then apply item-use slowdown multiplier.
            double normalAllowed = MovementSimulator.allowedHorizontalBpsNoItemSlow(p, p.isOnGround(), p.isSprinting(), p.isSneaking(), simCfg);
            double expectedUseAllowed = normalAllowed * itemUseMult(group);

            double avg = st.avg();
            double peak = st.peak();
            double avgOffset = avg - expectedUseAllowed;
            double peakOffset = peak - expectedUseAllowed;

            if (avgOffset > baseOffsetBps || peakOffset > peakOffsetBps) {
                st.addBuffer(group, 1.0);
            } else {
                st.decayBuffer(group, 0.2);
            }

            if (st.getBuffer(group) >= itemBufferMin(group)) {
                double next = vl.addVl(p.getUniqueId(), CheckType.NOSLOW, vlAdd);
                alert(p, "NOSLOW", next,
                        "avg=" + DF2.format(avg) +
                                ", peak=" + DF2.format(peak) +
                                ", expect=" + DF2.format(expectedUseAllowed) +
                                ", offA=" + DF2.format(avgOffset) +
                                ", offP=" + DF2.format(peakOffset) +
                                ", item=" + group.name().toLowerCase() +
                                ", buf=" + DF2.format(st.getBuffer(group)));
            }
        } else {
            st.decayAllBuffers(0.1);
        }

        st.lastLoc = e.getTo().clone();
        st.lastMoveAt = now;
    }

    private ItemGroup currentItemGroup(Player p) {
        Material hand = p.getItemInHand() != null ? p.getItemInHand().getType() : null;
        if (hand == null) return ItemGroup.NONE;

        boolean usingState = false;
        try { if (p.isBlocking()) usingState = true; } catch (Throwable ignored) {}
        if (!usingState) {
            try {
                java.lang.reflect.Method m = p.getClass().getMethod("isHandRaised");
                Object o = m.invoke(p);
                usingState = (o instanceof Boolean) && ((Boolean) o);
            } catch (Throwable ignored) {}
        }

        if (!usingState) return ItemGroup.NONE;

        String n = hand.name();
        if (detectSword && n.endsWith("_SWORD")) return ItemGroup.SWORD;
        if (detectPotion && (n.equals("POTION") || n.equals("SPLASH_POTION") || n.equals("LINGERING_POTION"))) return ItemGroup.POTION;
        if (detectFood && isFoodLike(hand)) return ItemGroup.FOOD;
        return ItemGroup.NONE;
    }

    private double itemUseMult(ItemGroup group) {
        switch (group) {
            case SWORD: return swordUseMult;
            case FOOD: return foodUseMult;
            case POTION: return potionUseMult;
            default: return 1.0;
        }
    }

    private double itemBufferMin(ItemGroup group) {
        switch (group) {
            case SWORD: return swordBufferMin;
            case FOOD: return foodBufferMin;
            case POTION: return potionBufferMin;
            default: return Double.MAX_VALUE;
        }
    }

    private boolean isFoodLike(Material m) {
        String n = m.name();
        return n.contains("APPLE") || n.equals("BREAD") || n.equals("COOKED_BEEF") || n.equals("COOKED_CHICKEN")
                || n.equals("COOKED_MUTTON") || n.equals("COOKED_RABBIT") || n.equals("COOKED_PORKCHOP")
                || n.equals("CARROT") || n.equals("POTATO") || n.equals("BAKED_POTATO") || n.equals("BEETROOT")
                || n.equals("BEETROOT_SOUP") || n.equals("MUSHROOM_STEW") || n.equals("RABBIT_STEW")
                || n.equals("MELON") || n.equals("COOKIE") || n.equals("PUMPKIN_PIE") || n.equals("GOLDEN_APPLE")
                || n.equals("ENCHANTED_GOLDEN_APPLE") || n.equals("CHORUS_FRUIT") || n.equals("DRIED_KELP")
                || n.equals("HONEY_BOTTLE") || n.equals("SUSPICIOUS_STEW") || n.equals("SWEET_BERRIES")
                || n.equals("GLOW_BERRIES");
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
        private org.bukkit.Location lastLoc;
        private long lastMoveAt;
        private long consumeGraceUntil;
        private double swordBuffer;
        private double foodBuffer;
        private double potionBuffer;
        private final java.util.ArrayDeque<double[]> samples = new java.util.ArrayDeque<>();

        private State(org.bukkit.Location l, long t) {
            this.lastLoc = l;
            this.lastMoveAt = t;
            this.consumeGraceUntil = 0L;
        }

        private void addSample(long now, double bps, int windowMs) {
            samples.addLast(new double[]{now, bps});
            while (!samples.isEmpty() && (now - (long) samples.peekFirst()[0]) > windowMs) samples.removeFirst();
        }

        private int samplesSize() { return samples.size(); }

        private double avg() {
            if (samples.isEmpty()) return 0.0;
            double s = 0.0;
            for (double[] d : samples) s += d[1];
            return s / samples.size();
        }

        private double peak() {
            double p = 0.0;
            for (double[] d : samples) if (d[1] > p) p = d[1];
            return p;
        }

        private void addBuffer(ItemGroup g, double add) {
            if (g == ItemGroup.SWORD) swordBuffer = Math.min(6.0, swordBuffer + add);
            if (g == ItemGroup.FOOD) foodBuffer = Math.min(6.0, foodBuffer + add);
            if (g == ItemGroup.POTION) potionBuffer = Math.min(6.0, potionBuffer + add);
        }

        private void decayBuffer(ItemGroup g, double d) {
            if (g == ItemGroup.SWORD) swordBuffer = Math.max(0.0, swordBuffer - d);
            if (g == ItemGroup.FOOD) foodBuffer = Math.max(0.0, foodBuffer - d);
            if (g == ItemGroup.POTION) potionBuffer = Math.max(0.0, potionBuffer - d);
        }

        private void decayAllBuffers(double d) {
            swordBuffer = Math.max(0.0, swordBuffer - d);
            foodBuffer = Math.max(0.0, foodBuffer - d);
            potionBuffer = Math.max(0.0, potionBuffer - d);
        }

        private double getBuffer(ItemGroup g) {
            if (g == ItemGroup.SWORD) return swordBuffer;
            if (g == ItemGroup.FOOD) return foodBuffer;
            if (g == ItemGroup.POTION) return potionBuffer;
            return 0.0;
        }
    }

    private enum ItemGroup {
        NONE,
        SWORD,
        FOOD,
        POTION
    }
}
