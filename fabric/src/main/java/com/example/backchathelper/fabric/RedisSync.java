package com.example.backchathelper.fabric;

import com.google.gson.Gson;
import org.slf4j.Logger;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.util.Pool;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.JedisSentinelPool;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

final class RedisSync {
    static final String CHANNEL = "bch:events";
    private static final Gson GSON = new Gson();
    static final int MAX_MAIL = 100;
    static final long MAIL_TTL_SECONDS = 30L * 24 * 60 * 60;
    private static final String TAKE_MAIL_LUA =
            "local items = redis.call('LRANGE', KEYS[1], 0, -1)\n"
                    + "redis.call('DEL', KEYS[1])\n"
                    + "return items";

    private final Logger logger;
    private final String serverName;
    private final String host;
    private final int port;
    private final String password;
    private volatile Pool<Jedis> pool;
    private volatile Thread subscriber;
    private volatile boolean running;

    RedisSync(Logger logger, String host, int port, String password, String serverName) {
        this.logger = logger;
        this.serverName = serverName;
        this.host = host;
        this.port = port;
        this.password = password == null ? "" : password;
    }

    static RedisSync fromDefaults(Logger logger) {
        String host = "127.0.0.1";
        int port = 6379;
        String password = "";
        try {
            Path pf = Path.of("redis.pass");
            if (Files.isRegularFile(pf)) {
                password = Files.readString(pf).trim();
            }
        } catch (Exception ignored) {
        }
        return new RedisSync(logger, host, port, password, "fabric");
    }

