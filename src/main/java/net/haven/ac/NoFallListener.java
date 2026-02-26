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

public final class NoFallListener implements Listener {

    private final AntiCheatLitePlugin plugin;
    private final ViolationManager vl;
    private final FileConfiguration cfg;

    private final boolean enabled;

    private final boolean alertsEnabled;
    private final String alertFormat;
    private final String bypassPermission;

    private final PunishAction punishAction;
    private final double punishThreshold;
    private final boolean setbackOnFlag;

    private final long windowMs;
    private final boolean useFeatherFallingFormula;

    private static final DecimalFormat DF2 = new DecimalFormat("0.00");

    private final Map<UUID, Double> peakY = new HashMap<>();
    private final Map<UUID, Boolean> wasOnGround = new HashMap<>();
    private final Map<UUID, Long> lastFallDamageMs = new HashMap<>();

    public NoFallListener(AntiCheatLitePlugin plugin, ViolationManager vl, FileConfiguration cfg) {
        this.plugin = plugin;
        this.vl = vl;
        this.cfg = cfg;

        this.enabled = cfg.getBoolean("checks.nofall.enabled", true);

        this.alertsEnabled = cfg.getBoolean("alerts.enabled", true);
        this.alertFormat = cfg.getString("alerts.format", "&c[AC]&7 {player} &f{check} &7VL={vl} &8({details})");
        this.bypassPermission = cfg.getString("bypass_permission", "anticheatlite.bypass");

        this.punishAction = PunishAction.fromString(cfg.getString("punishments.action", "SETBACK"));
        this.punishThreshold = cfg.getDouble("punishments.threshold_vl", 6.0);
        this.setbackOnFlag = cfg.getBoolean("punishments.setback_on_flag", true);

        this.windowMs = cfg.getLong("checks.nofall.window_ms", 300L);
        this.useFeatherFallingFormula = cfg.getBoolean("checks.nofall.use_feather_falling_formula", false);
    }

    private boolean shouldSkip(Player p) {
        if (!enabled) return true;
        if (p == null || !p.isOnline()) return true;
        if (p.hasPermission(bypassPermission)) return true;

        GameMode gm = p.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) return true;
        if (p.isInsideVehicle()) return true;
        if (p.isDead()) return true;

        if (p.hasPotionEffect(Compat.potionType("SLOW_FALLING"))) return true;
        if (p.hasPotionEffect(Compat.potionType("LEVITATION"))) return true;

        return false;
    }

    private boolean inMitigationEnv(Player p) {
        Material feet = p.getLocation().getBlock().getType();
        Material head = p.getEyeLocation().getBlock().getType();

        if (feet == Material.WATER || head == Material.WATER) return true;
        if (feet == Material.LAVA || head == Material.LAVA) return true;
        if (feet == Compat.material("COBWEB","WEB")) return true;
        if (feet == Material.LADDER || feet == Material.VINE) return true;

        return false;
    }

    private int jumpBoostLevel(Player p) {
        PotionEffect jb = Compat.getPotionEffect(p, PotionEffectType.JUMP);
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

        for (Player online : Bukkit.getOnlinePlayers()) {
            online.sendMessage(msg);
        }
        plugin.getLogger().info("[AC] " + suspected.getName() + " " + check + " VL=" + DF2.format(checkVl) + " (" + details + ")");
    }

    private void maybePunish(Player p) {
        double total = vl.getTotalVl(p.getUniqueId());
        if (total < punishThreshold) return;
        if (punishAction == PunishAction.KICK) {
            p.kickPlayer(AntiCheatLitePlugin.color("&c[AC] Suspicious movement detected."));
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
        if (e.getTo() == null) return;
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

        if (!onGroundPrev && onGroundNow) {
            Double py = peakY.remove(id);
            if (py == null) return;

            double fallDist = Math.max(0.0, py - e.getTo().getY());
            double raw = Math.max(0.0, fallDist - 3.0);

            int jLv = jumpBoostLevel(p);
            raw = Math.max(0.0, raw - jLv);

            if (raw <= 0.0) return;

            long now = System.currentTimeMillis();
            long last = lastFallDamageMs.getOrDefault(id, 0L);

            if (now - last > windowMs) {
                double applied = raw;

                if (useFeatherFallingFormula) {
                    int ff = featherFallingLevel(p);
                    if (ff > 0) {
                        double reduce = Math.min(0.96, 0.12 * ff);
                        applied = raw * (1.0 - reduce);
                    }
                }

                p.damage(applied);

                double next = vl.addVl(id, CheckType.NOFALL, 1.0);
                alert(p, "NOFALL", next, "raw=" + DF2.format(raw) + ",dmg=" + DF2.format(applied));

                if (punishAction == PunishAction.SETBACK && setbackOnFlag) {
                    plugin.setback(p);
                } else {
                    maybePunish(p);
                }
            }
        }
    }
}
