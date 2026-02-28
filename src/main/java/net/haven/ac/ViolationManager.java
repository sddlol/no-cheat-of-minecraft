package net.haven.ac;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ViolationManager {

    private final AntiCheatLitePlugin plugin;
    private final Map<UUID, EnumMap<CheckType, Double>> vls = new ConcurrentHashMap<>();

    private final double decayPerSecond;
    private volatile long lastDecayAt;

    // Guard against suspicious "all VL suddenly become 0" cases.
    private final boolean vlResetGuardEnabled;
    private final double vlResetGuardMinPrevTotal;
    private final long vlResetGuardMaxDropWindowMs;
    private final long vlResetGuardCooldownMs;

    private final Map<UUID, Double> lastObservedTotalVl = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastObservedAt = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastGuardPunishAt = new ConcurrentHashMap<>();

    public ViolationManager(AntiCheatLitePlugin plugin, FileConfiguration cfg) {
        this.plugin = plugin;
        this.decayPerSecond = Math.max(0.0, cfg.getDouble("punishments.decay_per_second", 0.35));
        this.lastDecayAt = System.currentTimeMillis();

        this.vlResetGuardEnabled = cfg.getBoolean("checks.vl_reset_guard.enabled", true);
        this.vlResetGuardMinPrevTotal = Math.max(0.0, cfg.getDouble("checks.vl_reset_guard.min_previous_total_vl", 8.0));
        this.vlResetGuardMaxDropWindowMs = Math.max(1L, cfg.getLong("checks.vl_reset_guard.max_drop_window_ms", 5000L));
        this.vlResetGuardCooldownMs = Math.max(0L, cfg.getLong("checks.vl_reset_guard.cooldown_ms", 15000L));
    }

    public void shutdown() {
        vls.clear();
        lastObservedTotalVl.clear();
        lastObservedAt.clear();
        lastGuardPunishAt.clear();
    }

    private void maybeDecay() {
        if (decayPerSecond <= 0.0) return;
        long now = System.currentTimeMillis();
        long dtMs = now - lastDecayAt;
        if (dtMs < 250L) return; // avoid over-decaying on very frequent calls

        double dtSeconds = dtMs / 1000.0;
        double dec = decayPerSecond * dtSeconds;
        lastDecayAt = now;

        for (Map.Entry<UUID, EnumMap<CheckType, Double>> e : vls.entrySet()) {
            EnumMap<CheckType, Double> map = e.getValue();
            boolean allZero = true;
            for (CheckType t : CheckType.values()) {
                double cur = map.getOrDefault(t, 0.0);
                if (cur <= 0.0) continue;
                cur = Math.max(0.0, cur - dec);
                map.put(t, cur);
                if (cur > 0.0) allZero = false;
            }
            if (allZero) {
                vls.remove(e.getKey());
            }
        }
    }

    public double addVl(UUID id, CheckType type, double add) {
        maybeDecay();
        EnumMap<CheckType, Double> map = vls.computeIfAbsent(id, k -> new EnumMap<>(CheckType.class));
        double next = Math.max(0.0, map.getOrDefault(type, 0.0) + add);
        map.put(type, next);
        observeTotalAndMaybeGuard(id, computeTotal(map));
        return next;
    }

    public double getVl(UUID id, CheckType type) {
        maybeDecay();
        EnumMap<CheckType, Double> map = vls.get(id);
        double value = (map == null) ? 0.0 : map.getOrDefault(type, 0.0);
        observeTotalAndMaybeGuard(id, map == null ? 0.0 : computeTotal(map));
        return value;
    }

    public double getTotalVl(UUID id) {
        maybeDecay();
        EnumMap<CheckType, Double> map = vls.get(id);
        double total = (map == null) ? 0.0 : computeTotal(map);
        observeTotalAndMaybeGuard(id, total);
        return total;
    }

    private static double computeTotal(EnumMap<CheckType, Double> map) {
        double sum = 0.0;
        for (Double d : map.values()) sum += d;
        return sum;
    }

    private void observeTotalAndMaybeGuard(UUID id, double currentTotal) {
        long now = System.currentTimeMillis();

        Double prevObj = lastObservedTotalVl.get(id);
        Long prevAtObj = lastObservedAt.get(id);
        double prev = prevObj == null ? 0.0 : prevObj;
        long prevAt = prevAtObj == null ? now : prevAtObj;

        if (vlResetGuardEnabled
                && prev >= vlResetGuardMinPrevTotal
                && currentTotal <= 0.0001
                && (now - prevAt) <= vlResetGuardMaxDropWindowMs) {
            long lastPunish = lastGuardPunishAt.getOrDefault(id, 0L);
            if ((now - lastPunish) >= vlResetGuardCooldownMs) {
                Player p = Bukkit.getPlayer(id);
                if (p != null && p.isOnline() && !p.isDead()) {
                    double damage = Math.max(0.5, p.getHealth() * 0.5);
                    plugin.punishDamage(p, damage, "VL_RESET_GUARD prev=" + String.format("%.2f", prev));
                    lastGuardPunishAt.put(id, now);
                    plugin.getLogger().warning("[AC] VL reset guard triggered for " + p.getName() + " (prevTotal=" + String.format("%.2f", prev) + ")");
                }
            }
        }

        lastObservedTotalVl.put(id, currentTotal);
        lastObservedAt.put(id, now);

        if (currentTotal <= 0.0001) {
            // Keep guard history for a while, but drop noisy stale players quickly.
            if (prev <= 0.0001) {
                lastObservedTotalVl.remove(id);
                lastObservedAt.remove(id);
            }
        }
    }
}
