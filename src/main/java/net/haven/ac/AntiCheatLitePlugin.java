package net.haven.ac;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AntiCheatLitePlugin extends JavaPlugin {

    private ViolationManager violationManager;
    private boolean chatDebugEnabled;
    private double annoyModeThresholdVl;
    private boolean shadowModeEnabled;
    private int evidenceMaxEntries;

    /** Default "annoy" damage (in half-hearts). 2.0 = 1 heart. */
    public static final double DEFAULT_PUNISH_DAMAGE = 2.0;
    private SetbackManager setbackManager;
    private DeathMessageListener deathMessageListener;
    private MovementListener movementListener;
    private CombatListener combatListener;
    private ClickListener clickListener;
    private ScaffoldListener scaffoldListener;
    private NoFallListener noFallListener;
    private XRayListener xRayListener;
    private NoSlowListener noSlowListener;
    private VelocityListener velocityListener;
    private BadPacketsListener badPacketsListener;
    private TimerListener timerListener;
    private InvMoveListener invMoveListener;

    // Last known "safe" location (usually last on-ground spot). Used for setbacks.
    private final Map<UUID, Location> lastSafe = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastFlagReason = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastFlagAt = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<EvidenceSnapshot>> evidence = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reload();
        getLogger().info("AntiCheatLite enabled for Paper 1.16.5");
    }

    @Override
    public void onDisable() {
        if (violationManager != null) {
            violationManager.shutdown();
        }
        lastSafe.clear();
        lastFlagReason.clear();
        lastFlagAt.clear();
        evidence.clear();
        getLogger().info("AntiCheatLite disabled");
    }

    public void reload() {
        reloadConfig();
        FileConfiguration cfg = getConfig();

        // Whether to spam debug info to chat by default (can be toggled by command).
        chatDebugEnabled = cfg.getBoolean("debug_chat_default", true);
        annoyModeThresholdVl = cfg.getDouble("punishments.annoy_mode_threshold_vl", 8.0);
        shadowModeEnabled = cfg.getBoolean("experimental.shadow_mode.enabled", false);
        evidenceMaxEntries = Math.max(5, cfg.getInt("experimental.evidence.max_entries_per_player", 20));

        if (violationManager != null) {
            violationManager.shutdown();
        }
        violationManager = new ViolationManager(this, cfg);

        if (setbackManager == null) {
            setbackManager = new SetbackManager(this);
        }
        setbackManager.reloadFromConfig();

        if (movementListener != null) {
            org.bukkit.event.HandlerList.unregisterAll(movementListener);
        }
        movementListener = new MovementListener(this, violationManager, cfg);
        Bukkit.getPluginManager().registerEvents(movementListener, this);

        if (combatListener != null) {
            org.bukkit.event.HandlerList.unregisterAll(combatListener);
        }
        combatListener = new CombatListener(this, violationManager, cfg);
        Bukkit.getPluginManager().registerEvents(combatListener, this);

        if (clickListener != null) {
            org.bukkit.event.HandlerList.unregisterAll(clickListener);
        }
        clickListener = new ClickListener(this, violationManager, cfg);
        Bukkit.getPluginManager().registerEvents(clickListener, this);

        if (scaffoldListener != null) {
            org.bukkit.event.HandlerList.unregisterAll(scaffoldListener);
        }
        scaffoldListener = new ScaffoldListener(this, violationManager, cfg);
        Bukkit.getPluginManager().registerEvents(scaffoldListener, this);

        if (deathMessageListener != null) {
            org.bukkit.event.HandlerList.unregisterAll(deathMessageListener);
        }
        deathMessageListener = new DeathMessageListener(this);
        Bukkit.getPluginManager().registerEvents(deathMessageListener, this);


        if (noFallListener != null) {
            org.bukkit.event.HandlerList.unregisterAll(noFallListener);
        }
        noFallListener = new NoFallListener(this, violationManager, cfg);
        Bukkit.getPluginManager().registerEvents(noFallListener, this);

        if (xRayListener != null) {
            org.bukkit.event.HandlerList.unregisterAll(xRayListener);
        }
        xRayListener = new XRayListener(this, violationManager, cfg);
        Bukkit.getPluginManager().registerEvents(xRayListener, this);

        if (noSlowListener != null) {
            org.bukkit.event.HandlerList.unregisterAll(noSlowListener);
        }
        noSlowListener = new NoSlowListener(this, violationManager, cfg);
        Bukkit.getPluginManager().registerEvents(noSlowListener, this);

        if (velocityListener != null) {
            org.bukkit.event.HandlerList.unregisterAll(velocityListener);
        }
        velocityListener = new VelocityListener(this, violationManager, cfg);
        Bukkit.getPluginManager().registerEvents(velocityListener, this);

        if (badPacketsListener != null) {
            org.bukkit.event.HandlerList.unregisterAll(badPacketsListener);
        }
        badPacketsListener = new BadPacketsListener(this, violationManager, cfg);
        Bukkit.getPluginManager().registerEvents(badPacketsListener, this);

        if (timerListener != null) {
            org.bukkit.event.HandlerList.unregisterAll(timerListener);
        }
        timerListener = new TimerListener(this, violationManager, cfg);
        Bukkit.getPluginManager().registerEvents(timerListener, this);

        if (invMoveListener != null) {
            org.bukkit.event.HandlerList.unregisterAll(invMoveListener);
        }
        invMoveListener = new InvMoveListener(this, violationManager, cfg);
        Bukkit.getPluginManager().registerEvents(invMoveListener, this);
    }

    /** Update safe location for setbacks. */
    public void updateLastSafe(Player p, Location loc) {
        if (p == null || loc == null || loc.getWorld() == null) return;
        lastSafe.put(p.getUniqueId(), loc.clone());
    }

    // Package-private access for SetbackManager.
    Location getLastSafeInternal(UUID id) {
        return lastSafe.get(id);
    }

    public SetbackManager getSetbackManager() {
        return setbackManager;
    }

    /**
     * Teleport player back to last safe location (if available).
     * This is used as a "setback" to annoy cheats on flag.
     */
    public void setback(Player p) {
        if (!canPunish()) return;
        if (setbackManager == null) return;
        setbackManager.setback(p);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);

        if (name.equals("acreload")) {
            reload();
            sender.sendMessage(color("&a[AC] Reloaded config."));
            return true;
        }

        if (name.equals("acstatus")) {
            if (args.length == 0) {
                if (sender instanceof Player) {
                    Player p = (Player) sender;
                    sender.sendMessage(color("&e[AC] Your VL=" + violationManager.getTotalVl(p.getUniqueId())));
                    return true;
                }
                sender.sendMessage(color("&cUsage: /acstatus <player>"));
                return true;
            }

            Player target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                sender.sendMessage(color("&c[AC] Player not found."));
                return true;
            }
            UUID id = target.getUniqueId();
            sender.sendMessage(color("&e[AC] " + target.getName() + " VL=" + violationManager.getTotalVl(id)));
            sender.sendMessage(color("&7  speed=" + violationManager.getVl(id, CheckType.SPEED) +
                    " fly=" + violationManager.getVl(id, CheckType.FLY) +
                    " reach=" + violationManager.getVl(id, CheckType.REACH) +
                    " aura=" + violationManager.getVl(id, CheckType.KILLAURA) +
                    " click=" + violationManager.getVl(id, CheckType.AUTOCLICKER) +
                    " scaffold=" + violationManager.getVl(id, CheckType.SCAFFOLD) +
                    " xray=" + violationManager.getVl(id, CheckType.XRAY) +
                    " noslow=" + violationManager.getVl(id, CheckType.NOSLOW) +
                    " velocity=" + violationManager.getVl(id, CheckType.VELOCITY) +
                    " badpackets=" + violationManager.getVl(id, CheckType.BADPACKETS) +
                    " timer=" + violationManager.getVl(id, CheckType.TIMER) +
                    " invmove=" + violationManager.getVl(id, CheckType.INVMOVE)));
            String last = getLastFlagReason(id);
            long at = getLastFlagAt(id);
            if (last != null && at > 0L) {
                long sec = Math.max(0L, (System.currentTimeMillis() - at) / 1000L);
                sender.sendMessage(color("&8  lastFlag=" + last + " &7(" + sec + "s ago)"));
            }
            return true;
        }

        if (name.equals("acdebug")) {
            boolean newState;
            if (args.length == 0) {
                newState = !isChatDebugEnabled();
            } else {
                String v = args[0].toLowerCase(Locale.ROOT);
                newState = v.equals("on") || v.equals("true") || v.equals("1") || v.equals("enable") || v.equals("yes");
            }
            setChatDebugEnabled(newState);
            sender.sendMessage(color("&b[AC] Chat debug " + (newState ? "&aON" : "&cOFF")));
            return true;
        }

        if (name.equals("acshadow")) {
            boolean newState;
            if (args.length == 0) {
                newState = !shadowModeEnabled;
            } else {
                String v = args[0].toLowerCase(Locale.ROOT);
                newState = v.equals("on") || v.equals("true") || v.equals("1") || v.equals("enable") || v.equals("yes");
            }
            shadowModeEnabled = newState;
            sender.sendMessage(color("&d[AC] Shadow mode " + (newState ? "&aON" : "&cOFF") + " &7(VL only, no punish/setback)."));
            return true;
        }

        if (name.equals("actop")) {
            int limit = 5;
            if (args.length >= 1) {
                try {
                    limit = Math.max(1, Math.min(20, Integer.parseInt(args[0])));
                } catch (Throwable ignored) {}
            }

            List<PlayerVl> rows = new ArrayList<PlayerVl>();
            for (Player op : Bukkit.getOnlinePlayers()) {
                double total = violationManager.getTotalVl(op.getUniqueId());
                if (total <= 0.0) continue;
                rows.add(new PlayerVl(op.getName(), total));
            }
            rows.sort((a, b) -> Double.compare(b.totalVl, a.totalVl));

            sender.sendMessage(color("&e[AC] Top VL players:"));
            if (rows.isEmpty()) {
                sender.sendMessage(color("&7  (none)"));
                return true;
            }
            for (int i = 0; i < Math.min(limit, rows.size()); i++) {
                PlayerVl r = rows.get(i);
                sender.sendMessage(color("&7  #" + (i + 1) + " &f" + r.name + " &7VL=&c" + String.format("%.2f", r.totalVl)));
            }
            return true;
        }

        if (name.equals("actrace") || name.equals("acevidence")) {
            if (args.length == 0) {
                sender.sendMessage(color("&cUsage: /" + name + " <player> [count]"));
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                sender.sendMessage(color("&c[AC] Player not found."));
                return true;
            }

            int count = 5;
            if (args.length >= 2) {
                try {
                    count = Math.max(1, Math.min(20, Integer.parseInt(args[1])));
                } catch (Throwable ignored) {}
            }

            List<EvidenceSnapshot> rows = getEvidence(target.getUniqueId(), count);
            sender.sendMessage(color("&e[AC] Trace for " + target.getName() + " (&7latest " + rows.size() + "&e):"));
            if (rows.isEmpty()) {
                sender.sendMessage(color("&7  (no evidence yet)"));
                return true;
            }
            long now = System.currentTimeMillis();
            for (EvidenceSnapshot s : rows) {
                long sec = Math.max(0L, (now - s.at) / 1000L);
                sender.sendMessage(color("&8  [" + sec + "s] &f" + s.check + " &7" + s.details));
                sender.sendMessage(color("&8      loc=&7" + s.world + " " + DF2(s.x) + "," + DF2(s.y) + "," + DF2(s.z)
                        + " &8rot=&7" + DF2(s.yaw) + "/" + DF2(s.pitch)
                        + " &8ping=&7" + s.ping + "ms &8vl=&7" + DF2(s.totalVl)));
            }
            return true;
        }

        if (name.equals("acprofile")) {
            if (args.length == 0) {
                sender.sendMessage(color("&cUsage: /acprofile <practice|survival|minigame>"));
                return true;
            }
            String profile = args[0].toLowerCase(Locale.ROOT);
            if (!applyProfile(profile)) {
                sender.sendMessage(color("&c[AC] Unknown profile: " + profile));
                return true;
            }
            saveConfig();
            reload();
            sender.sendMessage(color("&a[AC] Applied profile: &f" + profile));
            return true;
        }

        return false;
    }

    /**
     * Apply "annoy" damage when a cheat is detected.
     * If this damage would kill the player, the death message is overridden to "被自己杀了".
     */
    public void punishDamage(Player p, double damage, String debugReason) {
        if (!canPunish()) return;
        if (p == null || !p.isOnline()) return;
        if (damage <= 0) return;

        double finalHealth = p.getHealth() - damage;
        if (finalHealth <= 0.0) {
            DeathMessageListener.markSelfKill(this, p);
        }

        p.damage(damage);

        if (isChatDebugEnabled() && debugReason != null && !debugReason.isEmpty()) {
            p.sendMessage(color("&8[ACDBG] &7" + debugReason + " &8(-" + damage + ")"));
        }
    }

    public static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    public boolean isChatDebugEnabled() {
        return chatDebugEnabled;
    }

    public void setChatDebugEnabled(boolean enabled) {
        this.chatDebugEnabled = enabled;
    }

    public boolean isShadowModeEnabled() {
        return shadowModeEnabled;
    }

    public boolean canPunish() {
        return !shadowModeEnabled;
    }

    /**
     * "Annoy mode" is enabled only after total VL reaches threshold.
     * threshold <= 0 means always enabled.
     */
    public boolean isAnnoyMode(Player p) {
        if (!canPunish()) return false;
        if (p == null || !p.isOnline()) return false;
        if (annoyModeThresholdVl <= 0.0) return true;
        if (violationManager == null) return false;
        return violationManager.getTotalVl(p.getUniqueId()) >= annoyModeThresholdVl;
    }

    public void recordLastFlag(Player p, String check, String details) {
        if (p == null) return;
        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();

        lastFlagReason.put(id, check + " - " + details);
        lastFlagAt.put(id, now);

        Location l = p.getLocation();
        int ping = PingUtil.getPingMs(p);
        double total = (violationManager == null) ? 0.0 : violationManager.getTotalVl(id);

        Deque<EvidenceSnapshot> q = evidence.computeIfAbsent(id, k -> new ArrayDeque<EvidenceSnapshot>());
        q.addLast(new EvidenceSnapshot(
                now,
                check,
                details,
                l != null && l.getWorld() != null ? l.getWorld().getName() : "unknown",
                l != null ? l.getX() : 0.0,
                l != null ? l.getY() : 0.0,
                l != null ? l.getZ() : 0.0,
                l != null ? l.getYaw() : 0.0f,
                l != null ? l.getPitch() : 0.0f,
                ping,
                total
        ));
        while (q.size() > evidenceMaxEntries) q.removeFirst();
    }

    public String getLastFlagReason(UUID id) {
        return id == null ? null : lastFlagReason.get(id);
    }

    public long getLastFlagAt(UUID id) {
        if (id == null) return 0L;
        Long v = lastFlagAt.get(id);
        return v == null ? 0L : v;
    }

    public List<EvidenceSnapshot> getEvidence(UUID id, int limit) {
        if (id == null || limit <= 0) return new ArrayList<EvidenceSnapshot>();
        Deque<EvidenceSnapshot> q = evidence.get(id);
        if (q == null || q.isEmpty()) return new ArrayList<EvidenceSnapshot>();

        List<EvidenceSnapshot> rows = new ArrayList<EvidenceSnapshot>(q);
        rows.sort((a, b) -> Long.compare(b.at, a.at));
        if (rows.size() > limit) {
            return new ArrayList<EvidenceSnapshot>(rows.subList(0, limit));
        }
        return rows;
    }

    private boolean applyProfile(String profile) {
        FileConfiguration cfg = getConfig();

        if ("practice".equals(profile)) {
            cfg.set("checks.reach.base_blocks", 3.18);
            cfg.set("checks.reach.ping_comp_cap_blocks", 0.34);
            cfg.set("checks.killaura.max_angle_deg", 68.0);
            cfg.set("checks.killaura.smooth_rotation.max_yaw_stddev_deg", 0.80);
            cfg.set("checks.killaura.smooth_rotation.max_pitch_stddev_deg", 0.65);
            cfg.set("checks.velocity.min_take_ratio", 0.18);
            cfg.set("checks.scaffold.prediction.max_reach_blocks", 5.0);
            cfg.set("punishments.threshold_vl", 7.0);
            cfg.set("punishments.annoy_mode_threshold_vl", 10.0);
            return true;
        }

        if ("survival".equals(profile)) {
            cfg.set("checks.reach.base_blocks", 3.15);
            cfg.set("checks.reach.ping_comp_cap_blocks", 0.30);
            cfg.set("checks.killaura.max_angle_deg", 65.0);
            cfg.set("checks.killaura.smooth_rotation.max_yaw_stddev_deg", 0.90);
            cfg.set("checks.killaura.smooth_rotation.max_pitch_stddev_deg", 0.70);
            cfg.set("checks.velocity.min_take_ratio", 0.20);
            cfg.set("checks.scaffold.prediction.max_reach_blocks", 4.8);
            cfg.set("punishments.threshold_vl", 6.0);
            cfg.set("punishments.annoy_mode_threshold_vl", 8.0);
            return true;
        }

        if ("minigame".equals(profile)) {
            cfg.set("checks.reach.base_blocks", 3.12);
            cfg.set("checks.reach.ping_comp_cap_blocks", 0.28);
            cfg.set("checks.killaura.max_angle_deg", 62.0);
            cfg.set("checks.killaura.smooth_rotation.max_yaw_stddev_deg", 0.75);
            cfg.set("checks.killaura.smooth_rotation.max_pitch_stddev_deg", 0.60);
            cfg.set("checks.velocity.min_take_ratio", 0.22);
            cfg.set("checks.scaffold.prediction.max_reach_blocks", 4.6);
            cfg.set("punishments.threshold_vl", 5.0);
            cfg.set("punishments.annoy_mode_threshold_vl", 7.0);
            return true;
        }

        return false;
    }

    private static String DF2(double d) {
        return String.format("%.2f", d);
    }

    public static final class EvidenceSnapshot {
        public final long at;
        public final String check;
        public final String details;
        public final String world;
        public final double x;
        public final double y;
        public final double z;
        public final float yaw;
        public final float pitch;
        public final int ping;
        public final double totalVl;

        private EvidenceSnapshot(long at, String check, String details, String world,
                                 double x, double y, double z, float yaw, float pitch,
                                 int ping, double totalVl) {
            this.at = at;
            this.check = check;
            this.details = details;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
            this.ping = ping;
            this.totalVl = totalVl;
        }
    }

    private static final class PlayerVl {
        private final String name;
        private final double totalVl;

        private PlayerVl(String name, double totalVl) {
            this.name = name;
            this.totalVl = totalVl;
        }
    }
}
