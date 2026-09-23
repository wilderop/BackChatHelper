package com.example.backchathelper;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileReader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class BackChatHelper extends JavaPlugin implements Listener {

    private final Map<UUID, Long> nickCooldown = new HashMap<>();
    private final Map<UUID, Set<UUID>> ignoreCache = new ConcurrentHashMap<>();
    private int cooldownSeconds;
    private int maxNickLength;
    private RedisSync redis;
    private static final int MAX_MESSAGE_LENGTH = 256;

    private static final String CHANNEL_IGNORE_UPDATE = "backchat:ignore";
    private static final String CHANNEL_NICK = "backchat:nick";

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();
        cooldownSeconds = getConfig().getInt("nick-cooldown-seconds", 60);
        maxNickLength = getConfig().getInt("max-nick-length", 64);

        getServer().getPluginManager().registerEvents(this, this);
        getCommand("ignore").setExecutor(new IgnoreCommand());
        getCommand("msg").setExecutor(new MsgCommand());
        getCommand("r").setExecutor(new RCommand());
        getCommand("nick").setExecutor(new NickCommand());

        getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL_IGNORE_UPDATE);
        getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL_NICK);

        String uri = RedisSync.parseUriOrEmpty(this);
        if (!uri.isBlank()) {
            try {
                String serverName = getConfig().getString("server-name", "unknown");
                redis = new RedisSync(getLogger(), uri, serverName);
                redis.start(event -> Bukkit.getScheduler().runTask(this, () -> onRedisEvent(event)));
                migrateLocalFiles();
            } catch (Exception e) {
                redis = null;
                getLogger().severe("Redis failed; chat helper is local-only: " + e.getMessage());
            }
        } else {
            getLogger().warning("No redis-uri set; ignores/nicks/msg stay local to this server.");
        }

        getServer().getScheduler().runTaskTimer(this, () -> {
            if (redis == null) {
                return;
            }
            for (Player player : Bukkit.getOnlinePlayers()) {
                redis.setLocation(player.getUniqueId(), player.getName());
            }
        }, 20L, 20L * 30);

        getLogger().info("BackChatHelper 1.1.3 enabled (redis=" + (redis != null)
                + ", mailMax=" + RedisSync.MAX_MAIL + ")");
    }

    @Override
    public void onDisable() {
        if (redis != null) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                redis.clearLocation(player.getUniqueId());
            }
            redis.stop();
            redis = null;
        }
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, CHANNEL_IGNORE_UPDATE);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, CHANNEL_NICK);
    }

    private void onRedisEvent(RedisSync.Event event) {
        if (event == null || event.type == null) {
            return;
        }
        if ("msg".equals(event.type)) {
            UUID to = parseUuid(event.extraUuid);
            UUID from = parseUuid(event.uuid);
            if (to == null || from == null || event.text == null) {
                return;
            }
            Player target = Bukkit.getPlayer(to);
            if (target == null) {
                return;
            }
            String fromName = event.name != null ? event.name : "Unknown";
            Component msgToTarget = Component.text("[", NamedTextColor.GRAY)
                    .append(Component.text(fromName, NamedTextColor.AQUA))
                    .append(Component.text(" → me]: ", NamedTextColor.GRAY))
                    .append(Component.text(event.text, NamedTextColor.WHITE));
            target.sendMessage(msgToTarget);
            if (redis != null) {
                redis.setReply(to, from);
            }
        } else if ("ignore".equals(event.type)) {
            UUID ignorer = parseUuid(event.uuid);
            UUID ignored = parseUuid(event.extraUuid);
            if (ignorer == null || ignored == null) {
                return;
            }
            Set<UUID> set = ignoreCache.computeIfAbsent(ignorer, k -> ConcurrentHashMap.newKeySet());
            if ("add".equals(event.action)) {
                set.add(ignored);
            } else {
                set.remove(ignored);
            }
        } else if ("nick".equals(event.type)) {
            UUID uuid = parseUuid(event.uuid);
            if (uuid == null) {
                return;
            }
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                applyNick(player, "RESET".equals(event.text) ? null : event.text);
            }
        }
    }

    private class NickCommand implements org.bukkit.command.CommandExecutor {
        @Override
        public boolean onCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command cmd,
                                 String label, String[] args) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Only players can use this command.");
                return true;
            }

            UUID uuid = player.getUniqueId();
            long now = System.currentTimeMillis();
            if (nickCooldown.getOrDefault(uuid, 0L) > now) {
                long remaining = TimeUnit.MILLISECONDS.toSeconds(nickCooldown.get(uuid) - now);
                player.sendMessage(Component.text("You must wait " + remaining + " seconds before changing your nick again.", NamedTextColor.RED));
                return true;
            }

            if (args.length == 0) {
                String current = redis != null ? redis.getNick(uuid) : null;
                player.sendMessage(Component.text("Your current nick: " + (current == null ? "none" : current), NamedTextColor.YELLOW));
                player.sendMessage(Component.text("Usage: /nick <MiniMessage> | /nick reset", NamedTextColor.GRAY));
                return true;
            }

            String input = String.join(" ", args);
            if (input.equalsIgnoreCase("reset") || input.equalsIgnoreCase("off")) {
                if (redis != null) {
                    redis.setNick(uuid, null);
                }
                applyNick(player, null);
                sendNickToProxy(player, null);
                player.sendMessage(Component.text("Nickname reset.", NamedTextColor.GREEN));
                return true;
            }

            if (input.length() > maxNickLength) {
                player.sendMessage(Component.text("Nickname too long! Maximum " + maxNickLength + " characters.", NamedTextColor.RED));
                return true;
            }

            nickCooldown.put(uuid, now + (cooldownSeconds * 1000L));
            if (redis != null) {
                redis.setNick(uuid, input);
            }
            applyNick(player, input);
            sendNickToProxy(player, input);
            player.sendMessage(Component.text("Nickname set successfully!", NamedTextColor.GREEN));
            return true;
        }
    }

    private class IgnoreCommand implements org.bukkit.command.CommandExecutor {
        @Override
        public boolean onCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command cmd,
                                 String label, String[] args) {
            if (!(sender instanceof Player player)) {
                return true;
            }
            if (args.length != 1) {
                player.sendMessage(Component.text("Usage: /ignore <player>", NamedTextColor.RED));
                return true;
            }

            Resolved target = resolvePlayer(args[0]);
            if (target == null) {
                player.sendMessage(Component.text("Player not found: " + args[0], NamedTextColor.RED));
                return true;
            }
            Player online = Bukkit.getPlayer(target.uuid);
            if (online != null && online.isOp()) {
                player.sendMessage(Component.text("You cannot ignore ops.", NamedTextColor.RED));
                return true;
            }

            Set<UUID> ignored = ignoresOf(player.getUniqueId());
            boolean add = !ignored.contains(target.uuid);
            if (redis != null) {
                redis.setIgnore(player.getUniqueId(), target.uuid, add);
            }
            Set<UUID> cached = ignoreCache.computeIfAbsent(player.getUniqueId(), k -> ConcurrentHashMap.newKeySet());
            if (add) {
                cached.add(target.uuid);
            } else {
                cached.remove(target.uuid);
            }
            sendIgnoreToProxy(player, target.uuid, add);
            if (add) {
                player.sendMessage(Component.text("Now ignoring " + target.name, NamedTextColor.GREEN));
            } else {
                player.sendMessage(Component.text("No longer ignoring " + target.name, NamedTextColor.GREEN));
            }
            return true;
        }
    }

    private class MsgCommand implements org.bukkit.command.CommandExecutor {
        @Override
        public boolean onCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command cmd,
                                 String label, String[] args) {
            if (!(sender instanceof Player player)) {
                return true;
            }
            if (args.length < 2) {
                player.sendMessage(Component.text("Usage: /msg <player> <message>", NamedTextColor.RED));
                return true;
            }
            sendPrivate(player, args[0], String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)));
            return true;
        }
    }

    private class RCommand implements org.bukkit.command.CommandExecutor {
        @Override
        public boolean onCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command cmd,
                                 String label, String[] args) {
            if (!(sender instanceof Player player)) {
                return true;
            }
            if (args.length < 1) {
                player.sendMessage(Component.text("Usage: /r <message>", NamedTextColor.RED));
                return true;
            }
            UUID last = redis != null ? redis.getReply(player.getUniqueId()) : null;
            if (last == null) {
                player.sendMessage(Component.text("No one to reply to.", NamedTextColor.RED));
                return true;
            }
            String name = redis != null ? redis.lookupUuid(last) : null;
            if (name == null) {
                Player online = Bukkit.getPlayer(last);
                name = online != null ? online.getName() : last.toString();
            }
            sendPrivate(player, name, String.join(" ", args));
            return true;
        }
    }

    private void sendPrivate(Player player, String targetName, String message) {
        if (message != null && message.length() > MAX_MESSAGE_LENGTH) {
            player.sendMessage(Component.text("Message too long (max " + MAX_MESSAGE_LENGTH + " characters).", NamedTextColor.RED));
            return;
        }
        Resolved target = resolvePlayer(targetName);
        if (target == null) {
            player.sendMessage(Component.text("Player not found: " + targetName, NamedTextColor.RED));
            return;
        }
        if (target.uuid.equals(player.getUniqueId())) {
            player.sendMessage(Component.text("You cannot message yourself.", NamedTextColor.RED));
            return;
        }
        if (redis != null) {
            Set<UUID> ignoredByTarget = ignoresOf(target.uuid);
            if (ignoredByTarget.contains(player.getUniqueId())) {
                player.sendMessage(Component.text("You cannot message someone who ignores you.", NamedTextColor.RED));
                return;
            }
            boolean here = Bukkit.getPlayer(target.uuid) != null;
            if (here || redis.isOnline(target.uuid)) {
                redis.setReply(target.uuid, player.getUniqueId());
                redis.publishMsg(player.getUniqueId(), player.getName(), target.uuid, message);
            } else if (!redis.queueMail(target.uuid, player.getUniqueId(), player.getName(), message)) {
                player.sendMessage(Component.text("Could not queue that message.", NamedTextColor.RED));
                return;
            } else {
                echoPrivate(player, target.name, message);
                player.sendMessage(Component.text("They're offline. They'll see it when they join.", NamedTextColor.YELLOW));
                return;
            }
        } else {
            Player online = Bukkit.getPlayer(target.uuid);
            if (online == null) {
                player.sendMessage(Component.text("Player not found or offline: " + target.name, NamedTextColor.RED));
                return;
            }
            online.sendMessage(Component.text("[", NamedTextColor.GRAY)
                    .append(Component.text(player.getName(), NamedTextColor.AQUA))
                    .append(Component.text(" → me]: ", NamedTextColor.GRAY))
                    .append(Component.text(message, NamedTextColor.WHITE)));
        }

        echoPrivate(player, target.name, message);
    }

    private static void echoPrivate(Player player, String targetName, String message) {
        player.sendMessage(Component.text("[me → ", NamedTextColor.GRAY)
                .append(Component.text(targetName, NamedTextColor.AQUA))
                .append(Component.text("]: ", NamedTextColor.GRAY))
                .append(Component.text(message, NamedTextColor.WHITE)));
    }

    private void deliverMail(Player player) {
        if (redis == null || player == null || !player.isOnline()) {
            return;
        }
        List<RedisSync.Mail> inbox = redis.takeMail(player.getUniqueId());
        if (inbox.isEmpty()) {
            return;
        }
        String label = inbox.size() == 1 ? " offline message:" : " offline messages:";
        player.sendMessage(Component.text("You have " + inbox.size() + label, NamedTextColor.GOLD));
        UUID lastFrom = null;
        for (RedisSync.Mail mail : inbox) {
            String fromName = mail.fromName != null ? mail.fromName : "Unknown";
            player.sendMessage(Component.text("[", NamedTextColor.GRAY)
                    .append(Component.text(fromName, NamedTextColor.AQUA))
                    .append(Component.text(" → me]: ", NamedTextColor.GRAY))
                    .append(Component.text(mail.text == null ? "" : mail.text, NamedTextColor.WHITE)));
            lastFrom = parseUuid(mail.from);
        }
        if (lastFrom != null) {
            redis.setReply(player.getUniqueId(), lastFrom);
        }
    }

    @EventHandler
    public void onAsyncChat(AsyncChatEvent event) {
        Player sender = event.getPlayer();
        UUID senderUuid = sender.getUniqueId();
        event.viewers().removeIf(viewer -> {
            if (viewer instanceof Player recipient) {
                return ignoresOf(recipient.getUniqueId()).contains(senderUuid);
            }
            return false;
        });
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        event.joinMessage(null);
        Player player = event.getPlayer();
        if (redis != null) {
            redis.setLocation(player.getUniqueId(), player.getName());
            String nick = redis.getNick(player.getUniqueId());
            applyNick(player, nick);
            if (nick != null && !nick.isBlank() && !"RESET".equals(nick)) {
                sendNickToProxy(player, nick);
            }
            Set<UUID> ignored = redis.getIgnores(player.getUniqueId());
            ignoreCache.put(player.getUniqueId(), ConcurrentHashMap.newKeySet());
            ignoreCache.get(player.getUniqueId()).addAll(ignored);
            for (UUID id : ignored) {
                sendIgnoreToProxy(player, id, true);
            }
            getServer().getScheduler().runTaskLater(this, () -> deliverMail(player), 20L);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        event.quitMessage(null);
        if (redis != null) {
            redis.clearLocation(event.getPlayer().getUniqueId());
        }
    }

    private Set<UUID> ignoresOf(UUID uuid) {
        Set<UUID> cached = ignoreCache.get(uuid);
        if (cached != null) {
            return cached;
        }
        if (redis != null) {
            Set<UUID> loaded = redis.getIgnores(uuid);
            Set<UUID> set = ConcurrentHashMap.newKeySet();
            set.addAll(loaded);
            ignoreCache.put(uuid, set);
            return set;
        }
        return Set.of();
    }

    private Resolved resolvePlayer(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online == null) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().equalsIgnoreCase(name)) {
                    online = p;
                    break;
                }
            }
        }
        if (online != null) {
            return new Resolved(online.getUniqueId(), online.getName());
        }
        if (redis != null) {
            UUID uuid = redis.lookupName(name);
            if (uuid != null) {
                String stored = redis.lookupUuid(uuid);
                return new Resolved(uuid, stored != null ? stored : name);
            }
        }
        // Never call Bukkit.getOfflinePlayers()/getName() here. That iterates every
        // playerdata file (~16k on survival) and reads NBT on the server thread,
        // which watchdog-dumped at 10s+ and collapsed TPS/view-distance.
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached != null && cached.getName() != null) {
            if (redis != null) {
                redis.rememberName(cached.getUniqueId(), cached.getName());
            }
            return new Resolved(cached.getUniqueId(), cached.getName());
        }
        return null;
    }

    private void applyNick(Player player, String nick) {
        if (nick != null && !nick.isEmpty() && !"RESET".equals(nick)) {
            Component nickComponent = MiniMessage.miniMessage().deserialize(nick);
            player.displayName(nickComponent);
            player.playerListName(nickComponent);
        } else {
            player.displayName(null);
            player.playerListName(null);
        }
    }

    private void sendNickToProxy(Player player, String nick) {
        String payload = player.getUniqueId() + "|" + (nick == null || nick.isBlank() ? "RESET" : nick);
        player.sendPluginMessage(this, CHANNEL_NICK, payload.getBytes());
    }

    private void sendIgnoreToProxy(Player player, UUID ignored, boolean add) {
        String payload = player.getUniqueId() + "|" + ignored + "|" + (add ? "add" : "remove");
        player.sendPluginMessage(this, CHANNEL_IGNORE_UPDATE, payload.getBytes());
    }

    @SuppressWarnings("unchecked")
    private void migrateLocalFiles() {
        File folder = new File(getDataFolder(), "players");
        if (!folder.isDirectory() || redis == null) {
            return;
        }
        File[] files = folder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) {
            return;
        }
        int nicks = 0;
        int ignores = 0;
        Yaml yaml = new Yaml();
        for (File file : files) {
            String id = file.getName().replace(".yml", "");
            UUID uuid;
            try {
                uuid = UUID.fromString(id);
            } catch (Exception e) {
                continue;
            }
            try (FileReader reader = new FileReader(file)) {
                Object loaded = yaml.load(reader);
                if (!(loaded instanceof Map<?, ?> map)) {
                    continue;
                }
                Object nick = map.get("nick");
                if (nick != null && !String.valueOf(nick).isBlank() && !"null".equals(String.valueOf(nick))) {
                    redis.importNick(uuid, String.valueOf(nick));
                    nicks++;
                }
                Object ignoredObj = map.get("ignored");
                if (ignoredObj instanceof List<?> list) {
                    for (Object item : list) {
                        try {
                            redis.importIgnore(uuid, UUID.fromString(String.valueOf(item)));
                            ignores++;
                        } catch (Exception ignored) {
                        }
                    }
                }
            } catch (Exception e) {
                getLogger().warning("Could not migrate " + file.getName() + ": " + e.getMessage());
            }
        }
        if (nicks > 0 || ignores > 0) {
            getLogger().info("Migrated local helper data into Redis (" + nicks + " nicks, " + ignores + " ignore entries)");
        }
    }

    private UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private static final class Resolved {
        final UUID uuid;
        final String name;

        Resolved(UUID uuid, String name) {
            this.uuid = uuid;
            this.name = name;
        }
    }
}
