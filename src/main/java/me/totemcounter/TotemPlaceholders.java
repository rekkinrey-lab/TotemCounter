package me.totemcounter;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.OfflinePlayer;

/**
 * PlaceholderAPI placeholders, for tab/scoreboard/chat plugins:
 *   %totemcounter_count%   -> the player's number of pops
 *   %totemcounter_suffix%  -> the formatted suffix, e.g. " [3]" (empty if hidden)
 */
public class TotemPlaceholders extends PlaceholderExpansion {

    private final TotemCounter plugin;

    public TotemPlaceholders(TotemCounter plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "totemcounter";
    }

    @Override
    public String getAuthor() {
        return "TotemCounter";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (player == null) return "";
        switch (params.toLowerCase()) {
            case "count":
                return String.valueOf(plugin.getCount(player.getUniqueId()));
            case "suffix":
                String raw = plugin.getTotemSuffix(player.getUniqueId());
                return LegacyComponentSerializer.legacySection().serialize(
                        LegacyComponentSerializer.legacyAmpersand().deserialize(raw));
            default:
                return null;
        }
    }
}
