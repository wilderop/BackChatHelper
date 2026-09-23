package com.example.backchathelper;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.bukkit.plugin.java.JavaPlugin;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.JedisSentineled;
import redis.clients.jedis.UnifiedJedis;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;

final class RedisSync {

    static final String CHANNEL = "bch:events";
    private static final Gson GSON = new Gson();
    static final int MAX_MAIL = 100;
    static final long MAIL_TTL_SECONDS = 30L * 24 * 60 * 60;
    private static final String TAKE_MAIL_LUA =
            "local items = redis.call('LRANGE', KEYS[1], 0, -1)\n"
                    + "redis.call('DEL', KEYS[1])\n"
                    + "return items";

    private static final String SENTINEL_MASTER = "azpbmd";
    private static final Set<HostAndPort> SENTINELS = Set.of(
            new HostAndPort("127.0.0.1", 26379),
            new HostAndPort("127.0.0.1", 26379),
            new HostAndPort("127.0.0.1", 26379));

    private final Logger logger;
    private final String serverName;
    private final HostAndPort hostAndPort;
    private final JedisClientConfig clientConfig;

    private volatile UnifiedJedis pooled;
    private volatile Thread subscriber;
    private volatile boolean running;

    RedisSync(Logger logger, String uri, String serverName) {
        this.logger = logger;
        this.serverName = serverName;
        URI parsed = URI.create(uri);
        String host = parsed.getHost();
        int port = parsed.getPort() > 0 ? parsed.getPort() : 6379;
        String password = null;
        String user = null;
        if (parsed.getUserInfo() != null) {
            String info = parsed.getUserInfo();
            int colon = info.indexOf(':');
            if (colon < 0) {
                password = info;
            } else if (colon == 0) {
                password = info.substring(1);
            } else {
                user = info.substring(0, colon);
                password = info.substring(colon + 1);
            }
        }
        this.hostAndPort = new HostAndPort(host, port);
        DefaultJedisClientConfig.Builder builder = DefaultJedisClientConfig.builder()
                .socketTimeoutMillis(3000)
                .connectionTimeoutMillis(3000);
        if (user != null && !user.isBlank()) {
            builder.user(user);
        }
        if (password != null && !password.isBlank()) {
            builder.password(password);
        }
        this.clientConfig = builder.build();
    }

    void start(Consumer<Event> listener) {
        JedisClientConfig sentinelCfg = DefaultJedisClientConfig.builder()
                .socketTimeoutMillis(3000)
                .connectionTimeoutMillis(3000)
                .build();
        try {
            this.pooled = new JedisSentineled(SENTINEL_MASTER, clientConfig, SENTINELS, sentinelCfg);
            this.pooled.ping();
            logger.info("BackChatHelper Redis via Sentinel master=" + SENTINEL_MASTER);
        } catch (Exception e) {
            logger.warning("BackChatHelper Sentinel failed (" + e.getMessage()
                    + "), falling back to " + hostAndPort.getHost() + ":" + hostAndPort.getPort());
            this.pooled = new JedisPooled(hostAndPort, clientConfig);
            try (Jedis jedis = new Jedis(hostAndPort, clientConfig)) {
                jedis.ping();
            }
            logger.info("BackChatHelper Redis connected to " + hostAndPort.getHost() + ":" + hostAndPort.getPort());
        }
        this.running = true;
        subscriber = new Thread(() -> subscribeLoop(listener), "backchat-redis");
        subscriber.setDaemon(true);
        subscriber.start();
    }

    void stop() {
        running = false;
        if (subscriber != null) {
            subscriber.interrupt();
        }
        try {
            if (pooled != null) {
                pooled.close();
            }
        } catch (Exception ignored) {
        }
        pooled = null;
    }

    boolean ready() {
        return pooled != null;
    }

