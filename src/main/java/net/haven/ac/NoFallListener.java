package net.haven.ac;

import org.bukkit.GameMode;
import org.bukkit.Material;
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

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * NoFall: track peak Y while airborne. On landing, compute expected fall damage.
 * If vanilla fall damage didn't happen (or got cancelled), apply expected damage.
 */
public final class NoFallListener implements Listener {

    private final AntiCheatLitePlugin plugin;
    private final ViolationManager violations;

    private final Map<UUID, Double> peakY = new HashMap<>();
    private final Map<UUID, Boolean> wasOnGround = new HashMap<>();
    private final Map<UUID, Long> lastFallDamageMs = new HashMap<>();

    public NoFallListener(AntiCheatLitePlugin plugin, ViolationManager violations) {
        this.plugin = plugin;
        this.violations = violations;
    }

    private boolean shouldSkip(Player p) {
        if (p == null || !p.isOnline()) return true;
        if (p.hasPermission("anticheatlite.bypass")) return true;
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
        boolean onGroundNow = p.isOnGround();
        boolean onGroundPrev = wasOnGround.getOrDefault(id, true);
        wasOnGround.put(id, onGroundNow);

        if (inMitigationEnv(p)) {
            peakY.remove(id);
            return;
        }

        if (!onGroundNow) {
            double y = e.getTo().getY();
            peakY.put(id, Math.max(peakY.getOrDefault(id, y), y));
            return;
        }

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

            long windowMs = plugin.getConfig().getLong("checks.nofall.window_ms", 300L);
            long now = System.currentTimeMillis();
            long last = lastFallDamageMs.getOrDefault(id, 0L);

            if (now - last > windowMs) {
                boolean useFF = plugin.getConfig().getBoolean("checks.nofall.use_feather_falling_formula", false);
                double applied = raw;

                if (useFF) {
                    int ff = featherFallingLevel(p);
                    if (ff > 0) {
                        double reduce = Math.min(0.96, 0.12 * ff);
                        applied = raw * (1.0 - reduce);
                    }
                }

                p.damage(applied);

                violations.addViolation(p, CheckType.NOFALL, 1.0,
                        "nofall raw=" + String.format("%.2f", raw) + " applied=" + String.format("%.2f", applied));
                if (plugin.isSetbackOnFlag()) {
                    violations.trySetback(p);
                }
            }
        }
    }
}
