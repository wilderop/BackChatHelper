package com.example.backchathelper.fabric;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.CommandNode;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class BackChatHelperFabric implements ModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("BackChatHelper");
    private static final int NICK_COOLDOWN_SECONDS = 60;
    private static final int MAX_NICK_LENGTH = 64;
    private static final int MAX_MESSAGE_LENGTH = 256;

    static final PluginBytesPayload.TypeHolder IGNORE =
            PluginBytesPayload.TypeHolder.of("backchat:ignore");
    static final PluginBytesPayload.TypeHolder NICK =
            PluginBytesPayload.TypeHolder.of("backchat:nick");

    private final Map<UUID, Long> nickCooldown = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> ignoreCache = new ConcurrentHashMap<>();
    private RedisSync redis;
    private MinecraftServer server;
    private int locTicks;

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.clientboundPlay().register(IGNORE.type, IGNORE.codec);
        PayloadTypeRegistry.clientboundPlay().register(NICK.type, NICK.codec);

        redis = RedisSync.fromDefaults(LOG);
        try {
            redis.start(this::onRedisEvent);
        } catch (Throwable e) {
            redis = null;
            LOG.error("Redis failed; BackChatHelper is local-only: {}", e.toString());
        }

        ServerLifecycleEvents.SERVER_STARTED.register(srv -> {
            this.server = srv;
            CommandDispatcher<CommandSourceStack> dispatcher = srv.getCommands().getDispatcher();
            // Vanilla /msg /tell /w only see this server. Replace them so Redis lookup works.
            for (String name : new String[]{"msg", "tell", "w", "whisper", "m", "r", "reply", "ignore", "nick"}) {
                removeLiteral(dispatcher, name);
            }
            registerCommands(dispatcher);
            srv.getPlayerList().getPlayers().forEach(this::onJoin);
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, srv) -> {
            this.server = srv;
            ServerPlayer player = handler.player;
            srv.execute(() -> onJoin(player));
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, srv) -> {
            if (redis != null) {
                redis.clearLocation(handler.player.getUUID());
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(srv -> {
            if (redis != null) {
                for (ServerPlayer p : srv.getPlayerList().getPlayers()) {
                    redis.clearLocation(p.getUUID());
                }
                redis.stop();
                redis = null;
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(srv -> {
            this.server = srv;
            if (redis == null) return;
            locTicks++;
            if (locTicks < 20 * 30) return;
            locTicks = 0;
            for (ServerPlayer p : srv.getPlayerList().getPlayers()) {
                redis.setLocation(p.getUUID(), p.getGameProfile().name());
            }
        });
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
            String text = message.decoratedContent().getString();
            MinecraftServer srv = sender.level().getServer();
            if (srv == null) return true;
            Component line = Component.literal("<")
                    .append(nickComponent(sender))
                    .append(Component.literal("> " + text));
            sender.sendSystemMessage(line);
            for (ServerPlayer p : srv.getPlayerList().getPlayers()) {
                if (p.getUUID().equals(sender.getUUID())) continue;
                if (ignoresOf(p.getUUID()).contains(sender.getUUID())) continue;
                p.sendSystemMessage(line);
            }
            return false;
        });
        ServerMessageEvents.ALLOW_GAME_MESSAGE.register((server, message, overlay) -> {
            if (overlay) {
                return true;
            }
            String s = message.getString();
            return !s.endsWith(" joined the game") && !s.endsWith(" left the game");
        });

        LOG.info("BackChatHelper Fabric 1.2.4 enabled (redis={}, mailMax={})", redis != null, RedisSync.MAX_MAIL);
    }

    private void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("ignore")
                .then(Commands.argument("player", StringArgumentType.word())
                        .executes(this::cmdIgnore)));
        for (String alias : new String[]{"msg", "m", "tell", "whisper", "w"}) {
            dispatcher.register(Commands.literal(alias)
                    .then(Commands.argument("player", StringArgumentType.word())
                            .then(Commands.argument("message", StringArgumentType.greedyString())
                                    .executes(this::cmdMsg))));
        }
        dispatcher.register(Commands.literal("r")
                .then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(this::cmdReply)));
        dispatcher.register(Commands.literal("reply")
                .then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(this::cmdReply)));
        dispatcher.register(Commands.literal("nick")
                .executes(this::cmdNickShow)
                .then(Commands.argument("value", StringArgumentType.greedyString())
                        .executes(this::cmdNickSet)));
    }

    @SuppressWarnings("unchecked")
    private static void removeLiteral(CommandDispatcher<CommandSourceStack> dispatcher, String name) {
        CommandNode<CommandSourceStack> root = dispatcher.getRoot();
        try {
            for (String field : new String[]{"children", "literals"}) {
                Field f = CommandNode.class.getDeclaredField(field);
                f.setAccessible(true);
                ((Map<String, ?>) f.get(root)).remove(name);
            }
        } catch (Exception e) {
            LOG.warn("Could not replace /{}: {}", name, e.toString());
        }
    }

    private void onJoin(ServerPlayer player) {
        if (redis == null) return;
        UUID uuid = player.getUUID();
        redis.setLocation(uuid, player.getGameProfile().name());
        String nick = redis.getNick(uuid);
        if (nick != null && !nick.isBlank() && !"RESET".equals(nick)) {
            sendNickToProxy(player, nick);
        }
        Set<UUID> ignored = redis.getIgnores(uuid);
        Set<UUID> cached = ConcurrentHashMap.newKeySet();
        cached.addAll(ignored);
        ignoreCache.put(uuid, cached);
        for (UUID id : ignored) {
            sendIgnoreToProxy(player, id, true);
        }
        MinecraftServer srv = this.server;
        if (srv != null) {
            srv.execute(() -> {
                ServerPlayer still = srv.getPlayerList().getPlayer(uuid);
                if (still != null) {
                    deliverMail(still);
                }
            });
        }
    }

    private void deliverMail(ServerPlayer player) {
        if (redis == null || player == null) {
            return;
        }
        List<RedisSync.Mail> inbox = redis.takeMail(player.getUUID());
        if (inbox.isEmpty()) {
            return;
        }
        String label = inbox.size() == 1 ? " offline message:" : " offline messages:";
        player.sendSystemMessage(Component.literal("You have " + inbox.size() + label));
        UUID lastFrom = null;
        for (RedisSync.Mail mail : inbox) {
            String fromName = mail.fromName != null ? mail.fromName : "Unknown";
            player.sendSystemMessage(Component.literal("[" + fromName + " → me]: " + (mail.text == null ? "" : mail.text)));
            lastFrom = parseUuid(mail.from);
        }
        if (lastFrom != null) {
            redis.setReply(player.getUUID(), lastFrom);
        }
    }

    private void onRedisEvent(RedisSync.Event event) {
        if (event == null || event.type == null || server == null) return;
        server.execute(() -> {
            if ("msg".equals(event.type)) {
                UUID to = parseUuid(event.extraUuid);
                UUID from = parseUuid(event.uuid);
                if (to == null || from == null || event.text == null) return;
                ServerPlayer target = server.getPlayerList().getPlayer(to);
                if (target == null) return;
                String fromName = event.name != null ? event.name : "Unknown";
                target.sendSystemMessage(Component.literal("[" + fromName + " → me]: " + event.text));
                if (redis != null) redis.setReply(to, from);
            } else if ("ignore".equals(event.type)) {
                UUID ignorer = parseUuid(event.uuid);
                UUID ignored = parseUuid(event.extraUuid);
                if (ignorer == null || ignored == null) return;
                Set<UUID> set = ignoreCache.computeIfAbsent(ignorer, k -> ConcurrentHashMap.newKeySet());
                if ("add".equals(event.action)) set.add(ignored);
                else set.remove(ignored);
            }
        });
    }

    private int cmdIgnore(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) return 0;
        String name = StringArgumentType.getString(ctx, "player");
        Resolved target = resolvePlayer(name);
        if (target == null) {
            player.sendSystemMessage(Component.literal("Player not found: " + name));
            return 1;
        }
        ServerPlayer online = server != null ? server.getPlayerList().getPlayer(target.uuid) : null;
        if (online != null && server != null && server.getPlayerList().isOp(new NameAndId(online.getGameProfile()))) {
            player.sendSystemMessage(Component.literal("You cannot ignore ops."));
            return 1;
        }
        Set<UUID> ignored = ignoresOf(player.getUUID());
        boolean add = !ignored.contains(target.uuid);
        if (redis != null) {
            redis.setIgnore(player.getUUID(), target.uuid, add);
        }
        Set<UUID> cached = ignoreCache.computeIfAbsent(player.getUUID(), k -> ConcurrentHashMap.newKeySet());
        if (add) cached.add(target.uuid);
        else cached.remove(target.uuid);
        sendIgnoreToProxy(player, target.uuid, add);
        player.sendSystemMessage(Component.literal(add ? "Now ignoring " + target.name : "No longer ignoring " + target.name));
        return 1;
    }

    private int cmdMsg(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) return 0;
        try {
            sendPrivate(player, StringArgumentType.getString(ctx, "player"), StringArgumentType.getString(ctx, "message"));
        } catch (Throwable t) {
            LOG.error("/msg failed", t);
            player.sendSystemMessage(Component.literal("Could not send that message. Try again in a moment."));
        }
        return 1;
    }

    private int cmdReply(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) return 0;
        try {
            return cmdReplyInner(player, ctx);
        } catch (Throwable t) {
            LOG.error("/r failed", t);
            player.sendSystemMessage(Component.literal("Could not send that reply. Try again in a moment."));
            return 1;
        }
    }

    private int cmdReplyInner(ServerPlayer player, CommandContext<CommandSourceStack> ctx) {
        UUID last = redis != null ? redis.getReply(player.getUUID()) : null;
        if (last == null) {
            player.sendSystemMessage(Component.literal("No one to reply to."));
            return 1;
        }
        String name = redis != null ? redis.lookupUuid(last) : null;
        if (name == null) {
            ServerPlayer online = server != null ? server.getPlayerList().getPlayer(last) : null;
            name = online != null ? online.getGameProfile().name() : last.toString();
        }
        sendPrivate(player, name, StringArgumentType.getString(ctx, "message"));
        return 1;
    }

    private int cmdNickShow(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) return 0;
        String current = redis != null ? redis.getNick(player.getUUID()) : null;
        player.sendSystemMessage(Component.literal("Your current nick: " + (current == null ? "none" : current)));
        player.sendSystemMessage(Component.literal("Usage: /nick <MiniMessage> | /nick reset"));
        return 1;
    }

    private int cmdNickSet(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) return 0;
        UUID uuid = player.getUUID();
        long now = System.currentTimeMillis();
        if (nickCooldown.getOrDefault(uuid, 0L) > now) {
            long remaining = TimeUnit.MILLISECONDS.toSeconds(nickCooldown.get(uuid) - now);
            player.sendSystemMessage(Component.literal("You must wait " + remaining + " seconds before changing your nick again."));
            return 1;
        }
        String input = StringArgumentType.getString(ctx, "value");
        if (input.equalsIgnoreCase("reset") || input.equalsIgnoreCase("off")) {
            if (redis != null) redis.setNick(uuid, null);
            sendNickToProxy(player, null);
            player.sendSystemMessage(Component.literal("Nickname reset."));
            return 1;
        }
        if (input.length() > MAX_NICK_LENGTH) {
            player.sendSystemMessage(Component.literal("Nickname too long! Maximum " + MAX_NICK_LENGTH + " characters."));
            return 1;
        }
        nickCooldown.put(uuid, now + NICK_COOLDOWN_SECONDS * 1000L);
        if (redis != null) redis.setNick(uuid, input);
        sendNickToProxy(player, input);
        player.sendSystemMessage(Component.literal("Nickname set successfully!"));
        return 1;
    }

    private void sendPrivate(ServerPlayer player, String targetName, String message) {
        if (message != null && message.length() > MAX_MESSAGE_LENGTH) {
            player.sendSystemMessage(Component.literal("Message too long (max " + MAX_MESSAGE_LENGTH + " characters)."));
            return;
        }
        Resolved target = resolvePlayer(targetName);
        if (target == null) {
            player.sendSystemMessage(Component.literal("Player not found: " + targetName));
            return;
        }
        if (target.uuid.equals(player.getUUID())) {
            player.sendSystemMessage(Component.literal("You cannot message yourself."));
            return;
        }
        if (redis != null) {
            if (ignoresOf(target.uuid).contains(player.getUUID())) {
                player.sendSystemMessage(Component.literal("You cannot message someone who ignores you."));
                return;
            }
            boolean here = server != null && server.getPlayerList().getPlayer(target.uuid) != null;
            if (here || redis.isOnline(target.uuid)) {
                redis.setReply(target.uuid, player.getUUID());
                redis.publishMsg(player.getUUID(), player.getGameProfile().name(), target.uuid, message);
            } else if (!redis.queueMail(target.uuid, player.getUUID(), player.getGameProfile().name(), message)) {
                player.sendSystemMessage(Component.literal("Could not queue that message."));
                return;
            } else {
                player.sendSystemMessage(Component.literal("[me → " + target.name + "]: " + message));
                player.sendSystemMessage(Component.literal("They're offline. They'll see it when they join."));
                return;
            }
        } else {
            ServerPlayer online = server != null ? server.getPlayerList().getPlayer(target.uuid) : null;
            if (online == null) {
                player.sendSystemMessage(Component.literal("Player not found or offline: " + target.name));
                return;
            }
            online.sendSystemMessage(Component.literal("[" + player.getGameProfile().name() + " → me]: " + message));
        }
        player.sendSystemMessage(Component.literal("[me → " + target.name + "]: " + message));
    }

    private Set<UUID> ignoresOf(UUID uuid) {
        Set<UUID> cached = ignoreCache.get(uuid);
        if (cached != null) return cached;
        if (redis != null) {
            Set<UUID> loaded = redis.getIgnores(uuid);
            Set<UUID> set = ConcurrentHashMap.newKeySet();
            set.addAll(loaded);
            ignoreCache.put(uuid, set);
            return set;
        }
        return Set.of();
    }

    private Component nickComponent(ServerPlayer player) {
        String fallback = player.getGameProfile().name();
        if (redis == null) {
            return Component.literal(fallback);
        }
        return MiniNick.parse(redis.getNick(player.getUUID()), fallback);
    }

    private Resolved resolvePlayer(String name) {
        if (server != null) {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (p.getGameProfile().name().equalsIgnoreCase(name)) {
                    return new Resolved(p.getUUID(), p.getGameProfile().name());
                }
            }
        }
        if (redis != null) {
            UUID uuid = redis.lookupName(name);
            if (uuid != null) {
                String stored = redis.lookupUuid(uuid);
                return new Resolved(uuid, stored != null ? stored : name);
            }
        }
        return null;
    }

    private void sendNickToProxy(ServerPlayer player, String nick) {
        String payload = player.getUUID() + "|" + (nick == null || nick.isBlank() ? "RESET" : nick);
        ServerPlayNetworking.send(player, new PluginBytesPayload(NICK.type, payload.getBytes(StandardCharsets.UTF_8)));
    }

    private void sendIgnoreToProxy(ServerPlayer player, UUID ignored, boolean add) {
        String payload = player.getUUID() + "|" + ignored + "|" + (add ? "add" : "remove");
        ServerPlayNetworking.send(player, new PluginBytesPayload(IGNORE.type, payload.getBytes(StandardCharsets.UTF_8)));
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) return null;
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
