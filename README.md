<!-- azpbmd-live -->
**Live:** survival, pvp, creative, private, lobby, fabric

Paper jar on survival, pvp, creative, private, and the Montreal lobby. Fabric mod on fabric.
<!-- /azpbmd-live -->
# BackChatHelper

Companion for [SuperSimpleProxyChat](https://github.com/wilderop/SuperSimpleProxyChat): network-wide `/ignore`, `/nick`, `/msg`, `/r`.

Paper backends use the Maven plugin. The **Fabric** test sky uses the Gradle module in `fabric/`. Both talk to the same Redis keys (`bch:*`) and send `backchat:ignore` / `backchat:nick` plugin messages so Velocity chat, ignores, nicks, and PMs work on Fabric too.

## Commands

| Command | What it does |
|---|---|
| `/ignore <player>` | Toggle ignore (local chat + proxy chat + PMs) |
| `/nick <MiniMessage>` | Set a colored/gradient nick (shown on proxy chat) |
| `/nick reset` | Clear nick |
| `/msg <player> <text>` | PM across servers via Redis. Offline players get it on join (max 100 queued, 30 days). Lookup is Redis `GET` by name, never a playerdata scan. |
| `/r <text>` | Reply to last PM |

Aliases for `/msg`: `/m`, `/tell`, `/whisper`, `/w`

## Paper

```bash
mvn clean package
install-plugin-jar target/backchat.jar /mnt/pool/survival/plugins/backchat.jar
```

Config: `plugins/BackChatHelper/config.yml` (`redis-uri`, `server-name`).

## Fabric

```bash
cd fabric && ./gradlew remapJar
install-plugin-jar build/libs/BackChatHelper-1.2.4.jar /mnt/pool/fabric/mods/BackChatHelper.jar
```

Uses `/mnt/pool/skygate/redis.pass` at `127.0.0.1:6379`, `server-name=fabric`. Takes effect on the next Fabric JVM start.
