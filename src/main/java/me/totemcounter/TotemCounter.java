package me.totemcounter;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
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

public class TotemCounter extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private static final LegacyComponentSerializer AMP = LegacyComponentSerializer.legacyAmpersand();

    // Every player has their own counter, keyed by UUID.
    private final Map<UUID, Integer> counts = new HashMap<>();
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

        for (Player player : Bukkit.getOnlinePlayers()) {
            loadPlayer(player);
            updateDisplay(player);
        }
    }

    @Override
    public void onDisable() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            savePlayer(player.getUniqueId());
            clearDisplay(player);
        }
        data.set("global-enabled", globalEnabled);
        saveFile();
    }

    // ------------------------------------------------------------------
    // Events
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTotemPop(EntityResurrectEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        counts.merge(player.getUniqueId(), 1, Integer::sum);
        updateDisplay(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        // Only the player who died is reset.
        counts.put(player.getUniqueId(), 0);
        updateDisplay(player);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        loadPlayer(player);
        // Delay so LuckPerms and other plugins have finished loading the player
        Bukkit.getScheduler().runTaskLater(this, () -> updateDisplay(player), 10L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        savePlayer(player.getUniqueId());
        saveFile();
        clearDisplay(player);
        counts.remove(player.getUniqueId());
        hidden.remove(player.getUniqueId());
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
            // Only resets the sender's own counter.
            counts.put(player.getUniqueId(), 0);
            updateDisplay(player);
            msg(player, "messages.reset", "&aYour totem counter has been reset.");
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
    // Display
    // ------------------------------------------------------------------

    public boolean isShowing(UUID uuid) {
        return globalEnabled && !hidden.contains(uuid);
    }

    public int getCount(UUID uuid) {
        return counts.getOrDefault(uuid, 0);
    }

    /** The totem suffix as an &-coded string ("" if hidden/disabled). */
    public String getTotemSuffix(UUID uuid) {
        if (!isShowing(uuid)) return "";
        int count = getCount(uuid);
        if (count == 0 && getConfig().getBoolean("hide-when-zero", false)) return "";
        return getConfig().getString("suffix-format", " &e[&6%count%&e]")
                .replace("%count%", String.valueOf(count));
    }

    public void updateAll() {
        for (Player player : Bukkit.getOnlinePlayers()) updateDisplay(player);
    }

    public void updateDisplay(Player player) {
        if (!player.isOnline()) return;

        if (!isShowing(player.getUniqueId())) {
            clearDisplay(player);
            return;
        }

        String lpPrefix = luckPerms != null ? luckPerms.getPrefix(player) : "";
        String lpSuffix = luckPerms != null ? luckPerms.getSuffix(player) : "";
        String totemPart = getTotemSuffix(player.getUniqueId());

        // Nametag (above head) via a per-player scoreboard team
        if (getConfig().getBoolean("show-on-nametag", true)) {
            Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
            String teamName = teamName(player);
            Team team = board.getTeam(teamName);
            if (team == null) team = board.registerNewTeam(teamName);
            if (!team.hasEntry(player.getName())) team.addEntry(player.getName());

            team.prefix(AMP.deserialize(lpPrefix));
            team.suffix(AMP.deserialize(lpSuffix + totemPart));
        }

        // Tab list
        if (getConfig().getBoolean("show-on-tablist", true)) {
            player.playerListName(AMP.deserialize(lpPrefix + player.getName() + lpSuffix + totemPart));
        }

        // Display name (used by chat and many other plugins)
        if (getConfig().getBoolean("show-in-chat", true)) {
            player.displayName(AMP.deserialize(lpPrefix + player.getName() + lpSuffix + totemPart));
        }
    }

    private void clearDisplay(Player player) {
        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        Team team = board.getTeam(teamName(player));
        if (team != null) team.unregister();
        player.playerListName(null);
        player.displayName(null);
    }

    private String teamName(Player player) {
        // Max 16 chars for compatibility: "tc_" + 13 chars of UUID
        return "tc_" + player.getUniqueId().toString().replace("-", "").substring(0, 13);
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    private void loadPlayer(Player player) {
        UUID id = player.getUniqueId();
        counts.put(id, data.getInt("counts." + id, 0));
        if (data.getBoolean("hidden." + id, false)) hidden.add(id);
        else hidden.remove(id);
    }

    private void savePlayer(UUID uuid) {
        data.set("counts." + uuid, counts.getOrDefault(uuid, 0));
    }

    private void saveFile() {
        try {
            data.save(dataFile);
        } catch (IOException e) {
            getLogger().warning("Could not save data.yml: " + e.getMessage());
        }
    }
}
