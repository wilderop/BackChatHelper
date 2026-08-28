package com.wilderop.backchat;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class BackChatHelper extends JavaPlugin implements Listener, PluginMessageListener {

    static final String CHANNEL_IGNORE = "backchat:ignore";
    static final String CHANNEL_NICK = "backchat:nick";
    static final String CHANNEL_MAIL = "backchat:mail";

    private final Map<UUID, UUID> lastReply = new HashMap<>();
    private final Map<UUID, String> lastReplyName = new HashMap<>();
    private final Map<UUID, Long> nickCooldown = new HashMap<>();
    private final Map<UUID, String> playerNicks = new HashMap<>();

    private int cooldownSeconds;
    private int maxNickLength;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();
        cooldownSeconds = getConfig().getInt("nick-cooldown-seconds", 60);
        maxNickLength = getConfig().getInt("max-nick-length", 64);

        getServer().getPluginManager().registerEvents(this, this);

        getCommand("ignore").setExecutor(new IgnoreCommand());
        getCommand("msg").setExecutor(new MsgCommand());
        getCommand("r").setExecutor(new ReplyCommand());
        getCommand("nick").setExecutor(new NickCommand());

        getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL_IGNORE);
        getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL_NICK);
        getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL_MAIL);
        getServer().getMessenger().registerIncomingPluginChannel(this, CHANNEL_MAIL, this);
        getServer().getMessenger().registerIncomingPluginChannel(this, CHANNEL_NICK, this);
        getServer().getMessenger().registerIncomingPluginChannel(this, CHANNEL_IGNORE, this);

        getLogger().info("BackChatHelper enabled.");
    }

    @Override
    public void onDisable() {
        getServer().getMessenger().unregisterOutgoingPluginChannel(this);
        getServer().getMessenger().unregisterIncomingPluginChannel(this);
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] data) {
        String raw = new String(data, StandardCharsets.UTF_8);

        if (CHANNEL_MAIL.equals(channel)) {
            handleMailPayload(player, raw);
            return;
        }
        if (CHANNEL_NICK.equals(channel)) {
            handleNickPush(raw);
            return;
        }
        if (CHANNEL_IGNORE.equals(channel)) {
            handleIgnorePush(raw);
        }
    }

    private void handleMailPayload(Player player, String raw) {
        String[] parts = raw.split("\\|", 3);
        if (parts.length < 2) {
            return;
        }
        switch (parts[0]) {
            case "ACK_SENT" -> {
                String target = parts[1];
                String message = parts.length > 2 ? parts[2] : "";
                player.sendMessage(Component.text("[me → ", NamedTextColor.GRAY)
                        .append(Component.text(target, NamedTextColor.AQUA))
                        .append(Component.text("]: ", NamedTextColor.GRAY))
                        .append(Component.text(message, NamedTextColor.WHITE)));
                lastReplyName.put(player.getUniqueId(), target);
            }
            case "ACK_QUEUED" -> {
                String target = parts[1];
                String message = parts.length > 2 ? parts[2] : "";
                player.sendMessage(Component.text("[me → ", NamedTextColor.GRAY)
                        .append(Component.text(target, NamedTextColor.AQUA))
                        .append(Component.text("]: ", NamedTextColor.GRAY))
                        .append(Component.text(message, NamedTextColor.WHITE))
                        .append(Component.text(" (offline — delivered when they log in)", NamedTextColor.DARK_GRAY)));
                lastReplyName.put(player.getUniqueId(), target);
            }
            case "ACK_FAIL" -> player.sendMessage(Component.text(parts[1], NamedTextColor.RED));
            case "LASTREPLY" -> {
                try {
                    lastReply.put(player.getUniqueId(), UUID.fromString(parts[1]));
                    if (parts.length > 2 && !parts[2].isBlank()) {
                        lastReplyName.put(player.getUniqueId(), parts[2]);
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
            default -> {
            }
        }
    }

    private void handleNickPush(String raw) {
        String[] parts = raw.split("\\|", 3);
        if (parts.length < 3 || !"PUSH".equals(parts[0])) {
            return;
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(parts[1]);
        } catch (IllegalArgumentException e) {
            return;
        }
        String nick = parts[2];
        if ("RESET".equals(nick) || nick.isBlank()) {
            playerNicks.remove(uuid);
            saveNick(uuid, null);
        } else {
            playerNicks.put(uuid, nick);
            saveNick(uuid, nick);
        }
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            applyDisplayName(online);
        }
    }

    private void handleIgnorePush(String raw) {
        String[] parts = raw.split("\\|", 3);
        if (parts.length < 2 || !"PUSH".equals(parts[0])) {
            return;
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(parts[1]);
        } catch (IllegalArgumentException e) {
            return;
        }
        Set<UUID> ignored = new HashSet<>();
        if (parts.length > 2 && !parts[2].isBlank()) {
            for (String token : parts[2].split(",")) {
                try {
                    ignored.add(UUID.fromString(token.trim()));
                } catch (IllegalArgumentException ignoredEx) {
                }
            }
        }
        saveIgnored(uuid, ignored);
    }

    @EventHandler
    public void onAsyncChat(AsyncChatEvent event) {
        Player sender = event.getPlayer();
        UUID senderUuid = sender.getUniqueId();
        event.viewers().removeIf(viewer -> {
            if (viewer instanceof Player recipient) {
                return loadIgnored(recipient.getUniqueId()).contains(senderUuid);
            }
            return false;
        });

        String nickRaw = playerNicks.get(senderUuid);
        if (nickRaw == null || nickRaw.isBlank()) {
            return;
        }
        Component nickComponent = MiniMessage.miniMessage().deserialize(nickRaw);
        event.renderer((source, sourceDisplayName, message, viewer) ->
                Component.text("<")
                        .append(nickComponent)
                        .append(Component.text("> "))
                        .append(message));
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        String savedNick = loadNick(uuid);
        if (savedNick != null && !savedNick.isBlank()) {
            playerNicks.put(uuid, savedNick);
            applyDisplayName(player);
            sendPluginPayload(player, CHANNEL_NICK, uuid + "|" + savedNick);
        }

        for (UUID ignored : loadIgnored(uuid)) {
            sendPluginPayload(player, CHANNEL_IGNORE, uuid + "|" + ignored + "|add");
        }
    }

    private class NickCommand implements TabExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Players only.");
                return true;
            }

            UUID uuid = player.getUniqueId();
            long now = System.currentTimeMillis();
            Long until = nickCooldown.get(uuid);
            if (until != null && until > now && args.length > 0) {
                long remaining = TimeUnit.MILLISECONDS.toSeconds(until - now);
                player.sendMessage(Component.text("Wait " + remaining + "s before changing your nick again.",
                        NamedTextColor.RED));
                return true;
            }

            if (args.length == 0) {
                String current = playerNicks.get(uuid);
                player.sendMessage(Component.text("Current nick: " + (current == null ? "none" : current),
                        NamedTextColor.YELLOW));
                player.sendMessage(Component.text("Usage: /nick <MiniMessage> | /nick reset", NamedTextColor.GRAY));
                return true;
            }

            String input = String.join(" ", args);
            if (input.equalsIgnoreCase("reset") || input.equalsIgnoreCase("off")) {
                playerNicks.remove(uuid);
                saveNick(uuid, null);
                nickCooldown.put(uuid, now + cooldownSeconds * 1000L);
                applyDisplayName(player);
                sendPluginPayload(player, CHANNEL_NICK, uuid + "|RESET");
                player.sendMessage(Component.text("Nickname reset.", NamedTextColor.GREEN));
                return true;
            }

            if (input.length() > maxNickLength) {
                player.sendMessage(Component.text("Nickname too long. Max " + maxNickLength + " characters.",
                        NamedTextColor.RED));
                return true;
            }
            if (input.indexOf('\n') >= 0 || input.indexOf('\r') >= 0 || input.indexOf('\u00a7') >= 0) {
                player.sendMessage(Component.text("Nickname contains illegal characters.", NamedTextColor.RED));
                return true;
            }
            try {
                MiniMessage.miniMessage().deserialize(input);
            } catch (Exception ex) {
                player.sendMessage(Component.text("Invalid MiniMessage nickname.", NamedTextColor.RED));
                return true;
            }

            playerNicks.put(uuid, input);
            saveNick(uuid, input);
            nickCooldown.put(uuid, now + cooldownSeconds * 1000L);
            applyDisplayName(player);
            sendPluginPayload(player, CHANNEL_NICK, uuid + "|" + input);
            player.sendMessage(Component.text("Nickname set: ", NamedTextColor.GREEN)
                    .append(MiniMessage.miniMessage().deserialize(input)));
            return true;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            if (args.length == 1) {
                return filter(List.of("reset", "<gradient:#ff0000:#00ff00>name"), args[0]);
            }
            return Collections.emptyList();
        }
    }

    private class IgnoreCommand implements TabExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Players only.");
                return true;
            }
            if (args.length != 1) {
                player.sendMessage(Component.text("Usage: /ignore <player>", NamedTextColor.RED));
                return true;
            }

            OfflinePlayer target = findOffline(args[0]);
            if (target == null) {
                sendPluginPayload(player, CHANNEL_IGNORE,
                        "NAME|" + player.getUniqueId() + "|" + args[0] + "|toggle");
                player.sendMessage(Component.text("Toggled ignore for " + args[0] + " (network).", NamedTextColor.GREEN));
                return true;
            }
            if (target.getUniqueId().equals(player.getUniqueId())) {
                player.sendMessage(Component.text("You cannot ignore yourself.", NamedTextColor.RED));
                return true;
            }

            UUID playerUuid = player.getUniqueId();
            UUID targetUuid = target.getUniqueId();
            Set<UUID> ignored = loadIgnored(playerUuid);
            boolean adding = !ignored.contains(targetUuid);
            if (adding) {
                ignored.add(targetUuid);
                player.sendMessage(Component.text("Now ignoring " + display(target, args[0]), NamedTextColor.GREEN));
            } else {
                ignored.remove(targetUuid);
                player.sendMessage(Component.text("No longer ignoring " + display(target, args[0]), NamedTextColor.GREEN));
            }
            saveIgnored(playerUuid, ignored);
            sendPluginPayload(player, CHANNEL_IGNORE,
                    playerUuid + "|" + targetUuid + "|" + (adding ? "add" : "remove"));
            return true;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            if (args.length != 1) {
                return Collections.emptyList();
            }
            return onlineNames(args[0]);
        }
    }

    private class MsgCommand implements TabExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Players only.");
                return true;
            }
            if (args.length < 2) {
                player.sendMessage(Component.text("Usage: /msg <player> <message>", NamedTextColor.RED));
                return true;
            }

            String targetName = args[0];
            String message = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
            Player local = Bukkit.getPlayerExact(targetName);
            if (local == null) {
                local = Bukkit.getPlayer(targetName);
            }

            if (local != null) {
                if (loadIgnored(local.getUniqueId()).contains(player.getUniqueId())) {
                    player.sendMessage(Component.text("You cannot message someone who ignores you.", NamedTextColor.RED));
                    return true;
                }
                sendPrivate(player, local, message);
                lastReply.put(local.getUniqueId(), player.getUniqueId());
                lastReply.put(player.getUniqueId(), local.getUniqueId());
                lastReplyName.put(player.getUniqueId(), local.getName());
                lastReplyName.put(local.getUniqueId(), player.getName());
                return true;
            }

            lastReplyName.put(player.getUniqueId(), targetName);
            String payload = "SEND|" + player.getUniqueId() + "|" + player.getName() + "|" + targetName + "|" + message;
            sendPluginPayload(player, CHANNEL_MAIL, payload);
            return true;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            if (args.length == 1) {
                return onlineNames(args[0]);
            }
            return Collections.emptyList();
        }
    }

    private class ReplyCommand implements TabExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Players only.");
                return true;
            }
            if (args.length < 1) {
                player.sendMessage(Component.text("Usage: /r <message>", NamedTextColor.RED));
                return true;
            }

            UUID last = lastReply.get(player.getUniqueId());
            String lastName = lastReplyName.get(player.getUniqueId());
            if (last == null && (lastName == null || lastName.isBlank())) {
                player.sendMessage(Component.text("No one to reply to.", NamedTextColor.RED));
                return true;
            }

            String message = String.join(" ", args);
            Player local = last != null ? Bukkit.getPlayer(last) : null;
            if (local == null && lastName != null) {
                local = Bukkit.getPlayerExact(lastName);
            }
            if (local != null) {
                if (loadIgnored(local.getUniqueId()).contains(player.getUniqueId())) {
                    player.sendMessage(Component.text("You cannot reply to someone who ignores you.", NamedTextColor.RED));
                    return true;
                }
                sendPrivate(player, local, message);
                lastReply.put(local.getUniqueId(), player.getUniqueId());
                lastReply.put(player.getUniqueId(), local.getUniqueId());
                lastReplyName.put(player.getUniqueId(), local.getName());
                lastReplyName.put(local.getUniqueId(), player.getName());
                return true;
            }

            String targetName = lastName;
            if (targetName == null && last != null) {
                OfflinePlayer offline = Bukkit.getOfflinePlayer(last);
                targetName = offline.getName() != null ? offline.getName() : last.toString();
            }
            String payload = "SEND|" + player.getUniqueId() + "|" + player.getName() + "|" + targetName + "|" + message;
            sendPluginPayload(player, CHANNEL_MAIL, payload);
            return true;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            return Collections.emptyList();
        }
    }

    private void sendPrivate(Player from, Player to, String message) {
        Component toTarget = Component.text("[", NamedTextColor.GRAY)
                .append(Component.text(from.getName(), NamedTextColor.AQUA))
                .append(Component.text(" → me]: ", NamedTextColor.GRAY))
                .append(Component.text(message, NamedTextColor.WHITE));
        Component toSender = Component.text("[me → ", NamedTextColor.GRAY)
                .append(Component.text(to.getName(), NamedTextColor.AQUA))
                .append(Component.text("]: ", NamedTextColor.GRAY))
                .append(Component.text(message, NamedTextColor.WHITE));
        to.sendMessage(toTarget);
        from.sendMessage(toSender);
    }

    private void applyDisplayName(Player player) {
        String nick = playerNicks.get(player.getUniqueId());
        if (nick != null && !nick.isBlank()) {
            Component component = MiniMessage.miniMessage().deserialize(nick);
            player.displayName(component);
            player.playerListName(component);
        } else {
            player.displayName(null);
            player.playerListName(null);
        }
    }

    private void sendPluginPayload(Player player, String channel, String payload) {
        player.sendPluginMessage(this, channel, payload.getBytes(StandardCharsets.UTF_8));
    }

    private OfflinePlayer findOffline(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online;
        }
        online = Bukkit.getPlayer(name);
        if (online != null) {
            return online;
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached != null) {
            return cached;
        }
        for (OfflinePlayer offline : Bukkit.getOfflinePlayers()) {
            if (offline.getName() != null && offline.getName().equalsIgnoreCase(name)) {
                return offline;
            }
        }
        return null;
    }

    private String display(OfflinePlayer player, String fallback) {
        return player.getName() != null ? player.getName() : fallback;
    }

    private List<String> onlineNames(String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(lower))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .collect(Collectors.toList());
    }

    private List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(option);
            }
        }
        return out;
    }

    private File playerFile(UUID uuid) {
        File folder = new File(getDataFolder(), "players");
        if (!folder.exists()) {
            folder.mkdirs();
        }
        return new File(folder, uuid + ".yml");
    }

    private Set<UUID> loadIgnored(UUID playerUuid) {
        Set<UUID> ignored = new HashSet<>();
        File file = playerFile(playerUuid);
        if (!file.exists()) {
            return ignored;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String raw : yaml.getStringList("ignored")) {
            try {
                ignored.add(UUID.fromString(raw));
            } catch (IllegalArgumentException ignoredEx) {
            }
        }
        return ignored;
    }

    private void saveIgnored(UUID playerUuid, Set<UUID> ignored) {
        File file = playerFile(playerUuid);
        YamlConfiguration yaml = file.exists() ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
        List<String> list = new ArrayList<>();
        for (UUID uuid : ignored) {
            list.add(uuid.toString());
        }
        yaml.set("ignored", list);
        try {
            yaml.save(file);
        } catch (IOException e) {
            getLogger().warning("Failed to save ignores for " + playerUuid);
        }
    }

    private String loadNick(UUID uuid) {
        File file = playerFile(uuid);
        if (!file.exists()) {
            return null;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        return yaml.getString("nick");
    }

    private void saveNick(UUID uuid, String nick) {
        File file = playerFile(uuid);
        YamlConfiguration yaml = file.exists() ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
        yaml.set("nick", nick);
        try {
            yaml.save(file);
        } catch (IOException e) {
            getLogger().warning("Failed to save nick for " + uuid);
        }
    }
}
