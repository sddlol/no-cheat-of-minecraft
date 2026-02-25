package net.haven.ac;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * NoFall (Paper 1.16.5):
 * Track peak Y while airborne; on landing compute expected fall damage and enforce it if vanilla damage didn't happen.
 *
 * PVP-friendly anti-false-positive:
 * - Skip creative/spectator, vehicles
 * - Skip water/lava/ladder/vine/cobweb
 * - Skip SLOW_FALLING / LEVITATION
 * - Apply Jump Boost reduction
 * - Optionally apply Feather Falling reduction (12%/level)
 */
public final class NoFallListener implements Listener {

    private static final DecimalFormat DF2 = new DecimalFormat("0.00");

    private final AntiCheatLitePlugin plugin;
    private final ViolationManager vl;
    private final FileConfiguration cfg;

    private final boolean enabled;
    private final boolean alertsEnabled;
    private final String alertFormat;
    private final String bypassPermission;

    private final PunishAction punishAction;
    private final boolean setbackOnFlag;
    private final double punishTotalVl;
    private final long punishCooldownMs;
    private final boolean kickEnabled;
    private final String kickMessage;

    private final Map<UUID, Double> peakY = new HashMap<>();
    private final Map<UUID, Boolean> wasOnGround = new HashMap<>();
    private final Map<UUID, Long> lastFallDamageMs = new HashMap<>();
    private final Map<UUID, Long> lastPunishMs = new HashMap<>();

    public NoFallListener(AntiCheatLitePlugin plugin, ViolationManager vl, FileConfiguration cfg) {
        this.plugin = plugin;
        this.vl = vl;
        this.cfg = cfg;

        this.enabled = cfg.getBoolean("checks.nofall.enabled", true);
        this.alertsEnabled = cfg.getBoolean("alerts.enabled", true);
        this.alertFormat = cfg.getString("alerts.format", "&c[AC] {player} &7{check} &fVL={vl} &7({details})");
        this.bypassPermission = cfg.getString("permissions.bypass", "anticheatlite.bypass");

        this.punishAction = PunishAction.valueOf(cfg.getString("punishments.action", "NONE").toUpperCase());
        this.setbackOnFlag = cfg.getBoolean("punishments.setback_on_flag", true);
        this.punishTotalVl = Math.max(0.0, cfg.getDouble("punishments.total_vl_to_punish", 20.0));
        this.punishCooldownMs = Math.max(0L, cfg.getLong("punishments.cooldown_ms", 5000L));
        this.kickEnabled = cfg.getBoolean("punishments.kick.enabled", false);
        this.kickMessage = cfg.getString("punishments.kick.message", "&cKicked: cheating detected.");
    }

    private boolean shouldSkip(Player p) {
        if (!enabled) return true;
        if (p == null || !p.isOnline()) return true;
        if (p.hasPermission(bypassPermission)) return true;
        GameMode gm = p.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) return true;
        if (p.isInsideVehicle()) return true;
        if (p.isDead()) return true;
        if (p.hasPotionEffect(PotionEffectType.SLOW_FALLING)) return true;
        if (p.hasPotionEffect(PotionEffectType.LEVITATION)) return true;
        return false;
    }

    private boolean inMitigationEnv(Player p) {
        Material feet = p.getLocation().getBlock().getType();
        Material head = p.getEyeLocation().getBlock().getType();
        if (feet == Material.WATER || head == Material.WATER) return true;
        if (feet == Material.LAVA || head == Material.LAVA) return true;
        if (feet == Material.COBWEB) return true;
        if (feet == Material.LADDER || feet == Material.VINE) return true;
        return false;
    }

    private int jumpBoostLevel(Player p) {
        PotionEffect jb = p.getPotionEffect(PotionEffectType.JUMP);
        if (jb == null) return 0;
        return Math.max(0, jb.getAmplifier() + 1);
    }

    private int featherFallingLevel(Player p) {
        try {
            ItemStack boots = p.getInventory().getBoots();
            if (boots == null) return 0;
            return boots.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.PROTECTION_FALL);
        } catch (Throwable t) {
            return 0;
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
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("anticheatlite.alert")) {
                p.sendMessage(msg);
            }
        }
    }

    private void maybePunish(Player suspected) {
        if (punishAction == PunishAction.NONE) return;
        UUID id = suspected.getUniqueId();

        double total = vl.getTotalVl(id);
        if (total < punishTotalVl) return;

        long now = System.currentTimeMillis();
        long last = lastPunishMs.getOrDefault(id, 0L);
        if (now - last < punishCooldownMs) return;
        lastPunishMs.put(id, now);

        if (punishAction == PunishAction.KICK && kickEnabled) {
            suspected.kickPlayer(AntiCheatLitePlugin.color(kickMessage));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onFallDamage(EntityDamageEvent e) {
        Entity ent = e.getEntity();
        if (!(ent instanceof Player)) return;
        if (e.getCause() != EntityDamageEvent.DamageCause.FALL) return;
        lastFallDamageMs.put(ent.getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (shouldSkip(p)) return;
        if (e.getFrom().getWorld() != e.getTo().getWorld()) return;

        UUID id = p.getUniqueId();

        if (inMitigationEnv(p)) {
            peakY.remove(id);
            wasOnGround.put(id, true);
            return;
        }

        boolean onGroundNow = p.isOnGround();
        boolean onGroundPrev = wasOnGround.getOrDefault(id, true);
        wasOnGround.put(id, onGroundNow);

        if (!onGroundNow) {
            double y = e.getTo().getY();
            peakY.put(id, Math.max(peakY.getOrDefault(id, y), y));
            return;
        }

        // landing moment
        if (!onGroundPrev && onGroundNow) {
            Double py = peakY.remove(id);
            if (py == null) return;

            double fallDist = Math.max(0.0, py - e.getTo().getY());

            // Vanilla: damage points = max(0, fallDistance - 3)
            double raw = Math.max(0.0, fallDist - 3.0);

            // Jump boost reduces fall damage by 1 per level
            int jLv = jumpBoostLevel(p);
            raw = Math.max(0.0, raw - jLv);

            if (raw <= 0.0) return;

            long windowMs = Math.max(50L, cfg.getLong("checks.nofall.window_ms", 300L));
            long now = System.currentTimeMillis();
            long last = lastFallDamageMs.getOrDefault(id, 0L);

            // If vanilla didn't apply fall damage recently, enforce it.
            if (now - last > windowMs) {
                boolean useFF = cfg.getBoolean("checks.nofall.use_feather_falling_formula", false);
                double applied = raw;

                if (useFF) {
                    int ff = featherFallingLevel(p);
                    if (ff > 0) {
                        double reduce = Math.min(0.96, 0.12 * ff);
                        applied = raw * (1.0 - reduce);
                    }
                }

                p.damage(applied);

                double next = vl.addVl(id, CheckType.NOFALL, 1.0);
                alert(p, "NOFALL", next, "raw=" + DF2.format(raw) + ", dmg=" + DF2.format(applied));

                if (punishAction == PunishAction.SETBACK && setbackOnFlag) {
                    plugin.setback(p);
                } else {
                    maybePunish(p);
                }
            }
        }
    }
}
