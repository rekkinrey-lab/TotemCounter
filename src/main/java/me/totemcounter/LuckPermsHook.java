package me.totemcounter;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.cacheddata.CachedMetaData;
import net.luckperms.api.event.user.UserDataRecalculateEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Reads each player's LuckPerms prefix/suffix so the totem counter is
 * appended AFTER their existing suffix instead of replacing it.
 * Also refreshes the display whenever LuckPerms recalculates a user.
 */
public class LuckPermsHook {

    private final LuckPerms api;

    public LuckPermsHook(TotemCounter plugin) {
        this.api = LuckPermsProvider.get();

        api.getEventBus().subscribe(plugin, UserDataRecalculateEvent.class, event -> {
            Player player = Bukkit.getPlayer(event.getUser().getUniqueId());
            if (player != null) {
                Bukkit.getScheduler().runTask(plugin, () -> plugin.updateDisplay(player));
            }
        });
    }

    public String getPrefix(Player player) {
        CachedMetaData meta = meta(player);
        return meta != null && meta.getPrefix() != null ? meta.getPrefix() : "";
    }

    public String getSuffix(Player player) {
        CachedMetaData meta = meta(player);
        return meta != null && meta.getSuffix() != null ? meta.getSuffix() : "";
    }

    private CachedMetaData meta(Player player) {
        var user = api.getUserManager().getUser(player.getUniqueId());
        return user == null ? null : user.getCachedData().getMetaData();
    }
}
