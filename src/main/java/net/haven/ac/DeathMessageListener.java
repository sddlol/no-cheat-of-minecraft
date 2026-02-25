package net.haven.ac;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.metadata.FixedMetadataValue;

public final class DeathMessageListener implements Listener {

    public static final String META_SELF_KILL = "ac_self_kill";

    private final AntiCheatLitePlugin plugin;

    public DeathMessageListener(AntiCheatLitePlugin plugin) {
        this.plugin = plugin;
    }

    public static void markSelfKill(AntiCheatLitePlugin plugin, Player p) {
        p.setMetadata(META_SELF_KILL, new FixedMetadataValue(plugin, true));
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        if (p.hasMetadata(META_SELF_KILL)) {
            e.setDeathMessage(AntiCheatLitePlugin.color("&7" + p.getName() + " &c被自己杀了"));
            p.removeMetadata(META_SELF_KILL, plugin);
        }
    }
}
