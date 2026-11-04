# PearlPlusWebAPI (per-user tokens fork)

A [ZenithProxy](https://github.com/rfresh2/ZenithProxy) plugin that runs a small web server so players can load their PearlPlus pearls without using in-game chat. That helps when they're chat banned or muted, or when the server's chat filter delays or drops repeated whispers.

This is a fork of [duccss/PearlPlusWebAPI](https://github.com/duccss/PearlPlusWebAPI). Pair it with the matching [PearlPlusMod fork](#client-mod) on the player's side.

Requires [PearlPlus](https://github.com/duccss/PearlPlus) or [PearlPlus-ng](https://github.com/steve2b2t/PearlPlus-ng) to be installed in the same ZenithProxy instance.

## What's different from upstream

| | Upstream | This fork |
|---|---|---|
| Auth | One shared token that can load **any** player's pearls | One token **per player**, which only works for that player's own pearls. The old master token can be turned off |
| Tokens | A long random value | **7 characters**, easy to type, with no look-alike characters (no `0/O`, `1/l/I`) |
| Token storage | Plain text in the config, printed at startup, and shown in every `ppApi` reply (which ZenithProxy mirrors to Discord) | SHA-256 hashes only. The master token is never shown in `ppApi` replies. A new player token is shown once, in the command reply and the console log |
| Token guessing | Only the per-IP limit | A global cap on failed logins (default 60 a minute across all IPs). Valid tokens keep working during a lockout |
| Rate limit | Per IP. Behind a reverse proxy or Cloudflare Tunnel, every player shares one bucket | Per token. Unauthenticated callers are limited per IP, using `CF-Connecting-IP` when behind a local tunnel |
| Input | `playerName` and `pearlId` were pasted straight into the `pp load` command | Both are validated: Minecraft-name format, and pearl IDs with no spaces or quotes |
| `load` without a pearl | Rejected | Uses the player's default pearl, the same rule as whispering a bare `load` |
| Unknown pearl | Queued anyway, then fails inside PearlPlus | `404` that lists the player's pearls |
| Bot offline / in queue | Load queued anyway | `503 Bot is not connected` |
| `status` response | pearls, bot, server, connected | Also `loadCommand` (the bot's whisper load word) and `defaultPearlId`, which the mod uses to intercept whispers |
| Logging | Command only | Command, which player's token was used, and the real client IP |

Existing upstream configs keep working: the master token is still accepted until you turn it off.

## Install

1. Build the jar (`./gradlew build`, JDK 21+, output in `build/libs/`) or download it from Releases.
2. Put it in your ZenithProxy `plugins/` folder next to PearlPlus, and restart ZenithProxy.
3. The API starts on port `8080`. Change it with `ppApi port <port>`.
4. Make it reachable over **HTTPS**. Tokens are sent in a header, so don't expose plain HTTP to the internet. A Cloudflare Tunnel (`hostname -> http://127.0.0.1:8080`) or any reverse proxy with TLS works.

## Setting up players

Run these in the ZenithProxy console (or anywhere you can run owner commands):

| Command | What it does |
|---|---|
| `ppApi user add <mcName>` | Creates a token for that player and shows it **once**, in the command reply (so the bot's Discord log keeps a copy) and in the console log |
| `ppApi user reset <mcName>` | Replaces a player's token (lost or leaked). The old one stops working |
| `ppApi user remove <mcName>` | Revokes a player's access |
| `ppApi user list` | Lists players who have tokens |
| `ppApi masterToken off` | Disables the all-players master token. Recommended once players have their own tokens |
| `ppApi auth regenerate` | Makes a new master token, printed to the console log only |

**Keep the bot's Discord channel admin-only.** ZenithProxy mirrors command replies there, so it records every token you issue.

Give each player:
- the API URL (for example `https://pearl.example.com`)
- their own token

The player's Minecraft name must match the name their pearls are registered under in PearlPlus (capitalization doesn't matter).

Other settings: `ppApi on/off`, `ppApi port <port>`, `ppApi rateLimiter on/off`, `ppApi rateLimiter requestsPerMinute <n>` (default 30 per token per minute). The failed-login cap is `authFailuresPerMinute` in `plugins/config/pp-api.json` (default 60, `0` turns it off, which isn't recommended with 7-character tokens).

## HTTP API

Every request is a `POST` with a JSON body and an `Authorization: <token>` header. A `Bearer ` prefix is also accepted.

### `POST /pearlplus/status`

```json
{ "playerName": "Steve" }
```

```json
{
  "pearls": ["home", "stash"],
  "output": ["Loaded pearls from PearlPlus config"],
  "minecraftServer": "2b2t.org",
  "botUsername": "PearlBot",
  "botConnected": true,
  "loadCommand": "load",
  "defaultPearlId": "home"
}
```

### `POST /pearlplus/load`

```json
{ "playerName": "Steve", "pearlId": "stash" }
```

`pearlId` is optional. Without it, the player's default pearl is used. That is the player's own default, then the global default if they own that pearl, then their only pearl if they have exactly one.

```json
{ "status": "queued", "pearlId": "stash", "output": [] }
```

### Errors

All errors look like `{"error": "..."}`.

| Code | When |
|---|---|
| 400 | Bad player name or pearl ID, or a bare load when the player has several pearls and no default |
| 401 | Missing or invalid token |
| 403 | Token belongs to a different player |
| 404 | No pearls registered, or no pearl with that ID |
| 429 | Rate limit exceeded, or too many failed logins across all callers |
| 503 | PearlPlus not loaded, or the bot isn't connected |

## Client mod

The matching PearlPlusMod fork adds whisper interception. A player types `/w PearlBot load` as usual. The mod cancels the whisper before it reaches the server's chat filter and sends the request through this API instead. The mod also keeps the original pause-menu screen with a Load button for each pearl.

## Security notes

- Only give a player their own token. A token can't load anyone else's pearls, but it can load all of its owner's.
- If a token leaks, run `ppApi user reset <name>`.
- Tokens are short (7 characters, about 2 trillion combinations), so the failed-login cap is what stops guessing. Leave it on.
- The master token in a brand-new config is still a long random value until you run `ppApi auth regenerate` (which makes a 7-character one) or turn it off.
- The master token can load anyone's pearls. Turn it off (`ppApi masterToken off`) once players have their own tokens. Upstream versions printed it to the log at startup, so regenerate it if you upgraded from one.

## Credits

Original web API by rfresh2 ([ZenithProxyWebAPI](https://github.com/rfresh2/ZenithProxyWebAPI)), adapted for PearlPlus by duccss. Per-user tokens and the whisper-intercept support were added in this fork.
