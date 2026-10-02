package me.totemcounter;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
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
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class TotemCounter extends JavaPlugin implements Listener, CommandExecutor {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    // Every player has their own counter, keyed by UUID.
    private final Map<UUID, Integer> counts = new HashMap<>();

    private File dataFile;
    private YamlConfiguration data;
    private LuckPermsHook luckPerms;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        dataFile = new File(getDataFolder(), "data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);

        if (Bukkit.getPluginManager().getPlugin("LuckPerms") != null) {
            luckPerms = new LuckPermsHook(this);
            getLogger().info("Hooked into LuckPerms.");
        }

        Bukkit.getPluginManager().registerEvents(this, this);
        getCommand("resettotemcounter").setExecutor(this);

        // Handle players already online (e.g. after /reload)
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
        // Delay one tick so LuckPerms data is loaded
        Bukkit.getScheduler().runTask(this, () -> updateDisplay(player));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        savePlayer(player.getUniqueId());
        saveFile();
        clearDisplay(player);
        counts.remove(player.getUniqueId());
    }

    // ------------------------------------------------------------------
    // Command
    // ------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(LEGACY.deserialize(getConfig().getString("messages.player-only", "Players only.")));
            return true;
        }

        // Only resets the sender's own counter.
        counts.put(player.getUniqueId(), 0);
        updateDisplay(player);
        player.sendMessage(LEGACY.deserialize(getConfig().getString("messages.reset", "&aCounter reset.")));
        return true;
    }

    // ------------------------------------------------------------------
    // Display
    // ------------------------------------------------------------------

    public void updateDisplay(Player player) {
        if (!player.isOnline()) return;

        int count = counts.getOrDefault(player.getUniqueId(), 0);

        String lpPrefix = luckPerms != null ? luckPerms.getPrefix(player) : "";
        String lpSuffix = luckPerms != null ? luckPerms.getSuffix(player) : "";

        String totemPart = "";
        if (count > 0 || !getConfig().getBoolean("hide-when-zero", false)) {
            totemPart = getConfig().getString("suffix-format", " &e[&6%count%&e]")
                    .replace("%count%", String.valueOf(count));
        }

        // Nametag (above head) via a per-player scoreboard team
        if (getConfig().getBoolean("show-on-nametag", true)) {
            Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
            String teamName = teamName(player);
            Team team = board.getTeam(teamName);
            if (team == null) team = board.registerNewTeam(teamName);
            if (!team.hasEntry(player.getName())) team.addEntry(player.getName());

            team.prefix(LEGACY.deserialize(lpPrefix));
            team.suffix(LEGACY.deserialize(lpSuffix + totemPart));
        }

        // Tab list
        if (getConfig().getBoolean("show-on-tablist", true)) {
            Component tab = LEGACY.deserialize(lpPrefix + player.getName() + lpSuffix + totemPart);
            player.playerListName(tab);
        }
    }

    private void clearDisplay(Player player) {
        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        Team team = board.getTeam(teamName(player));
        if (team != null) team.unregister();
        player.playerListName(null);
    }

    private String teamName(Player player) {
        // Max 16 chars for compatibility: "tc_" + 13 chars of UUID
        return "tc_" + player.getUniqueId().toString().replace("-", "").substring(0, 13);
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    private void loadPlayer(Player player) {
        counts.put(player.getUniqueId(), data.getInt("counts." + player.getUniqueId(), 0));
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
