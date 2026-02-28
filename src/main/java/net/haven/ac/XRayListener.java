package net.haven.ac;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

import java.text.DecimalFormat;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * XRay heuristics (lightweight, no packets):
 * 1) Hidden ore ratio in a mining window.
 * 2) Hidden diamond burst in a short window.
 * 3) Path anomaly: hidden ores found too far apart too quickly.
 */
public final class XRayListener implements Listener {

    private final AntiCheatLitePlugin plugin;
    private final ViolationManager vl;

    private final boolean alertsEnabled;
    private final String alertFormat;
    private final String bypassPermission;

    private final boolean enabled;
    private final int maxY;
    private final int windowMs;
    private final int minUndergroundBreaks;
    private final int minHiddenOres;
    private final double maxHiddenOreRatio;
    private final int diamondWindowMs;
    private final int maxDiamondPerWindow;
    private final double vlAdd;

    // Optional per-environment overrides (World.Environment.NORMAL/NETHER/THE_END)
    private final int normalMaxY;
    private final int netherMaxY;
    private final int endMaxY;
    private final double normalMaxHiddenOreRatio;
    private final double netherMaxHiddenOreRatio;
    private final double endMaxHiddenOreRatio;
    private final int normalMaxDiamondPerWindow;
    private final int netherMaxDiamondPerWindow;
    private final int endMaxDiamondPerWindow;

    // Path anomaly detector
    private final boolean pathEnabled;
    private final int pathWindowMs;
    private final int pathMinHiddenOres;
    private final double pathMinAvgStepDistance;
    private final double pathVlAdd;

    private final boolean ignoreSilkTouch;
    private final boolean relaxOnFortune;
    private final Set<String> exemptWorlds;

    private final Map<UUID, XRayState> states = new ConcurrentHashMap<>();

    private static final DecimalFormat DF2 = new DecimalFormat("0.00");