    void start(Consumer<Event> listener) {
        JedisPoolConfig cfg = new JedisPoolConfig();
        cfg.setMaxTotal(4);
        java.util.Set<String> sentinels = java.util.Set.of(
                "127.0.0.1:26379", "127.0.0.1:26379", "127.0.0.1:26379");
        try {
            pool = password.isBlank()
                    ? new JedisSentinelPool("azpbmd", sentinels, cfg, 3000)
                    : new JedisSentinelPool("azpbmd", sentinels, cfg, 3000, password);
            try (Jedis jedis = pool.getResource()) {
                jedisCall(jedis, "ping", new Class<?>[0]);
            }
            logger.info("BackChatHelper Redis via Sentinel master=azpbmd");
        } catch (Exception e) {
            logger.warn("BackChatHelper Sentinel failed ({}), falling back to {}:{}", e.getMessage(), host, port);
            if (pool != null) {
                try { pool.close(); } catch (Exception ignored) {}
            }
            pool = password.isBlank()
                    ? new JedisPool(cfg, host, port, 3000)
                    : new JedisPool(cfg, host, port, 3000, password);
            try (Jedis jedis = pool.getResource()) {
                jedisCall(jedis, "ping", new Class<?>[0]);
            } catch (Exception pingErr) {
                throw new RuntimeException("Redis ping failed: " + pingErr.getMessage(), pingErr);
            }
            logger.info("BackChatHelper Redis connected to {}:{}", host, port);
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
        if (pool != null) {
            pool.close();
            pool = null;
        }
    }

    boolean ready() {
        return pool != null;
    }

    void rememberName(UUID uuid, String name) {
        if (pool == null || uuid == null || name == null || name.isBlank()) return;
        try (Jedis j = pool.getResource()) {
            jedisSet(j, "bch:uuid:" + uuid, name);
            jedisSet(j, "bch:name:" + name.toLowerCase(Locale.ROOT), uuid.toString());
        } catch (Exception e) {
            logger.warn("rememberName: {}", e.toString());
        }
    }

    void setLocation(UUID uuid, String name) {
        if (pool == null || uuid == null) return;
        rememberName(uuid, name);
        try (Jedis j = pool.getResource()) {
            jedisSetex(j, "bch:loc:" + uuid, 90, serverName + "|" + (name == null ? "" : name));
        } catch (Exception e) {
            logger.warn("setLocation: {}", e.toString());
        }
    }

    void clearLocation(UUID uuid) {
        if (pool == null || uuid == null) return;
        redisDel("bch:loc:" + uuid);
    }

    boolean isOnline(UUID uuid) {
        if (pool == null || uuid == null) return false;
        try (Jedis j = pool.getResource()) {
            return jedisExists(j, "bch:loc:" + uuid);
        } catch (Exception e) {
            return false;
        }
    }

    UUID lookupName(String name) {
        if (pool == null || name == null || name.isBlank()) return null;
        try (Jedis j = pool.getResource()) {
            String raw = jedisGet(j, "bch:name:" + name.toLowerCase(Locale.ROOT));
            if (raw == null || raw.isBlank()) return null;
            return UUID.fromString(raw);
        } catch (Exception e) {
            return null;
        }
    }

    String lookupUuid(UUID uuid) {
        if (pool == null || uuid == null) return null;
        try (Jedis j = pool.getResource()) {
            return jedisGet(j, "bch:uuid:" + uuid);
        } catch (Exception e) {
            return null;
        }
    }

    Set<UUID> getIgnores(UUID uuid) {
        Set<UUID> out = new HashSet<>();
        if (pool == null || uuid == null) return out;
        try (Jedis j = pool.getResource()) {
            for (String raw : jedisSmembers(j, "bch:ignore:" + uuid)) {
                try {
                    out.add(UUID.fromString(raw));
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            logger.warn("getIgnores: {}", e.toString());
        }
        return out;
    }

    void setIgnore(UUID ignorer, UUID ignored, boolean add) {
        if (pool == null || ignorer == null || ignored == null) return;
        try (Jedis j = pool.getResource()) {
            if (add) {
                jedisSadd(j, "bch:ignore:" + ignorer, ignored.toString());
            } else {
                jedisSrem(j, "bch:ignore:" + ignorer, ignored.toString());
            }
            Event event = new Event();
            event.type = "ignore";
            event.uuid = ignorer.toString();
            event.extraUuid = ignored.toString();
            event.action = add ? "add" : "remove";
            event.server = serverName;
            publish(event);
        } catch (Exception e) {
            logger.warn("setIgnore: {}", e.toString());
        }
    }

    String getNick(UUID uuid) {
        if (pool == null || uuid == null) return null;
        try (Jedis j = pool.getResource()) {
            String nick = jedisGet(j, "bch:nick:" + uuid);
            return nick == null || nick.isBlank() ? null : nick;
        } catch (Exception e) {
            return null;
        }
    }

    void setNick(UUID uuid, String nick) {
        if (pool == null || uuid == null) return;
        try (Jedis j = pool.getResource()) {
            if (nick == null || nick.isBlank()) {
                redisDel(j, "bch:nick:" + uuid);
            } else {
                jedisSet(j, "bch:nick:" + uuid, nick);
            }
            Event event = new Event();
            event.type = "nick";
            event.uuid = uuid.toString();
            event.text = nick == null || nick.isBlank() ? "RESET" : nick;
            event.server = serverName;
            publish(event);
        } catch (Exception e) {
            logger.warn("setNick: {}", e.toString());
        }
    }

    UUID getReply(UUID uuid) {
        if (pool == null || uuid == null) return null;
        try (Jedis j = pool.getResource()) {
            String raw = jedisGet(j, "bch:reply:" + uuid);
            return raw == null || raw.isBlank() ? null : UUID.fromString(raw);
        } catch (Exception e) {
            return null;
        }
    }

    void setReply(UUID recipient, UUID sender) {
        if (pool == null || recipient == null || sender == null) return;
        try (Jedis j = pool.getResource()) {
            jedisSet(j, "bch:reply:" + recipient, sender.toString());
        } catch (Exception e) {
            logger.warn("setReply: {}", e.toString());
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
        if (pool == null || to == null || from == null || text == null) {
            return false;
        }
        Mail mail = new Mail();
        mail.from = from.toString();
        mail.fromName = fromName == null ? "Unknown" : fromName;
        mail.text = text;
        mail.time = System.currentTimeMillis();
        String key = "bch:mail:" + to;
        try (Jedis j = pool.getResource()) {
            jedisRpush(j, key, GSON.toJson(mail));
            jedisLtrim(j, key, -MAX_MAIL, -1);
            jedisExpire(j, key, MAIL_TTL_SECONDS);
            return true;
        } catch (Exception e) {
            logger.warn("queueMail: {}", e.toString());
            return false;
        }
    }

    List<Mail> takeMail(UUID to) {
        List<Mail> out = new ArrayList<>();
        if (pool == null || to == null) {
            return out;
        }
        try (Jedis j = pool.getResource()) {
            Object raw = jedisEval(j, TAKE_MAIL_LUA, "bch:mail:" + to);
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
            logger.warn("takeMail: {}", e.toString());
        }
        return out;
    }

    private void redisDel(String key) {
        if (pool == null) return;
        try (Jedis j = pool.getResource()) {
            redisDel(j, key);
        } catch (Exception ignored) {
        }
    }

    private static void redisDel(Jedis j, String key) {
        try {
            jedisCall(j, "del", new Class<?>[]{String.class}, key);
        } catch (Throwable t) {
            try {
                jedisCall(j, "del", new Class<?>[]{String[].class}, (Object) new String[]{key});
            } catch (Throwable ignored) {
            }
        }
    }

    // PlayerDataSync ships an older Jedis on the Fabric Knot classpath. Direct
    // calls to exists/setex compiled against Jedis 4 throw NoSuchMethodError.
    private static Object jedisCall(Jedis j, String name, Class<?>[] types, Object... args) throws Exception {
        return j.getClass().getMethod(name, types).invoke(j, args);
    }

    private static boolean jedisExists(Jedis j, String key) {
        try {
            Object r = jedisCall(j, "exists", new Class<?>[]{String.class}, key);
            return Boolean.TRUE.equals(r);
        } catch (Throwable t) {
            return false;
        }
    }

    private static String jedisGet(Jedis j, String key) {
        try {
            Object r = jedisCall(j, "get", new Class<?>[]{String.class}, key);
            return r == null ? null : r.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void jedisSet(Jedis j, String key, String value) throws Exception {
        jedisCall(j, "set", new Class<?>[]{String.class, String.class}, key, value);
    }

    private static void jedisSetex(Jedis j, String key, int seconds, String value) throws Exception {
        try {
            jedisCall(j, "setex", new Class<?>[]{String.class, long.class, String.class}, key, (long) seconds, value);
        } catch (Throwable t) {
            jedisCall(j, "setex", new Class<?>[]{String.class, int.class, String.class}, key, seconds, value);
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<String> jedisSmembers(Jedis j, String key) {
        try {
            Object r = jedisCall(j, "smembers", new Class<?>[]{String.class}, key);
            if (r instanceof Set<?> set) {
                Set<String> out = new HashSet<>();
                for (Object o : set) {
                    if (o != null) out.add(o.toString());
                }
                return out;
            }
        } catch (Throwable ignored) {
        }
        return Set.of();
    }

    private static void jedisSadd(Jedis j, String key, String member) throws Exception {
        jedisCall(j, "sadd", new Class<?>[]{String.class, String[].class}, key, new String[]{member});
    }

    private static void jedisSrem(Jedis j, String key, String member) throws Exception {
        jedisCall(j, "srem", new Class<?>[]{String.class, String[].class}, key, new String[]{member});
    }

    private static void jedisRpush(Jedis j, String key, String value) throws Exception {
        jedisCall(j, "rpush", new Class<?>[]{String.class, String[].class}, key, new String[]{value});
    }

    private static void jedisLtrim(Jedis j, String key, long start, long stop) throws Exception {
        try {
            jedisCall(j, "ltrim", new Class<?>[]{String.class, long.class, long.class}, key, start, stop);
        } catch (Throwable t) {
            jedisCall(j, "ltrim", new Class<?>[]{String.class, int.class, int.class}, key, (int) start, (int) stop);
        }
    }

    private static void jedisExpire(Jedis j, String key, long seconds) throws Exception {
        try {
            jedisCall(j, "expire", new Class<?>[]{String.class, long.class}, key, seconds);
        } catch (Throwable t) {
            jedisCall(j, "expire", new Class<?>[]{String.class, int.class}, key, (int) seconds);
        }
    }

    private static Object jedisEval(Jedis j, String script, String key) throws Exception {
        try {
            return jedisCall(j, "eval", new Class<?>[]{String.class, int.class, String[].class},
                    script, 1, new String[]{key});
        } catch (Throwable t) {
            return jedisCall(j, "eval", new Class<?>[]{String.class, List.class, List.class},
                    script, List.of(key), List.of());
        }
    }

    private void publish(Event event) {
        if (pool == null) return;
        try (Jedis j = pool.getResource()) {
            jedisCall(j, "publish", new Class<?>[]{String.class, String.class}, CHANNEL, GSON.toJson(event));
        } catch (Exception e) {
            logger.warn("publish: {}", e.toString());
        }
    }

    private void subscribeLoop(Consumer<Event> listener) {
        while (running) {
            try (Jedis jedis = pool.getResource()) {
                jedis.subscribe(new JedisPubSub() {
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
                            logger.debug("event ignored: {}", e.toString());
                        }
                    }
                }, CHANNEL);
            } catch (Exception e) {
                if (running) {
                    logger.warn("subscriber disconnected: {}", e.toString());
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

    static String passwordFromUri(String uri) {
        try {
            URI parsed = URI.create(uri);
            if (parsed.getUserInfo() == null) return "";
            String info = parsed.getUserInfo();
            int colon = info.indexOf(':');
            if (colon < 0) return info;
            if (colon == 0) return info.substring(1);
            return info.substring(colon + 1);
        } catch (Exception e) {
            return "";
        }
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
