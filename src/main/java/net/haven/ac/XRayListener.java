package net.haven.ac;

import org.bukkit.Bukkit;
import org.bukkit.Material;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple XRay heuristics inspired by common anti-cheat practice:
 * - focus on underground mining only
 * - focus on ores that were hidden (not air-exposed) before break
 * - evaluate ore ratio in a sliding window
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

        XRayState st = states.computeIfAbsent(p.getUniqueId(), k -> new XRayState());

        boolean underground = b.getY() <= maxY;
        if (underground && !isAirLike(type)) {
            st.undergroundBreakTimes.addLast(now);
            prune(st.undergroundBreakTimes, now, windowMs);
        }

        if (!isOre(type)) return;

        boolean hidden = isHiddenOre(b);
        if (!hidden) return;

        st.hiddenOreTimes.addLast(now);
        prune(st.hiddenOreTimes, now, windowMs);

        if (isDiamondOre(type)) {
            st.hiddenDiamondTimes.addLast(now);
            prune(st.hiddenDiamondTimes, now, diamondWindowMs);
        }

        int mineN = st.undergroundBreakTimes.size();
        int oreN = st.hiddenOreTimes.size();
        int diaN = st.hiddenDiamondTimes.size();
        double ratio = mineN <= 0 ? 0.0 : (oreN * 1.0 / mineN);

        boolean suspiciousRatio = mineN >= minUndergroundBreaks && oreN >= minHiddenOres && ratio > maxHiddenOreRatio;
        boolean suspiciousDiamondBurst = diaN > maxDiamondPerWindow;

        if (suspiciousRatio || suspiciousDiamondBurst) {
            double next = vl.addVl(p.getUniqueId(), CheckType.XRAY, vlAdd);
            String details = "ore=" + oreN + "/" + mineN +
                    ", ratio=" + DF2.format(ratio) +
                    ", dia1m=" + diaN +
                    (suspiciousRatio ? ", ratioFlag" : "") +
                    (suspiciousDiamondBurst ? ", diaBurst" : "");
            alert(p, "XRAY", next, details);
        }
    }

    private void prune(Deque<Long> q, long now, int winMs) {
        while (!q.isEmpty() && (now - q.peekFirst()) > winMs) q.removeFirst();
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
        plugin.getLogger().info("[AC] " + suspected.getName() + " " + check + " VL=" + DF2.format(checkVl) + " (" + details + ")");
    }

    private static final class XRayState {
        private final Deque<Long> undergroundBreakTimes = new ArrayDeque<>();
        private final Deque<Long> hiddenOreTimes = new ArrayDeque<>();
        private final Deque<Long> hiddenDiamondTimes = new ArrayDeque<>();
    }
}
