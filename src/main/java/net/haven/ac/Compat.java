package net.haven.ac;

import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.lang.reflect.Method;
import java.util.Locale;

public final class Compat {
    private Compat() {}

    public static boolean isOneOf(Material m, String... names) {
        if (m == null || names == null) return false;
        String mn = m.name();
        for (String n : names) {
            if (n != null && mn.equalsIgnoreCase(n)) return true;
        }
        return false;
    }

    public static Material material(String... names) {
        if (names == null) return null;
        for (String n : names) {
            if (n == null) continue;
            try { return Material.valueOf(n.toUpperCase(Locale.ROOT)); } catch (Throwable ignored) {}
        }
        return null;
    }

    public static boolean isCobweb(Material m) {
        return isOneOf(m, "COBWEB", "WEB");
    }

    public static PotionEffectType potionType(String name) {
        if (name == null) return null;
        PotionEffectType t = PotionEffectType.getByName(name.toUpperCase(Locale.ROOT));
        if (t != null) return t;
        try { return (PotionEffectType) PotionEffectType.class.getField(name.toUpperCase(Locale.ROOT)).get(null); } catch (Throwable ignored) {}
        return null;
    }

    public static boolean hasPotion(Player p, String typeName) {
        PotionEffectType t = potionType(typeName);
        if (p == null || t == null) return false;
        for (PotionEffect pe : p.getActivePotionEffects()) {
            if (pe != null && pe.getType() != null && pe.getType().equals(t)) return true;
        }
        return false;
    }

    public static int potionAmplifier(Player p, String typeName) {
        PotionEffectType t = potionType(typeName);
        if (p == null || t == null) return 0;
        int best = 0;
        for (PotionEffect pe : p.getActivePotionEffects()) {
            if (pe != null && pe.getType() != null && pe.getType().equals(t)) best = Math.max(best, pe.getAmplifier());
        }
        return best;
    }

    public static PotionEffect getPotionEffect(Player p, PotionEffectType t) {
        if (p == null || t == null) return null;
        try {
            Method m = p.getClass().getMethod("getPotionEffect", PotionEffectType.class);
            Object o = m.invoke(p, t);
            if (o instanceof PotionEffect) return (PotionEffect) o;
        } catch (Throwable ignored) {}
        for (PotionEffect pe : p.getActivePotionEffects()) {
            if (pe != null && pe.getType() != null && pe.getType().equals(t)) return pe;
        }
        return null;
    }

    public static boolean isGliding(Player p) {
        try {
            Method m = p.getClass().getMethod("isGliding");
            Object o = m.invoke(p);
            return o instanceof Boolean && (Boolean) o;
        } catch (Throwable ignored) {}
        return false;
    }

    public static boolean isSwimming(Player p) {
        try {
            Method m = p.getClass().getMethod("isSwimming");
            Object o = m.invoke(p);
            return o instanceof Boolean && (Boolean) o;
        } catch (Throwable ignored) {}
        return false;
    }

    public static boolean isRiptiding(Player p) {
        try {
            Method m = p.getClass().getMethod("isRiptiding");
            Object o = m.invoke(p);
            return o instanceof Boolean && (Boolean) o;
        } catch (Throwable ignored) {}
        return false;
    }

    public static boolean hasLineOfSight(Player p, Entity e) {
        if (p == null || e == null) return true;
        try { return p.hasLineOfSight(e); } catch (Throwable ignored) { return true; }
    }
}
