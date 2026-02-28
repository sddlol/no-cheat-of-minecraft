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

    private final double swordMaxUseItemBps;
    private final double foodMaxUseItemBps;
    private final double potionMaxUseItemBps;
    private final double graceBps;
    private final double vlAdd;

    private final double swordBufferMin;
    private final double foodBufferMin;
    private final double potionBufferMin;

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

        double fallbackMax = cfg.getDouble("checks.noslow.max_use_item_bps", 2.25);
        this.swordMaxUseItemBps = cfg.getDouble("checks.noslow.sword_max_use_item_bps", fallbackMax);
        this.foodMaxUseItemBps = cfg.getDouble("checks.noslow.food_max_use_item_bps", fallbackMax);
        this.potionMaxUseItemBps = cfg.getDouble("checks.noslow.potion_max_use_item_bps", fallbackMax);
        this.graceBps = cfg.getDouble("checks.noslow.grace_bps", 0.25);
        this.vlAdd = cfg.getDouble("checks.noslow.vl_add", 1.4);

        this.swordBufferMin = cfg.getDouble("checks.noslow.sword_buffer_min", 2.0);
        this.foodBufferMin = cfg.getDouble("checks.noslow.food_buffer_min", 1.5);
        this.potionBufferMin = cfg.getDouble("checks.noslow.potion_buffer_min", 1.5);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onConsume(PlayerItemConsumeEvent e) {
        // small cooldown window around consume animation
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
            double avg = st.avg();
            double peak = st.peak();
            double limit = itemLimit(group) + graceBps;

            if (avg > limit || peak > limit + 0.35) {
                st.addBuffer(group, 1.0);
            } else {
                st.decayBuffer(group, 0.2);
            }

            if (st.getBuffer(group) >= itemBufferMin(group)) {
                double next = vl.addVl(p.getUniqueId(), CheckType.NOSLOW, vlAdd);
                alert(p, "NOSLOW", next,
                        "avg=" + DF2.format(avg) +
                                ", peak=" + DF2.format(peak) +
                                ", limit=" + DF2.format(limit) +
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

    private double itemLimit(ItemGroup group) {
        switch (group) {
            case SWORD: return swordMaxUseItemBps;
            case FOOD: return foodMaxUseItemBps;
            case POTION: return potionMaxUseItemBps;
            default: return Double.MAX_VALUE;
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
        // cross-version conservative list
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
            this.swordBuffer = 0.0;
            this.foodBuffer = 0.0;
            this.potionBuffer = 0.0;
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
