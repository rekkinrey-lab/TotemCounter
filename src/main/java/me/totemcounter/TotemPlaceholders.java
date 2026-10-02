package me.totemcounter;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.clip.placeholderapi.expansion.Relational;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * PlaceholderAPI placeholders.
 *
 * Per-viewer (use these in TAB for nametag / tab list):
 *   %rel_totemcounter_tab%     -> the suffix for the TAB LIST (only if display.tab is true in config.yml)
 *   %rel_totemcounter_nametag% -> the suffix for NAMETAGS (only if display.nametag is true in config.yml)
 *   %rel_totemcounter_suffix%  -> the suffix, always shown (ignores the display settings)
 *   %rel_totemcounter_count%   -> just the number the viewer sees
 *
 * Normal (shows the player's own count as they see it):
 *   %totemcounter_suffix%
 *   %totemcounter_count%
 */
public class TotemPlaceholders extends PlaceholderExpansion implements Relational {

    private static final LegacyComponentSerializer AMP = LegacyComponentSerializer.legacyAmpersand();
    private static final LegacyComponentSerializer SECTION = LegacyComponentSerializer.legacySection();

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

    // %totemcounter_...%
    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (player == null) return "";
        return resolve(player.getUniqueId(), player.getUniqueId(), params);
    }

    // %rel_totemcounter_...%   (one = viewer, two = target, same order TAB uses)
    @Override
    public String onPlaceholderRequest(Player viewer, Player target, String identifier) {
        if (viewer == null || target == null) return "";
        return resolve(viewer.getUniqueId(), target.getUniqueId(), identifier);
    }

    private String color(String raw) {
        return SECTION.serialize(AMP.deserialize(raw));
    }

    private String resolve(java.util.UUID viewer, java.util.UUID target, String params) {
        switch (params.toLowerCase()) {
            case "count":
                return String.valueOf(plugin.getViewCount(viewer, target));
            case "suffix":
                // Always shown, ignores the display settings.
                return color(plugin.getSuffixFor(viewer, target));
            case "tab":
                // Only shown if display.tab is true in config.yml.
                return plugin.viewerWants(viewer, "tab") ? color(plugin.getSuffixFor(viewer, target)) : "";
            case "nametag":
                // Only shown if display.nametag is true in config.yml.
                return plugin.viewerWants(viewer, "nametag") ? color(plugin.getSuffixFor(viewer, target)) : "";
            default:
                return null;
        }
    }
}
