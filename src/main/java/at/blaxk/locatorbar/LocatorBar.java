package at.blaxk.locatorbar;

import org.bukkit.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bstats.bukkit.Metrics;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class LocatorBar extends JavaPlugin implements Listener, TabCompleter {

    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private FileConfiguration config;
    private FileConfiguration messages;
    private Map<UUID, Boolean> playerVisibility = new HashMap<>();
    private Map<UUID, Boolean> playerReceiving = new HashMap<>();
    private Map<UUID, Long> cooldownMap = new HashMap<>();
    private Map<UUID, Long> updateNotificationHidden = new HashMap<>();
    private final String MODRINTH_PROJECT_ID = "KF5O4Zzd";
    private String latestVersion = null;
    private boolean updateAvailable = false;

    @Override
    public void onEnable() {
        this.saveDefaultConfig();
        this.config = this.getConfig();

        createMessagesFile();
        loadPlayerData();

        this.getServer().getPluginManager().registerEvents(this, this);
        this.getCommand("locatorbar").setTabCompleter(this);

        setupPlaceholderAPI();
        setupMetrics();
        checkForUpdates();

        this.getLogger().info("LocatorBar has been enabled successfully!");
    }

    @Override
    public void onDisable() {
        savePlayerData();
        this.getLogger().info("LocatorBar has been disabled!");
    }

    private void checkForUpdates() {

        this.getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                URL url = new URL("https://api.modrinth.com/v2/project/" + MODRINTH_PROJECT_ID + "/version");
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setRequestProperty("User-Agent", "LocatorBar/" + getDescription().getVersion());

                if (connection.getResponseCode() == 200) {
                    InputStreamReader reader = new InputStreamReader(connection.getInputStream());
                    JsonObject response = JsonParser.parseReader(reader).getAsJsonArray().get(0).getAsJsonObject();

                    latestVersion = response.get("version_number").getAsString();
                    String currentVersion = getDescription().getVersion();

                    if (!latestVersion.equals(currentVersion)) {
                        updateAvailable = true;
                        this.getLogger().info("Update Available!");
                        this.getLogger().info("Current: " + currentVersion);
                        this.getLogger().info("Latest: " + latestVersion);
                        this.getLogger().info("Download: https://modrinth.com/plugin/locator-bar");
                    }

                    reader.close();
                }
                connection.disconnect();
            } catch (Exception e) {
                if (config.getBoolean("debug", false)) {
                    this.getLogger().warning("Could not check for updates: " + e.getMessage());
                }
            }
        });
    }

    private void setupPlaceholderAPI() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new LocatorBarPlaceholders(this).register();
            this.getLogger().info("PlaceholderAPI integration enabled!");
        }
    }

    private void setupMetrics() {
        int pluginId = 26290;
        Metrics metrics = new Metrics(this, pluginId);

        this.getLogger().info("bStats metrics enabled!");
    }

    private void createMessagesFile() {
        File messagesFile = new File(getDataFolder(), "messages.yml");
        if (!messagesFile.exists()) {
            saveResource("messages.yml", false);
        }
        messages = YamlConfiguration.loadConfiguration(messagesFile);
    }

    private void loadPlayerData() {
        File dataFile = new File(getDataFolder(), "playerdata.yml");
        if (dataFile.exists()) {
            FileConfiguration data = YamlConfiguration.loadConfiguration(dataFile);
            for (String uuid : data.getKeys(false)) {
                UUID playerUUID = UUID.fromString(uuid);
                playerVisibility.put(playerUUID, data.getBoolean(uuid + ".visible", true));
                playerReceiving.put(playerUUID, data.getBoolean(uuid + ".receiving", true));

                if (data.contains(uuid + ".update-hidden-until")) {
                    long hiddenUntil = data.getLong(uuid + ".update-hidden-until");
                    if (hiddenUntil > System.currentTimeMillis()) {
                        updateNotificationHidden.put(playerUUID, hiddenUntil);
                    }
                }
            }
        }
    }

    private void savePlayerData() {
        File dataFile = new File(getDataFolder(), "playerdata.yml");
        FileConfiguration data = new YamlConfiguration();

        for (Map.Entry<UUID, Boolean> entry : playerVisibility.entrySet()) {
            String uuid = entry.getKey().toString();
            data.set(uuid + ".visible", entry.getValue());
            data.set(uuid + ".receiving", playerReceiving.getOrDefault(entry.getKey(), true));

            if (updateNotificationHidden.containsKey(entry.getKey())) {
                long hiddenUntil = updateNotificationHidden.get(entry.getKey());
                if (hiddenUntil > System.currentTimeMillis()) {
                    data.set(uuid + ".update-hidden-until", hiddenUntil);
                }
            }
        }

        try {
            data.save(dataFile);
        } catch (IOException e) {
            this.getLogger().warning("Could not save player data: " + e.getMessage());
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        boolean isFirstJoin = !playerVisibility.containsKey(uuid);

        if (isFirstJoin) {
            playerVisibility.put(uuid, true);
            playerReceiving.put(uuid, true);

            if (config.getBoolean("show-first-join-message", true)) {
                this.getServer().getScheduler().runTaskLater(this, () -> {
                    player.sendMessage(getMessage("first-join-welcome"));
                    player.sendMessage(getMessage("first-join-info"));
                }, 20L);
            }
        }

        updatePlayerAttributes(player);

        if (player.isOp() && updateAvailable && config.getBoolean("update-checker", true)) {
            Long hiddenUntil = updateNotificationHidden.get(uuid);
            if (hiddenUntil == null || hiddenUntil < System.currentTimeMillis()) {
                this.getServer().getScheduler().runTaskLater(this, () -> {
                    showUpdateNotification(player);
                }, 40L);
            }
        }
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        updatePlayerAttributes(player);
    }

    private void showUpdateNotification(Player player) {
        String currentVersion = getDescription().getVersion();
        String downloadUrl = "https://modrinth.com/plugin/locator-bar/version/" + (latestVersion != null ? latestVersion : "latest");

        Component updateMessage = getMessage("update-available")
                .replaceText(net.kyori.adventure.text.TextReplacementConfig.builder()
                        .matchLiteral("{current}")
                        .replacement(currentVersion)
                        .build())
                .replaceText(net.kyori.adventure.text.TextReplacementConfig.builder()
                        .matchLiteral("{latest}")
                        .replacement(latestVersion != null ? latestVersion : "unknown")
                        .build());

        Component hideButton = getMessage("update-hide-button")
                .replaceText(net.kyori.adventure.text.TextReplacementConfig.builder()
                        .matchLiteral("{download_url}")
                        .replacement(downloadUrl)
                        .build())
                .clickEvent(ClickEvent.runCommand("/locatorbar hideupdate"));

        player.sendMessage(updateMessage);
        player.sendMessage(hideButton);
    }

    private boolean isWorldForceVisible(String worldName) {
        return config.getBoolean("world-settings." + worldName + ".force-visible", false);
    }

    private void updatePlayerAttributes(Player player) {
        UUID uuid = player.getUniqueId();
        String worldName = player.getWorld().getName();

        boolean visible = playerVisibility.getOrDefault(uuid, true);
        boolean receiving = playerReceiving.getOrDefault(uuid, true);

        if (isWorldForceVisible(worldName)) {
            visible = true;
            receiving = true;
        }

        double transmitRange = visible ? config.getDouble("default-transmit-range", 60000000.0) : 0.0;
        double receiveRange;

        if (config.getBoolean("disable-receive-when-hidden", false) && !visible) {
            receiveRange = 0.0;
        } else {
            receiveRange = receiving ? config.getDouble("default-receive-range", 60000000.0) : 0.0;
        }

        try {
            if (player.getAttribute(Attribute.WAYPOINT_TRANSMIT_RANGE) != null) {
                player.getAttribute(Attribute.WAYPOINT_TRANSMIT_RANGE).setBaseValue(transmitRange);
            }
            if (player.getAttribute(Attribute.WAYPOINT_RECEIVE_RANGE) != null) {
                player.getAttribute(Attribute.WAYPOINT_RECEIVE_RANGE).setBaseValue(receiveRange);
            }

            if (config.getBoolean("debug", false)) {
                this.getLogger().info("Updated attributes for " + player.getName() +
                        " - Transmit: " + transmitRange + ", Receive: " + receiveRange +
                        (isWorldForceVisible(worldName) ? " (Force Visible)" : ""));
            }
        } catch (Exception e) {
            this.getLogger().warning("Could not set waypoint attributes for " + player.getName() + ": " + e.getMessage());
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player) && args.length > 0 && !args[0].equalsIgnoreCase("reload")) {
            sender.sendMessage(getMessage("only-players"));
            return true;
        }

        if (command.getName().equalsIgnoreCase("locatorbar")) {
            if (args.length == 0) {
                if (sender instanceof Player) {
                    showHelp((Player) sender);
                } else {
                    sender.sendMessage("Usage: /locatorbar reload");
                }
                return true;
            }

            switch (args[0].toLowerCase()) {
                case "toggle":
                    if (sender instanceof Player) {
                        Player player = (Player) sender;
                        if (checkCooldown(player)) {
                            if (config.getString("control-mode", "combined").equals("combined")) {
                                toggleCombined(player);
                            } else {
                                toggleVisibility(player);
                            }
                            setCooldown(player);
                        }
                    }
                    break;
                case "receive":
                    if (sender instanceof Player) {
                        Player player = (Player) sender;
                        if (config.getString("control-mode", "combined").equals("separate")) {
                            if (checkCooldown(player)) {
                                toggleReceiving(player);
                                setCooldown(player);
                            }
                        } else {
                            player.sendMessage(getMessage("command-not-available"));
                        }
                    }
                    break;
                case "status":
                    if (sender instanceof Player) {
                        showStatus((Player) sender);
                    }
                    break;
                case "player":
                    if (sender.hasPermission("locatorbar.admin")) {
                        handleAdminPlayerCommand(sender, args);
                    } else {
                        sender.sendMessage(getMessage("no-permission"));
                    }
                    break;
                case "hideupdate":
                    if (sender instanceof Player) {
                        Player player = (Player) sender;
                        if (player.isOp()) {
                            hideUpdateNotification(player);
                        }
                    }
                    break;
                case "triggerupdatenotif":
                    if (sender instanceof Player) {
                        Player player = (Player) sender;
                        if (player.isOp()) {
                            latestVersion = "1.1";
                            updateAvailable = true;
                            showUpdateNotification(player);

                            if (config.getBoolean("debug", false)) {
                                String downloadUrl = "https://modrinth.com/plugin/locator-bar/version/" + latestVersion;
                                player.sendMessage(miniMessage.deserialize("<gray>[DEBUG] Download URL: " + downloadUrl));
                            }
                        }
                    }
                    break;
                case "help":
                    if (sender instanceof Player) {
                        showHelp((Player) sender);
                    }
                    break;
                case "reload":
                    if (sender.hasPermission("locatorbar.admin")) {
                        reloadConfig();
                        createMessagesFile();
                        sender.sendMessage(getMessage("config-reloaded"));
                    } else {
                        sender.sendMessage(getMessage("no-permission"));
                    }
                    break;
                default:
                    if (sender instanceof Player) {
                        showHelp((Player) sender);
                    }
                    break;
            }
        }

        return true;
    }

    private void hideUpdateNotification(Player player) {
        UUID uuid = player.getUniqueId();
        long hideUntil = System.currentTimeMillis() + (24 * 60 * 60 * 1000L);
        updateNotificationHidden.put(uuid, hideUntil);
        player.sendMessage(getMessage("update-hidden"));
        savePlayerData();
    }

    private void handleAdminPlayerCommand(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(getMessage("admin-usage"));
            return;
        }

        String playerName = args[1];
        String action = args[2].toLowerCase();

        Player target = Bukkit.getPlayer(playerName);
        if (target == null) {
            sender.sendMessage(getMessage("player-not-found"));
            return;
        }

        switch (action) {
            case "toggle":
                if (config.getString("control-mode", "combined").equals("combined")) {
                    toggleCombinedAdmin(target, sender);
                } else {
                    toggleVisibilityAdmin(target, sender);
                }
                break;
            case "status":
                showStatusAdmin(target, sender);
                break;
            default:
                sender.sendMessage(getMessage("admin-usage"));
                break;
        }
    }

    private boolean checkCooldown(Player player) {
        UUID uuid = player.getUniqueId();
        long currentTime = System.currentTimeMillis();

        if (cooldownMap.containsKey(uuid)) {
            long lastUsed = cooldownMap.get(uuid);
            int cooldownSeconds = getCooldownForPlayer(player);
            long cooldownMillis = cooldownSeconds * 1000L;

            if (currentTime - lastUsed < cooldownMillis) {
                double remainingSeconds = (cooldownMillis - (currentTime - lastUsed)) / 1000.0;
                String formattedTime = String.format("%.2f", remainingSeconds);
                player.sendMessage(getMessage("cooldown-active").replaceText(
                        net.kyori.adventure.text.TextReplacementConfig.builder()
                                .matchLiteral("{time}")
                                .replacement(formattedTime)
                                .build()
                ));
                return false;
            }
        }

        return true;
    }

    private void setCooldown(Player player) {
        cooldownMap.put(player.getUniqueId(), System.currentTimeMillis());
    }

    private int getCooldownForPlayer(Player player) {
        if (player.hasPermission("locatorbar.cooldown.bypass")) {
            return 0;
        }

        for (int i = 0; i <= 300; i++) {
            if (player.hasPermission("locatorbar.cooldown." + i)) {
                return i;
            }
        }

        return config.getInt("default-cooldown", 5);
    }

    private void playSound(Player player, String soundType) {
        if (!config.getBoolean("sounds.enabled", true)) {
            return;
        }

        String soundName = config.getString("sounds." + soundType, "");
        if (soundName.isEmpty()) {
            return;
        }

        try {
            String raw = config.getString("sounds.name", "entity.player.levelup");
            String keyString = raw.startsWith("minecraft:") ? raw : "minecraft:" + raw.toLowerCase();
            NamespacedKey key = NamespacedKey.fromString(keyString);

            if (key == null) {
                Bukkit.getLogger().warning("Ungültiger Sound-Key: " + raw);
                return;
            }
            Sound sound = Registry.SOUNDS.get(key);
            if (sound == null) {
                Bukkit.getLogger().warning("Sound nicht im Registry gefunden: " + keyString);
                return;
            }

            float volume = (float) config.getDouble("sounds.volume", 1.0);
            float pitch = (float) config.getDouble("sounds.pitch", 1.0);
            player.playSound(player.getLocation(), sound, SoundCategory.MASTER, volume, pitch);


        } catch (Exception e) {
            if (config.getBoolean("debug", false)) {
                this.getLogger().warning("Invalid sound: " + soundName + " - " + e.getMessage());
            }
        }
    }

    private void toggleCombined(Player player) {
        UUID uuid = player.getUniqueId();
        String worldName = player.getWorld().getName();

        if (isWorldForceVisible(worldName)) {
            player.sendMessage(getMessage("world-force-visible"));
            return;
        }

        boolean currentState = playerVisibility.getOrDefault(uuid, true);
        boolean newState = !currentState;

        playerVisibility.put(uuid, newState);

        if (config.getBoolean("disable-receive-when-hidden", false)) {
            playerReceiving.put(uuid, newState);
        } else {
            playerReceiving.put(uuid, newState);
        }

        updatePlayerAttributes(player);

        String messageKey = newState ? "visibility-enabled-combined" : "visibility-disabled-combined";
        player.sendMessage(getMessage(messageKey));

        String soundType = newState ? "enable" : "disable";
        playSound(player, soundType);
    }

    private void toggleCombinedAdmin(Player target, CommandSender admin) {
        UUID uuid = target.getUniqueId();
        String worldName = target.getWorld().getName();

        if (isWorldForceVisible(worldName)) {
            admin.sendMessage(getMessage("admin-world-force-visible").replaceText(
                    net.kyori.adventure.text.TextReplacementConfig.builder()
                            .matchLiteral("{player}")
                            .replacement(target.getName())
                            .build()
            ));
            return;
        }

        boolean currentState = playerVisibility.getOrDefault(uuid, true);
        boolean newState = !currentState;

        playerVisibility.put(uuid, newState);

        if (config.getBoolean("disable-receive-when-hidden", false)) {
            playerReceiving.put(uuid, newState);
        } else {
            playerReceiving.put(uuid, newState);
        }

        updatePlayerAttributes(target);

        String messageKey = newState ? "admin-visibility-enabled" : "admin-visibility-disabled";
        admin.sendMessage(getMessage(messageKey).replaceText(
                net.kyori.adventure.text.TextReplacementConfig.builder()
                        .matchLiteral("{player}")
                        .replacement(target.getName())
                        .build()
        ));

        String targetMessageKey = newState ? "visibility-enabled-combined" : "visibility-disabled-combined";
        target.sendMessage(getMessage(targetMessageKey));

        String soundType = newState ? "enable" : "disable";
        playSound(target, soundType);
    }

    private void toggleVisibility(Player player) {
        UUID uuid = player.getUniqueId();
        String worldName = player.getWorld().getName();

        if (isWorldForceVisible(worldName)) {
            player.sendMessage(getMessage("world-force-visible"));
            return;
        }

        boolean currentState = playerVisibility.getOrDefault(uuid, true);
        boolean newState = !currentState;

        playerVisibility.put(uuid, newState);
        updatePlayerAttributes(player);

        String messageKey = newState ? "visibility-enabled" : "visibility-disabled";
        player.sendMessage(getMessage(messageKey));

        String soundType = newState ? "enable" : "disable";
        playSound(player, soundType);
    }

    private void toggleVisibilityAdmin(Player target, CommandSender admin) {
        UUID uuid = target.getUniqueId();
        String worldName = target.getWorld().getName();

        if (isWorldForceVisible(worldName)) {
            admin.sendMessage(getMessage("admin-world-force-visible").replaceText(
                    net.kyori.adventure.text.TextReplacementConfig.builder()
                            .matchLiteral("{player}")
                            .replacement(target.getName())
                            .build()
            ));
            return;
        }

        boolean currentState = playerVisibility.getOrDefault(uuid, true);
        boolean newState = !currentState;

        playerVisibility.put(uuid, newState);
        updatePlayerAttributes(target);

        String messageKey = newState ? "admin-visibility-enabled" : "admin-visibility-disabled";
        admin.sendMessage(getMessage(messageKey).replaceText(
                net.kyori.adventure.text.TextReplacementConfig.builder()
                        .matchLiteral("{player}")
                        .replacement(target.getName())
                        .build()
        ));

        String targetMessageKey = newState ? "visibility-enabled" : "visibility-disabled";
        target.sendMessage(getMessage(targetMessageKey));

        String soundType = newState ? "enable" : "disable";
        playSound(target, soundType);
    }

    private void toggleReceiving(Player player) {
        UUID uuid = player.getUniqueId();
        boolean currentState = playerReceiving.getOrDefault(uuid, true);
        boolean newState = !currentState;

        playerReceiving.put(uuid, newState);
        updatePlayerAttributes(player);

        String messageKey = newState ? "receiving-enabled" : "receiving-disabled";
        player.sendMessage(getMessage(messageKey));

        String soundType = newState ? "enable" : "disable";
        playSound(player, soundType);
    }

    private void showStatus(Player player) {
        UUID uuid = player.getUniqueId();
        String worldName = player.getWorld().getName();
        boolean visible = playerVisibility.getOrDefault(uuid, true);
        boolean receiving = playerReceiving.getOrDefault(uuid, true);

        Component visibilityStatus = visible ? getMessage("status-visible") : getMessage("status-hidden");
        Component receivingStatus = receiving ? getMessage("status-receiving") : getMessage("status-not-receiving");

        player.sendMessage(getMessage("status-header"));
        player.sendMessage(visibilityStatus);

        if (config.getString("control-mode", "combined").equals("separate")) {
            player.sendMessage(receivingStatus);
        } else {
            player.sendMessage(getMessage("status-combined"));
        }

        if (isWorldForceVisible(worldName)) {
            player.sendMessage(getMessage("status-world-force"));
        }
    }

    private void showStatusAdmin(Player target, CommandSender admin) {
        UUID uuid = target.getUniqueId();
        String worldName = target.getWorld().getName();
        boolean visible = playerVisibility.getOrDefault(uuid, true);
        boolean receiving = playerReceiving.getOrDefault(uuid, true);

        admin.sendMessage(getMessage("admin-status-header").replaceText(
                net.kyori.adventure.text.TextReplacementConfig.builder()
                        .matchLiteral("{player}")
                        .replacement(target.getName())
                        .build()
        ));

        String visibilityKey = visible ? "admin-status-visible" : "admin-status-hidden";
        String receivingKey = receiving ? "admin-status-receiving" : "admin-status-not-receiving";

        admin.sendMessage(getMessage(visibilityKey));
        admin.sendMessage(getMessage(receivingKey));

        if (isWorldForceVisible(worldName)) {
            admin.sendMessage(getMessage("admin-status-world-force"));
        }
    }

    private void showHelp(Player player) {
        String controlMode = config.getString("control-mode", "combined");

        if (controlMode.equals("combined")) {
            player.sendMessage(getMessage("help-header-combined"));
            player.sendMessage(getMessage("help-toggle-combined"));
            player.sendMessage(getMessage("help-status-combined"));
            player.sendMessage(getMessage("help-help-combined"));

            if (player.hasPermission("locatorbar.admin")) {
                player.sendMessage(getMessage("help-player-combined"));
                player.sendMessage(getMessage("help-reload-combined"));
            }
        } else {
            player.sendMessage(getMessage("help-header"));
            player.sendMessage(getMessage("help-toggle"));
            player.sendMessage(getMessage("help-receive"));
            player.sendMessage(getMessage("help-status"));
            player.sendMessage(getMessage("help-help"));

            if (player.hasPermission("locatorbar.admin")) {
                player.sendMessage(getMessage("help-player"));
                player.sendMessage(getMessage("help-reload"));
            }
        }
    }

    private Component getMessage(String key) {
        String locale = config.getString("language", "en");
        String messageKey = locale + "." + key;

        if (!messages.contains(messageKey)) {
            messageKey = "en." + key;
        }

        String message = messages.getString(messageKey, "<red>Message not found: " + key);

        String prefix = messages.getString(locale + ".prefix", messages.getString("en.prefix", ""));
        message = message.replace("<prefix>", prefix);

        return miniMessage.deserialize(message);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> suggestions = new ArrayList<>();

        if (args.length == 1) {
            String controlMode = config.getString("control-mode", "combined");
            suggestions.add("toggle");
            suggestions.add("help");

            if (controlMode.equals("separate")) {
                suggestions.add("receive");
            }

            suggestions.add("status");

            if (sender.hasPermission("locatorbar.admin")) {
                suggestions.add("player");
                suggestions.add("reload");
            }

            String input = args[0].toLowerCase();
            suggestions.removeIf(suggestion -> !suggestion.toLowerCase().startsWith(input));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("player") && sender.hasPermission("locatorbar.admin")) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                suggestions.add(player.getName());
            }

            String input = args[1].toLowerCase();
            suggestions.removeIf(suggestion -> !suggestion.toLowerCase().startsWith(input));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("player") && sender.hasPermission("locatorbar.admin")) {
            suggestions.addAll(Arrays.asList("toggle", "status"));

            String input = args[2].toLowerCase();
            suggestions.removeIf(suggestion -> !suggestion.toLowerCase().startsWith(input));
        }

        return suggestions;
    }

    public boolean isPlayerVisible(UUID playerUUID) {
        return playerVisibility.getOrDefault(playerUUID, true);
    }

    public boolean isPlayerReceiving(UUID playerUUID) {
        return playerReceiving.getOrDefault(playerUUID, true);
    }

    public String getPlayerStatus(UUID playerUUID) {
        boolean visible = isPlayerVisible(playerUUID);
        boolean receiving = isPlayerReceiving(playerUUID);

        if (visible && receiving) {
            return "visible_receiving";
        } else if (visible && !receiving) {
            return "visible_not_receiving";
        } else if (!visible && receiving) {
            return "hidden_receiving";
        } else {
            return "hidden_not_receiving";
        }
    }

    public class LocatorBarPlaceholders extends PlaceholderExpansion {

        private final LocatorBar plugin;

        public LocatorBarPlaceholders(LocatorBar plugin) {
            this.plugin = plugin;
        }

        @Override
        public boolean persist() {
            return true;
        }

        @Override
        public boolean canRegister() {
            return true;
        }

        @Override
        public String getAuthor() {
            return plugin.getDescription().getAuthors().toString();
        }

        @Override
        public String getIdentifier() {
            return "locatorbar";
        }

        @Override
        public String getVersion() {
            return plugin.getDescription().getVersion();
        }

        @Override
        public String onPlaceholderRequest(Player player, String params) {
            if (player == null) {
                return "";
            }

            UUID uuid = player.getUniqueId();

            switch (params.toLowerCase()) {
                case "visible":
                    return String.valueOf(plugin.isPlayerVisible(uuid));
                case "receiving":
                    return String.valueOf(plugin.isPlayerReceiving(uuid));
                case "status":
                    return plugin.getPlayerStatus(uuid);
                default:
                    return null;
            }
        }
    }
}