package me.totemcounter;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * How counting works:
 *  - totals:    the REAL number of totems each player has popped (reset only when that player dies).
 *  - baselines: for each viewer, a snapshot of everyone's totals taken when that viewer ran
 *               /resettotemcounter.
 *  - What a viewer sees for a player = total - that viewer's baseline for that player.
 * So resetting only changes what YOU see. Everyone else keeps seeing their own numbers.
 */
public class TotemCounter extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private static final LegacyComponentSerializer AMP = LegacyComponentSerializer.legacyAmpersand();

    private final Map<UUID, Integer> totals = new HashMap<>();
    private final Map<UUID, Map<UUID, Integer>> baselines = new HashMap<>();
    // Players who turned their own display off (counting continues).
    private final Set<UUID> hidden = new HashSet<>();

    private boolean globalEnabled = true;

    private File dataFile;
    private YamlConfiguration data;
    private LuckPermsHook luckPerms;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        dataFile = new File(getDataFolder(), "data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        globalEnabled = data.getBoolean("global-enabled", true);
        loadAll();

        if (Bukkit.getPluginManager().getPlugin("LuckPerms") != null) {
            luckPerms = new LuckPermsHook(this);
            getLogger().info("Hooked into LuckPerms.");
        }

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new TotemPlaceholders(this).register();
            getLogger().info("Registered PlaceholderAPI placeholders.");
        }

        Bukkit.getPluginManager().registerEvents(this, this);
        getCommand("resettotemcounter").setExecutor(this);
        getCommand("totemcounter").setExecutor(this);
        getCommand("totemcounter").setTabCompleter(this);

        updateAll();
    }

    @Override
    public void onDisable() {
        for (Player player : Bukkit.getOnlinePlayers()) clearDisplay(player);
        data.set("global-enabled", globalEnabled);
        syncToData();
        saveFile();
    }

    // ------------------------------------------------------------------
    // Events
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTotemPop(EntityResurrectEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        totals.merge(player.getUniqueId(), 1, Integer::sum);
        updateDisplay(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        UUID id = player.getUniqueId();
        // The player who died starts again from 0, for every viewer.
        totals.put(id, 0);
        for (Map<UUID, Integer> viewerBaseline : baselines.values()) {
            viewerBaseline.remove(id);
        }
        updateDisplay(player);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // Delay so LuckPerms and other plugins have finished loading the player
        Bukkit.getScheduler().runTaskLater(this, () -> updateDisplay(player), 10L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        clearDisplay(event.getPlayer());
        syncToData();
        saveFile();
    }

    /**
     * Chat: each viewer sees the counter relative to their own reset.
     * Wraps whatever renderer is already set, so it works alongside other chat plugins.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!viewerWants(null, "chat")) return;

        ChatRenderer previous = event.renderer();
        event.renderer((source, sourceDisplayName, message, viewer) -> {
            UUID viewerId = viewer instanceof Player v ? v.getUniqueId() : null;
            String suffix = getSuffixFor(viewerId, source.getUniqueId());
            Component name = suffix.isEmpty() ? sourceDisplayName : sourceDisplayName.append(AMP.deserialize(suffix));
            return previous.render(source, name, message, viewer);
        });
    }

    // ------------------------------------------------------------------
    // Commands
    // ------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("resettotemcounter")) {
            if (!(sender instanceof Player player)) {
                msg(sender, "messages.player-only", "&cOnly players can use this command.");
                return true;
            }
            // Reset what YOU see for everyone. Nobody else is affected.
            Map<UUID, Integer> mine = baselines.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>());
            mine.clear();
            mine.putAll(totals);
            updateAll();
            msg(player, "messages.reset", "&aReset everyone's totem counter &lfor you&a.");
            return true;
        }

        // /totemcounter <toggle|global|reload>
        String sub = args.length > 0 ? args[0].toLowerCase() : "";
        switch (sub) {
            case "toggle" -> {
                if (!(sender instanceof Player player)) {
                    msg(sender, "messages.player-only", "&cOnly players can use this command.");
                    return true;
                }
                if (!player.hasPermission("totemcounter.toggle")) {
                    msg(sender, "messages.no-permission", "&cYou don't have permission.");
                    return true;
                }
                UUID id = player.getUniqueId();
                if (hidden.remove(id)) {
                    data.set("hidden." + id, null);
                    updateDisplay(player);
                    msg(player, "messages.toggle-on", "&aYour totem counter is now &lvisible&a.");
                } else {
                    hidden.add(id);
                    data.set("hidden." + id, true);
                    updateDisplay(player);
                    msg(player, "messages.toggle-off", "&eYour totem counter is now &lhidden&e (still counting).");
                }
                saveFile();
            }
            case "global" -> {
                if (!sender.hasPermission("totemcounter.admin")) {
                    msg(sender, "messages.no-permission", "&cYou don't have permission.");
                    return true;
                }
                globalEnabled = !globalEnabled;
                data.set("global-enabled", globalEnabled);
                saveFile();
                updateAll();
                msg(sender, globalEnabled ? "messages.global-on" : "messages.global-off",
                        globalEnabled ? "&aTotem counters are now &lenabled&a for everyone."
                                      : "&eTotem counters are now &ldisabled&e for everyone.");
            }
            case "reload" -> {
                if (!sender.hasPermission("totemcounter.admin")) {
                    msg(sender, "messages.no-permission", "&cYou don't have permission.");
                    return true;
                }
                reloadConfig();
                updateAll();
                sender.sendMessage(AMP.deserialize("&aTotemCounter config reloaded."));
            }
            default -> sender.sendMessage(AMP.deserialize(
                    "&eUsage: &f/totemcounter <toggle|global|reload>"));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.add("toggle");
            if (sender.hasPermission("totemcounter.admin")) {
                out.add("global");
                out.add("reload");
            }
            out.removeIf(s -> !s.startsWith(args[0].toLowerCase()));
        }
        return out;
    }

    private void msg(CommandSender to, String path, String def) {
        to.sendMessage(AMP.deserialize(getConfig().getString(path, def)));
    }

    // ------------------------------------------------------------------
    // Counting / suffix (used by chat, display and PlaceholderAPI)
    // ------------------------------------------------------------------

    public boolean isShowing(UUID target) {
        return globalEnabled && !hidden.contains(target);
    }

    /**
     * Is the counter turned on for this place ("chat", "tab" or "nametag")?
     * Controlled by the "display:" section in config.yml (chat and tab are off by default).
     */
    public boolean viewerWants(UUID viewer, String channel) {
        return getConfig().getBoolean("display." + channel, channel.equals("nametag"));
    }

    /** The real number of pops for a player. */
    public int getTotal(UUID target) {
        return totals.getOrDefault(target, 0);
    }

    /** What "viewer" sees for "target" (total minus the viewer's last reset). */
    public int getViewCount(UUID viewer, UUID target) {
        int total = getTotal(target);
        if (viewer == null) return total;
        Map<UUID, Integer> base = baselines.get(viewer);
        int baseline = base == null ? 0 : base.getOrDefault(target, 0);
        return Math.max(0, total - baseline);
    }

    /** The &-coded suffix that "viewer" should see on "target" ("" if hidden). viewer may be null. */
    public String getSuffixFor(UUID viewer, UUID target) {
        if (!isShowing(target)) return "";
        return format(getViewCount(viewer, target));
    }

    private String format(int count) {
        if (count == 0 && getConfig().getBoolean("hide-when-zero", false)) return "";
        return getConfig().getString("suffix-format", " &e[&6%count%&e]")
                .replace("%count%", String.valueOf(count));
    }

    // ------------------------------------------------------------------
    // Optional shared display (same number for everyone; off by default)
    // ------------------------------------------------------------------

    public void updateAll() {
        for (Player player : Bukkit.getOnlinePlayers()) updateDisplay(player);
    }

    public void updateDisplay(Player player) {
        if (!player.isOnline()) return;

        boolean nametag = getConfig().getBoolean("show-on-nametag", false);
        boolean tablist = getConfig().getBoolean("show-on-tablist", false);
        if (!nametag && !tablist) return;

        if (!isShowing(player.getUniqueId())) {
            clearDisplay(player);
            return;
        }

        String lpPrefix = luckPerms != null ? luckPerms.getPrefix(player) : "";
        String lpSuffix = luckPerms != null ? luckPerms.getSuffix(player) : "";
        String totemPart = format(getTotal(player.getUniqueId()));

        if (nametag) {
            Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
            String teamName = teamName(player);
            Team team = board.getTeam(teamName);
            if (team == null) team = board.registerNewTeam(teamName);
            if (!team.hasEntry(player.getName())) team.addEntry(player.getName());

            team.prefix(AMP.deserialize(lpPrefix));
            team.suffix(AMP.deserialize(lpSuffix + totemPart));
        }

        if (tablist) {
            player.playerListName(AMP.deserialize(lpPrefix + player.getName() + lpSuffix + totemPart));
        }
    }

    private void clearDisplay(Player player) {
        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        Team team = board.getTeam(teamName(player));
        if (team != null) team.unregister();
        if (getConfig().getBoolean("show-on-tablist", false)) player.playerListName(null);
    }

    private String teamName(Player player) {
        // Max 16 chars for compatibility: "tc_" + 13 chars of UUID
        return "tc_" + player.getUniqueId().toString().replace("-", "").substring(0, 13);
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    private void loadAll() {
        ConfigurationSection counts = data.getConfigurationSection("counts");
        if (counts != null) {
            for (String key : counts.getKeys(false)) {
                try {
                    totals.put(UUID.fromString(key), counts.getInt(key));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }

        ConfigurationSection bases = data.getConfigurationSection("baselines");
        if (bases != null) {
            for (String viewerKey : bases.getKeys(false)) {
                ConfigurationSection vs = bases.getConfigurationSection(viewerKey);
                if (vs == null) continue;
                try {
                    Map<UUID, Integer> map = new HashMap<>();
                    for (String targetKey : vs.getKeys(false)) {
                        map.put(UUID.fromString(targetKey), vs.getInt(targetKey));
                    }
                    baselines.put(UUID.fromString(viewerKey), map);
                } catch (IllegalArgumentException ignored) {
                }
            }
        }

        ConfigurationSection hid = data.getConfigurationSection("hidden");
        if (hid != null) {
            for (String key : hid.getKeys(false)) {
                try {
                    if (hid.getBoolean(key)) hidden.add(UUID.fromString(key));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
    }

    private void syncToData() {
        data.set("counts", null);
        for (Map.Entry<UUID, Integer> e : totals.entrySet()) {
            data.set("counts." + e.getKey(), e.getValue());
        }
        data.set("baselines", null);
        for (Map.Entry<UUID, Map<UUID, Integer>> viewer : baselines.entrySet()) {
            for (Map.Entry<UUID, Integer> target : viewer.getValue().entrySet()) {
                data.set("baselines." + viewer.getKey() + "." + target.getKey(), target.getValue());
            }
        }
    }

    private void saveFile() {
        try {
            data.save(dataFile);
        } catch (IOException e) {
            getLogger().warning("Could not save data.yml: " + e.getMessage());
        }
    }
}
