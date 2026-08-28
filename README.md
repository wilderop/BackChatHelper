# BackChatHelper

Paper companion for [SuperSimpleProxyChat](https://github.com/wilderop/SuperSimpleProxyChat).

## Commands

| Command | What it does |
|---|---|
| `/ignore <player>` | Toggle ignore (local chat + proxy chat + mail) |
| `/nick <MiniMessage>` | Set a colored/gradient nick |
| `/nick reset` | Clear nick |
| `/msg <player> <text>` | PM. If they are on another server or offline, the proxy delivers or queues it |
| `/r <text>` | Reply to last PM |

Aliases for `/msg`: `/m`, `/tell`, `/whisper`, `/w`

## Install

1. Build: `mvn clean package`
2. Put `target/BackChatHelper.jar` on **every** Paper backend
3. Put SuperSimpleProxyChat on Velocity

## Config

`plugins/BackChatHelper/config.yml`

```yaml
nick-cooldown-seconds: 60
max-nick-length: 64
```

Nicks and ignore lists are stored in `plugins/BackChatHelper/players/<uuid>.yml` and also synced through Velocity so they follow the player across backends.