    public XRayListener(AntiCheatLitePlugin plugin, ViolationManager vl, FileConfiguration cfg) {
        this.plugin = plugin;
        this.vl = vl;

        this.alertsEnabled = cfg.getBoolean("alerts.enabled", true);
        this.alertFormat = cfg.getString("alerts.format", "&c[AC]&7 {player} &f{check} &7VL={vl} &8({details})");
        this.bypassPermission = cfg.getString("bypass_permission", "anticheatlite.bypass");

        this.enabled = cfg.getBoolean("checks.xray.enabled", true);
        this.maxY = cfg.getInt("checks.xray.max_y", 48);
        this.windowMs = cfg.getInt("checks.xray.window_ms", 300000);
        this.minUndergroundBreaks = cfg.getInt("checks.xray.min_underground_breaks", 45);
        this.minHiddenOres = cfg.getInt("checks.xray.min_hidden_ores", 12);
        this.maxHiddenOreRatio = cfg.getDouble("checks.xray.max_hidden_ore_ratio", 0.28);
        this.diamondWindowMs = cfg.getInt("checks.xray.diamond_window_ms", 60000);
        this.maxDiamondPerWindow = cfg.getInt("checks.xray.max_diamond_ore_per_window", 5);
        this.vlAdd = cfg.getDouble("checks.xray.vl_add", 2.5);

        this.normalMaxY = cfg.getInt("checks.xray.worlds.normal.max_y", maxY);
        this.netherMaxY = cfg.getInt("checks.xray.worlds.nether.max_y", maxY);
        this.endMaxY = cfg.getInt("checks.xray.worlds.end.max_y", maxY);

        this.normalMaxHiddenOreRatio = cfg.getDouble("checks.xray.worlds.normal.max_hidden_ore_ratio", maxHiddenOreRatio);
        this.netherMaxHiddenOreRatio = cfg.getDouble("checks.xray.worlds.nether.max_hidden_ore_ratio", maxHiddenOreRatio * 1.40);
        this.endMaxHiddenOreRatio = cfg.getDouble("checks.xray.worlds.end.max_hidden_ore_ratio", maxHiddenOreRatio);

        this.normalMaxDiamondPerWindow = cfg.getInt("checks.xray.worlds.normal.max_diamond_ore_per_window", maxDiamondPerWindow);
        this.netherMaxDiamondPerWindow = cfg.getInt("checks.xray.worlds.nether.max_diamond_ore_per_window", maxDiamondPerWindow + 2);
        this.endMaxDiamondPerWindow = cfg.getInt("checks.xray.worlds.end.max_diamond_ore_per_window", maxDiamondPerWindow);

        this.pathEnabled = cfg.getBoolean("checks.xray.path.enabled", true);
        this.pathWindowMs = cfg.getInt("checks.xray.path.window_ms", 120000);
        this.pathMinHiddenOres = cfg.getInt("checks.xray.path.min_hidden_ores", 6);
        this.pathMinAvgStepDistance = cfg.getDouble("checks.xray.path.min_avg_step_distance", 5.5);
        this.pathVlAdd = cfg.getDouble("checks.xray.path.vl_add", 1.5);

        this.ignoreSilkTouch = cfg.getBoolean("checks.xray.ignore_silk_touch", true);
        this.relaxOnFortune = cfg.getBoolean("checks.xray.relax_on_fortune", true);
        this.exemptWorlds = new HashSet<String>();
        List<String> worlds = cfg.getStringList("checks.xray.exempt_worlds");
        if (worlds != null) {
            for (String w : worlds) {
                if (w != null && !w.trim().isEmpty()) exemptWorlds.add(w.trim().toLowerCase());
            }
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBreak(BlockBreakEvent e) {
        if (!enabled) return;

        Player p = e.getPlayer();
        if (p == null) return;
        if (p.hasPermission(bypassPermission)) return;
        if (p.getGameMode().name().equalsIgnoreCase("CREATIVE") || p.getGameMode().name().equalsIgnoreCase("SPECTATOR")) return;

        Block b = e.getBlock();
        Material type = b.getType();
        long now = System.currentTimeMillis();

        if (b.getWorld() != null && exemptWorlds.contains(b.getWorld().getName().toLowerCase())) return;
        if (ignoreSilkTouch && isSilkTouchTool(p)) return;

        XRayState st = states.computeIfAbsent(p.getUniqueId(), k -> new XRayState());

        int envMaxY = getMaxYByWorld(b.getWorld());
        double envRatioLimit = getMaxHiddenOreRatioByWorld(b.getWorld());
        int envDiamondLimit = getMaxDiamondPerWindowByWorld(b.getWorld());

        int fortuneLevel = getFortuneLevel(p);
        if (relaxOnFortune && fortuneLevel > 0) {
            envRatioLimit += 0.03 * Math.min(3, fortuneLevel);
            envDiamondLimit += Math.min(2, fortuneLevel);
        }

        boolean underground = b.getY() <= envMaxY;
        if (underground && !isAirLike(type)) {
            st.undergroundBreakTimes.addLast(now);
            prune(st.undergroundBreakTimes, now, windowMs);
        }

        if (!isOre(type)) return;

        boolean hidden = isHiddenOre(b);
        if (!hidden) return;

        st.hiddenOreTimes.addLast(now);
        prune(st.hiddenOreTimes, now, windowMs);

        if (pathEnabled) {
            st.hiddenOrePath.addLast(new OrePoint(now, b.getX(), b.getY(), b.getZ()));
            prunePath(st.hiddenOrePath, now, pathWindowMs);
        }

        if (isDiamondOre(type)) {
            st.hiddenDiamondTimes.addLast(now);
            prune(st.hiddenDiamondTimes, now, diamondWindowMs);
        }

        int mineN = st.undergroundBreakTimes.size();
        int oreN = st.hiddenOreTimes.size();
        int diaN = st.hiddenDiamondTimes.size();
        double ratio = mineN <= 0 ? 0.0 : (oreN * 1.0 / mineN);

        boolean suspiciousRatio = mineN >= minUndergroundBreaks && oreN >= minHiddenOres && ratio > envRatioLimit;
        boolean suspiciousDiamondBurst = diaN > envDiamondLimit;
        boolean suspiciousPath = pathEnabled && isSuspiciousPathByConfig(st.hiddenOrePath);

        if (suspiciousRatio || suspiciousDiamondBurst || suspiciousPath) {
            double add = suspiciousPath && !suspiciousRatio && !suspiciousDiamondBurst ? pathVlAdd : vlAdd;
            double next = vl.addVl(p.getUniqueId(), CheckType.XRAY, add);

            String details = "ore=" + oreN + "/" + mineN +
                    ", ratio=" + DF2.format(ratio) +
                    ", dia1m=" + diaN;
            if (suspiciousRatio) details += ", ratioFlag";
            if (suspiciousDiamondBurst) details += ", diaBurst";
            if (suspiciousPath) {
                details += ", path";
                details += "(avgStep=" + DF2.format(avgStepDistance(st.hiddenOrePath)) + ")";
            }
            alert(p, "XRAY", next, details);
        }
    }

    private int getMaxYByWorld(World world) {
        if (world == null) return maxY;
        World.Environment env = world.getEnvironment();
        if (env == World.Environment.NETHER) return netherMaxY;
        if (env == World.Environment.THE_END) return endMaxY;
        return normalMaxY;
    }

    private double getMaxHiddenOreRatioByWorld(World world) {
        if (world == null) return maxHiddenOreRatio;
        World.Environment env = world.getEnvironment();
        if (env == World.Environment.NETHER) return netherMaxHiddenOreRatio;
        if (env == World.Environment.THE_END) return endMaxHiddenOreRatio;
        return normalMaxHiddenOreRatio;
    }

    private int getMaxDiamondPerWindowByWorld(World world) {
        if (world == null) return maxDiamondPerWindow;
        World.Environment env = world.getEnvironment();
        if (env == World.Environment.NETHER) return netherMaxDiamondPerWindow;
        if (env == World.Environment.THE_END) return endMaxDiamondPerWindow;
        return normalMaxDiamondPerWindow;
    }

    private boolean isSuspiciousPathByConfig(Deque<OrePoint> path) {
        if (path.size() < pathMinHiddenOres) return false;
        return avgStepDistance(path) >= pathMinAvgStepDistance;
    }

    private void prune(Deque<Long> q, long now, int winMs) {
        while (!q.isEmpty() && (now - q.peekFirst()) > winMs) q.removeFirst();
    }

    private void prunePath(Deque<OrePoint> q, long now, int winMs) {
        while (!q.isEmpty() && (now - q.peekFirst().t) > winMs) q.removeFirst();
    }

    private static double avgStepDistance(Deque<OrePoint> path) {
        if (path.size() < 2) return 0.0;
        OrePoint prev = null;
        double sum = 0.0;
        int n = 0;
        for (OrePoint cur : path) {
            if (prev != null) {
                double dx = cur.x - prev.x;
                double dy = cur.y - prev.y;
                double dz = cur.z - prev.z;
                sum += Math.sqrt(dx * dx + dy * dy + dz * dz);
                n++;
            }
            prev = cur;
        }
        return n <= 0 ? 0.0 : (sum / n);
    }

    private int getFortuneLevel(Player p) {
        try {
            if (p.getItemInHand() == null) return 0;
            return p.getItemInHand().getEnchantmentLevel(org.bukkit.enchantments.Enchantment.LOOT_BONUS_BLOCKS);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private boolean isSilkTouchTool(Player p) {
        try {
            if (p.getItemInHand() == null) return false;
            return p.getItemInHand().getEnchantmentLevel(org.bukkit.enchantments.Enchantment.SILK_TOUCH) > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isAirLike(Material m) {
        if (m == null) return true;
        String n = m.name();
        return n.equals("AIR") || n.equals("CAVE_AIR") || n.equals("VOID_AIR");
    }

    private static boolean isLiquid(Material m) {
        if (m == null) return false;
        String n = m.name();
        return n.equals("WATER") || n.equals("LAVA");
    }

    private static boolean isOre(Material m) {
        if (m == null) return false;
        String n = m.name();
        return n.endsWith("_ORE")
                || n.equals("ANCIENT_DEBRIS")
                || n.equals("NETHER_GOLD_ORE")
                || n.equals("NETHER_QUARTZ_ORE");
    }

    private static boolean isDiamondOre(Material m) {
        if (m == null) return false;
        String n = m.name();
        return n.equals("DIAMOND_ORE") || n.equals("DEEPSLATE_DIAMOND_ORE");
    }

    private static boolean isHiddenOre(Block b) {
        return !(isExposedFace(b, 1, 0, 0)
                || isExposedFace(b, -1, 0, 0)
                || isExposedFace(b, 0, 1, 0)
                || isExposedFace(b, 0, -1, 0)
                || isExposedFace(b, 0, 0, 1)
                || isExposedFace(b, 0, 0, -1));
    }

    private static boolean isExposedFace(Block b, int dx, int dy, int dz) {
        Material n = b.getRelative(dx, dy, dz).getType();
        return isAirLike(n) || isLiquid(n);
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

    private static final class XRayState {
        private final Deque<Long> undergroundBreakTimes = new ArrayDeque<>();
        private final Deque<Long> hiddenOreTimes = new ArrayDeque<>();
        private final Deque<Long> hiddenDiamondTimes = new ArrayDeque<>();
        private final Deque<OrePoint> hiddenOrePath = new ArrayDeque<>();
    }

    private static final class OrePoint {
        private final long t;
        private final int x;
        private final int y;
        private final int z;

        private OrePoint(long t, int x, int y, int z) {
            this.t = t;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }
}