    void rememberName(UUID uuid, String name) {
        if (pooled == null || uuid == null || name == null || name.isBlank()) {
            return;
        }
        try {
            pooled.set("bch:uuid:" + uuid, name);
            pooled.set("bch:name:" + name.toLowerCase(Locale.ROOT), uuid.toString());
        } catch (Exception e) {
            logger.warning("Redis rememberName failed: " + e.getMessage());
        }
    }

    void setLocation(UUID uuid, String name) {
        if (pooled == null || uuid == null) {
            return;
        }
        rememberName(uuid, name);
        try {
            pooled.setex("bch:loc:" + uuid, 90, serverName + "|" + (name == null ? "" : name));
        } catch (Exception e) {
            logger.warning("Redis setLocation failed: " + e.getMessage());
        }
    }

    void clearLocation(UUID uuid) {
        if (pooled == null || uuid == null) {
            return;
        }
        try {
            pooled.del("bch:loc:" + uuid);
        } catch (Exception ignored) {
        }
    }

    boolean isOnline(UUID uuid) {
        if (pooled == null || uuid == null) {
            return false;
        }
        try {
            return pooled.exists("bch:loc:" + uuid);
        } catch (Exception e) {
            return false;
        }
    }

    UUID lookupName(String name) {
        if (pooled == null || name == null || name.isBlank()) {
            return null;
        }
        try {
            String raw = pooled.get("bch:name:" + name.toLowerCase(Locale.ROOT));
            if (raw == null || raw.isBlank()) {
                return null;
            }
            return UUID.fromString(raw);
        } catch (Exception e) {
            return null;
        }
    }

    String lookupUuid(UUID uuid) {
        if (pooled == null || uuid == null) {
            return null;
        }
        try {
            return pooled.get("bch:uuid:" + uuid);
        } catch (Exception e) {
            return null;
        }
    }

