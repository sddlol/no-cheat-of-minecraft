package net.haven.ac;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ViolationManager {

    private final Map<UUID, EnumMap<CheckType, Double>> vls = new ConcurrentHashMap<>();

    private final double decayPerSecond;
    private volatile long lastDecayAt;

    public ViolationManager(AntiCheatLitePlugin plugin, FileConfiguration cfg) {
        this.decayPerSecond = Math.max(0.0, cfg.getDouble("punishments.decay_per_second", 0.35));
        this.lastDecayAt = System.currentTimeMillis();
    }

    public void shutdown() {
        vls.clear();
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
        return next;
    }

    public double getVl(UUID id, CheckType type) {
        maybeDecay();
        EnumMap<CheckType, Double> map = vls.get(id);
        if (map == null) return 0.0;
        return map.getOrDefault(type, 0.0);
    }

    public double getTotalVl(UUID id) {
        maybeDecay();
        EnumMap<CheckType, Double> map = vls.get(id);
        if (map == null) return 0.0;
        double sum = 0.0;
        for (Double d : map.values()) sum += d;
        return sum;
    }
}
