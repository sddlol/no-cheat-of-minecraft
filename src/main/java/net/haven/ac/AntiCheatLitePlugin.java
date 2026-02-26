package net.haven.ac;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AntiCheatLitePlugin extends JavaPlugin {

    private ViolationManager violationManager;
    private boolean chatDebugEnabled;

    /** Default "annoy" damage (in half-hearts). 2.0 = 1 heart. */
    public static final double DEFAULT_PUNISH_DAMAGE = 2.0;
    private SetbackManager setbackManager;
    private DeathMessageListener deathMessageListener;
    private MovementListener movementListener;
    private CombatListener combatListener;
    private ClickListener clickListener;
    private ScaffoldListener scaffoldListener;
    private NoFallListener noFallListener;

    // Last known "safe" location (usually last on-ground spot). Used for setbacks.
    private final Map<UUID, Location> lastSafe = new ConcurrentHashMap<>();

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
        getLogger().info("AntiCheatLite disabled");
    }

    public void reload() {
        reloadConfig();
        FileConfiguration cfg = getConfig();

        // Whether to spam debug info to chat by default (can be toggled by command).
        chatDebugEnabled = cfg.getBoolean("debug_chat_default", true);
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
                    " scaffold=" + violationManager.getVl(id, CheckType.SCAFFOLD)));
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

        return false;
    }

    /**
     * Apply "annoy" damage when a cheat is detected.
     * If this damage would kill the player, the death message is overridden to "被自己杀了".
     */
    public void punishDamage(Player p, double damage, String debugReason) {
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
}