    Set<UUID> getIgnores(UUID uuid) {
        Set<UUID> out = new HashSet<>();
        if (pooled == null || uuid == null) {
            return out;
        }
        try {
            for (String raw : pooled.smembers("bch:ignore:" + uuid)) {
                try {
                    out.add(UUID.fromString(raw));
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            logger.warning("Redis getIgnores failed: " + e.getMessage());
        }
        return out;
    }

    void setIgnore(UUID ignorer, UUID ignored, boolean add) {
        if (pooled == null || ignorer == null || ignored == null) {
            return;
        }
        try {
            if (add) {
                pooled.sadd("bch:ignore:" + ignorer, ignored.toString());
            } else {
                pooled.srem("bch:ignore:" + ignorer, ignored.toString());
            }
            Event event = new Event();
            event.type = "ignore";
            event.uuid = ignorer.toString();
            event.extraUuid = ignored.toString();
            event.action = add ? "add" : "remove";
            event.server = serverName;
            publish(event);
        } catch (Exception e) {
            logger.warning("Redis setIgnore failed: " + e.getMessage());
        }
    }

    String getNick(UUID uuid) {
        if (pooled == null || uuid == null) {
            return null;
        }
        try {
            String nick = pooled.get("bch:nick:" + uuid);
            return nick == null || nick.isBlank() ? null : nick;
        } catch (Exception e) {
            return null;
        }
    }

    void setNick(UUID uuid, String nick) {
        if (pooled == null || uuid == null) {
            return;
        }
        try {
            if (nick == null || nick.isBlank()) {
                pooled.del("bch:nick:" + uuid);
            } else {
                pooled.set("bch:nick:" + uuid, nick);
            }
            Event event = new Event();
            event.type = "nick";
            event.uuid = uuid.toString();
            event.text = nick == null || nick.isBlank() ? "RESET" : nick;
            event.server = serverName;
            publish(event);
        } catch (Exception e) {
            logger.warning("Redis setNick failed: " + e.getMessage());
        }
    }

    UUID getReply(UUID uuid) {
        if (pooled == null || uuid == null) {
            return null;
        }
        try {
            String raw = pooled.get("bch:reply:" + uuid);
            return raw == null || raw.isBlank() ? null : UUID.fromString(raw);
        } catch (Exception e) {
            return null;
        }
    }

    void setReply(UUID recipient, UUID sender) {
        if (pooled == null || recipient == null || sender == null) {
            return;
        }
        try {
            pooled.set("bch:reply:" + recipient, sender.toString());
        } catch (Exception e) {
            logger.warning("Redis setReply failed: " + e.getMessage());
        }
    }

    void publishMsg(UUID from, String fromName, UUID to, String text) {
        Event event = new Event();
        event.type = "msg";
        event.uuid = from.toString();
        event.name = fromName;
        event.extraUuid = to.toString();
        event.text = text;
        event.server = serverName;
        publish(event);
    }

    boolean queueMail(UUID to, UUID from, String fromName, String text) {
        if (pooled == null || to == null || from == null || text == null) {
            return false;
        }
        Mail mail = new Mail();
        mail.from = from.toString();
        mail.fromName = fromName == null ? "Unknown" : fromName;
        mail.text = text;
        mail.time = System.currentTimeMillis();
        String key = "bch:mail:" + to;
        try {
            pooled.rpush(key, GSON.toJson(mail));
            pooled.ltrim(key, -MAX_MAIL, -1);
            pooled.expire(key, MAIL_TTL_SECONDS);
            return true;
        } catch (Exception e) {
            logger.warning("Redis queueMail failed: " + e.getMessage());
            return false;
        }
    }

    List<Mail> takeMail(UUID to) {
        List<Mail> out = new ArrayList<>();
        if (pooled == null || to == null) {
            return out;
        }
        try {
            Object raw = pooled.eval(TAKE_MAIL_LUA, 1, "bch:mail:" + to);
            if (raw instanceof List<?> list) {
                for (Object item : list) {
                    if (item == null) {
                        continue;
                    }
                    try {
                        Mail mail = GSON.fromJson(item.toString(), Mail.class);
                        if (mail != null && mail.text != null) {
                            out.add(mail);
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception e) {
            logger.warning("Redis takeMail failed: " + e.getMessage());
        }
        return out;
    }

    void importIgnore(UUID ignorer, UUID ignored) {
        if (pooled == null || ignorer == null || ignored == null) {
            return;
        }
        try {
            pooled.sadd("bch:ignore:" + ignorer, ignored.toString());
        } catch (Exception ignoredEx) {
        }
    }

    void importNick(UUID uuid, String nick) {
        if (pooled == null || uuid == null || nick == null || nick.isBlank()) {
            return;
        }
        try {
            if (!pooled.exists("bch:nick:" + uuid)) {
                pooled.set("bch:nick:" + uuid, nick);
            }
        } catch (Exception ignored) {
        }
    }

    private void publish(Event event) {
        if (pooled == null) {
            return;
        }
        try {
            pooled.publish(CHANNEL, GSON.toJson(event));
        } catch (Exception e) {
            logger.warning("Redis publish failed: " + e.getMessage());
        }
    }

    private void subscribeLoop(Consumer<Event> listener) {
        while (running) {
            try {
                pooled.subscribe(new JedisPubSub() {
                    @Override
                    public void onMessage(String channel, String message) {
                        if (!running) {
                            try {
                                unsubscribe();
                            } catch (Exception ignored) {
                            }
                            return;
                        }
                        try {
                            Event event = GSON.fromJson(message, Event.class);
                            if (event != null) {
                                listener.accept(event);
                            }
                        } catch (Exception e) {
                            logger.fine("Redis event ignored: " + e.getMessage());
                        }
                    }
                }, CHANNEL);
            } catch (Exception e) {
                if (running) {
                    logger.warning("Redis subscriber disconnected: " + e.getMessage());
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    static String parseUriOrEmpty(JavaPlugin plugin) {
        String uri = plugin.getConfig().getString("redis-uri", "");
        return uri == null ? "" : uri.trim();
    }

    static final class Event {
        String type;
        String uuid;
        String extraUuid;
        String name;
        String text;
        String action;
        String server;
    }

    static final class Mail {
        String from;
        String fromName;
        String text;
        long time;
    }
}
