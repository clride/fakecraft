# FakeMCPlayer

Paper/Purpur 26.3 plugin (also compatible with 1.20+ via reflective NMS): a fake player bot that
- shows up in the **tab player list** (no real login, packet-spoofed),
- chats like a player via **OpenRouter** or **Ollama**, with configurable personality.

Behavior you asked for:
- **Alone with 1 player** → bot is social on its own: answers everything + proactively starts conversation when chat goes quiet.
- **Crowded server** → bot stays silent unless addressed. Direct mentions reply instantly, ambiguous messages go through a cheap **judge LLM** (`YES`/`NO`) to decide if it was addressed.
- **Human delivery cadence** → replies wait out a random human pause, and long replies that get split into multiple chat lines send part by part with a typing-length pause between them — never as a burst.
- **Leave / Rejoin** → the bot can walk out of the server like a real player: "left the game" line, gone from the tab list and the multiplayer server-list entry, zero interactions. Rejoin (console button or `/bot rejoin`) brings it back with a "joined the game" line. The state survives restarts.

## Build

Needs Java 21+ (Paper 26.x requirement). Compiled against `paper-api 26.3.build.200-stable`.

```bash
# maven (example path if you installed it to /tmp/opencode)
/tmp/opencode/apache-maven-3.9.9/bin/mvn package
# jar -> target/FakeMCPlayer-1.0.0.jar
```

Drop the jar into `plugins/`, restart, edit `plugins/FakeMCPlayer/config.yml`, then `/bot reload`.

## Setup

### OpenRouter
1. Key from https://openrouter.ai/
2. `config.yml`:
```yaml
provider: openrouter
openrouter:
  api-key: "sk-or-..."
  model: "openai/gpt-4o-mini"
  judge-model: "openai/gpt-4o-mini"  # cheap model for address detection
```

### Ollama
1. `ollama pull llama3.1 && ollama serve`
2. `config.yml`:
```yaml
provider: ollama
ollama:
  host: "http://localhost:11434"
  model: "llama3.1"
  judge-model: "llama3.1"
```

Switch at any time: change `provider:` + `/bot reload`. No restart needed.

## Key config

```yaml
bot:
  name: Sam                   # any normal name — never gets a [Bot] prefix
  show-in-tab: true
  chat-format: "<{name}> {message}"
  skin:
    source-username: "Notch"  # or source-uuid: "<dashed uuid>" — skin is copied
    # value/signature manual override from mineskin.org wins if set

personality:
  system-prompt: "You are Sam, ..."   # main personality — edit freely
  traits: [friendly, witty, helpful]

interaction:
  mention-triggers: ["@sam", "!ask", "!bot", "bot,"]
  alone-mode: {enabled: true, threshold: 1, proactive-interval-minutes: 3, idle-minutes: 2}
  group-mode: {judge-enabled: true, ...}  # false = mention-only, never judges

dashboard:
  enabled: true
  host: "0.0.0.0"            # 127.0.0.1 = this machine only
  port: 8642
  token: ""                  # blank = generated at startup, printed to console
  mode: auto                 # auto | approve | manual (live-switchable)
```

Full annotated example: `src/main/resources/config.yml`.

## Operator console (dashboard)

The plugin hosts a small web console (JDK HttpServer, SSE — no extra dependencies)
to watch and steer the bot. Open the URL that is printed at startup or re-shown
with `/bot dashboard` — the link contains the access token.

- **One `#server` feed** — everything the operator needs in a single channel:
  player and bot chat, system lines (joins, leaves, deaths, advancements), plus
  the bot's inner decisions inline — judge verdicts (Jev probability or LLM
  YES/NO + latency), triggers, skips, held messages, and draft cards you can
  Send / edit / Regenerate / Discard right in the feed.
- **Activity log** — the same stream as a compact technical list with filters
  (decisions / delivery / errors), including mode, hold and mute changes.
- **Write as the bot** — type in the composer and hit Send; the line goes out
  immediately and is stored in the bot's memory, so future AI replies treat it
  as their own history. "Suggest" fills the composer with an AI draft for the
  last player who spoke (never sent on its own).
- **Reply modes** — live-switchable in the UI or via `/bot mode`:
  - `auto` — bot replies on its own (classic behavior),
  - `approve` — the AI drafts every reply; nothing sends until you Send / edit /
    Regenerate / Discard. Ignored drafts simply wait,
  - `manual` — the bot stays quiet; "Suggest" asks the AI for a draft that lands
    in your composer for editing.
- **Hold** — stops all automatic sends without changing the mode.
- **Leave / Rejoin** — one button in the header: the bot posts "left the game",
  vanishes from the tab list and the multiplayer server-list entry, and stops
  reacting entirely; the composer locks until you press Rejoin ("joined the
  game" + session resumes). Pick up where the conversation left off.

Mode and hold state persist in `plugins/FakeMCPlayer/dashboard-state.yml`
(so your commented `config.yml` is never rewritten).

## Commands

- `/bot ask <msg>` — talk directly (bypasses judge)
- `/bot status` — show provider/config
- `/bot dashboard` — show the console URL + token (admin)
- `/bot mode <auto|approve|manual>` — reply mode (admin)
- `/bot leave | /bot rejoin` — remove the bot from the server / bring it back (admin)
- `/bot reload` — reload config (admin)
- `/bot on|off` — enable/disable (admin)

Chatting normally also works: just type `Alex what are you building?` or `@Alex hi`.

## How tab spoof works

`TablistManager` sends `ClientboundPlayerInfoUpdatePacket(ADD_PLAYER, UPDATE_LISTED)` with a fake `GameProfile` to every viewer. Fully reflective, so one jar covers 1.20–1.21. If NMS changes break it, the plugin logs once and chat keeps working — set `bot.show-in-tab: false` to silence.

Skin: set `bot.skin.source-username` (or `source-uuid`) to copy a real Java skin.
Only textures are copied — the bot keeps its own fake UUID, so no clash when the
source player joins. Cached in `plugins/FakeMCPlayer/skin-cache.yml`, refreshed in
background. Manual `value`/`signature` (mineskin.org) still wins if set. Tab ping
jitters 18–55ms (`bot.realistic-ping`) and the join line fires once on startup.

## Limits / notes

- Bot sends chat via server broadcast formatted as `<Name> msg` — looks like a player, but `/data get`, scoreboards, etc. won't list it as real. That's intentional (no fake login / no UUID conflicts).
- Judge costs one tiny extra LLM call per group message. Set `group-mode.judge-enabled: false` for strict mention-only (zero extra cost).
- Cooldowns (`per-player-seconds`, `global-seconds`) prevent spam; proactive pings only fire in alone-mode after `idle-minutes` of silence.
# fakecraft
