# Outmind Casino

Backend of the **Outmind Casino**, a Minecraft casino whose chips are real money
from a public survival server. Players deposit by paying a bank account on the
public server, play on a private Paper server, and withdraw back to the public
server. This repository contains the three Node.js processes that move the
money and talk to players. The Paper plugins are not part of this repository.

```
 public server (DonutSMP)          this repo (VPS, pm2)               private Paper server
 ┌──────────────────┐   /pay    ┌──────────────────────┐   panel API   ┌────────────────────┐
 │ player accounts  │ ────────► │ mineflayer-bot/bot.js │ ───────────► │ OutMindLink plugin │
 │                  │ ◄──────── │   (bank account)      │   inbox.jsonl │  (vault, cashouts) │
 └──────────────────┘  payouts  └──────────┬───────────┘               └─────────┬──────────┘
                                           │ balances.json (ledger)                │ outbox
                                ┌──────────┴───────────┐   console cmds          │
                                │ mineflayer-bot/bridge │ ◄─────────────────────────┘
                                └──────────┬───────────┘
                                           │ shared JSON state
                                ┌──────────┴───────────┐
                                │ discord-bot/bot.js    │  linking, /cashout, /chain, stats,
                                └──────────────────────┘  AI desks, admin tools
```

## Components

| Directory | Process | Role |
|---|---|---|
| `mineflayer-bot/bot.js` | `mineflayer-bot` | Bank account on the public server. Detects incoming payments, credits the ledger, pays withdrawals, answers `/msg`, runs the watchdogs and the leaderboard NPC decor. |
| `mineflayer-bot/bridge.js` | `bridge` | Reconciles the ledger with the in-game vault through the Pterodactyl panel API: reads the plugin outbox (cashout requests, game deltas), writes the inbox (credits), sends console signals to the plugin, publishes stats files. |
| `mineflayer-bot/lib/` | shared | `ledger.js` is the **only** writer of `balances.json` (inter-process lock, atomic writes, audit log). `quotas.js` handles the daily caps with an atomic `reserve()`. `guard.js` is the circuit breaker. `admin-orders.js`, `alerts.js`, `backups.js`, `report.js`, `decor.js`, `money.js`, `fstore.js`, `ptero.js` are self-explanatory. |
| `mineflayer-bot/panel.js` | optional | Small local web panel to watch the bank. |
| `discord-bot/bot.js` | `discord-bot` | Discord side: account linking (`/verify` code from the game), `/cashout`, `/chain` (double or nothing), `/stats`, `/vouch`, `/balance`, showcase channels, admin commands. The auto-deposit feature (`autodeposit.js`, `bank-auth-worker.js`) is disabled by default and its payment worker is not published. |
| `discord-bot/public-ai.js`, `admin-ai.js` | inside `discord-bot` | Optional LLM desks: a public helper for players and an admin assistant. Both are off when no API key is configured. |

## Paper server side (`paper/`)

The casino runs on a Paper 1.21.11 server. What is ours is published here:

| Directory | What it is |
|---|---|
| `paper/buckshot/plugin` | **Donut's Buckshot**, a Buckshot-Roulette style minigame plugin (Maven, Java 21): solo tables against a Citizens dealer NPC, PvP duel tables with a pot, multi-table support, PlaceholderAPI placeholders, Bedrock (Geyser) fallbacks. Build with `mvn package`. |
| `paper/buckshot/resourcepack` | Its resource pack (models, textures, font glyphs, `sounds.json`). The audio files are not included: most of them are third-party recordings. Drop your own `.ogg` files under `assets/rr/sounds/` with the names listed in `sounds.json`. |
| `paper/buckshot/build_merged_pack.py`, `build_bedrock_pack.py` | Merge the pack with another pack and build the Bedrock `.mcpack` for Geyser. |
| `paper/outmindstats` | Tiny plugin exposing the stats placeholders used by the scoreboard and the name tags (`%outmind_profit%`, `%outmind_balance_short%`, `%outmind_playtime%`, `%outmind_discord%`). Reads `mirrored.json` and `links.json`, which the bridge publishes. |
| `paper/skript` | Skript scripts: cashout status feedback (action bar, sounds, error dialogs), dealer giggle, luck potion bottle cleanup. |
| `paper/scoreboard` | SimpleScore scoreboard config and the script that generates it. |

Not included because they are third-party or closed source: **OutMindLink** (the vault plugin that holds
balances in game, handles `/cashout`, `/invest` and the daily reward; the bridge talks to it through its
outbox/inbox files and console commands), **NitroCasino** and **Vegas** (commercial casino game plugins).
The odds of those games are theirs, not ours.

## Money model

- The in-game vault is the source of truth for withdrawals. The ledger (`balances.json`) records what the bank owes and what was mirrored into the game; the bridge pushes every difference between the two into the game.
- Both sides of a movement always change by the same amount. Breaking that invariant creates money.
- Withdrawals are bounded only by quotas: a per-player daily cap by rank (Gambler / Investor) and a house cap expressed as a percentage of the treasury. Quotas reset at midnight Paris time.
- The bridge emits credits in write-ahead batches with sequence numbers; the plugin deduplicates on the sequence, so a retried batch is applied exactly once.
- Circuit breaker: a single push above a threshold is blocked, two near-identical large pushes in a short window freeze the player and alert the admin channel. Negative pushes are never blocked.
- A payout is confirmed only by the public server's own "You paid" message. A payment sent right before a disconnect is marked suspect and never retried automatically.

## Requirements

- Node.js 22 or newer
- pm2 (`npm i -g pm2`)
- A Pterodactyl panel client API key for the Paper server, and the OutMindLink plugin running on it
- A Minecraft account for the bank bot (Microsoft auth) and a Discord application

## Setup

```bash
git clone https://github.com/ryaanbcq/Outmind-Casino.git
cd Outmind-Casino
(cd mineflayer-bot && npm install)
(cd discord-bot && npm install)
cp mineflayer-bot/.env.example mineflayer-bot/.env
cp discord-bot/.env.example discord-bot/.env
cp ecosystem.config.example.js ecosystem.config.js
# fill both .env files, then:
pm2 start ecosystem.config.js
pm2 logs
```

The first start of `mineflayer-bot` prints a Microsoft device-code link to log
the bank account in. The stats cards (`/stats`) draw with the TrueType fonts
found in `FONTS_DIR` (`segoeui.ttf`, `segoeuib.ttf`, `seguisb.ttf` by default,
not shipped here because of their license): drop any fonts with those names or
adjust `discord-bot/stats.js`. State files (`balances.json`, `daily-cap.json`,
`links.json`, `bank-state.json`, audit logs) are created next to the scripts
and are ignored by git.

## Operations

- Deploy a change: edit, `node --check <file>`, `pm2 restart <process>`, commit.
- Soft freeze of the bank without stopping the process: `touch mineflayer-bot/bank-freeze.json` (deposits keep being counted, payouts wait). Remove the file to resume.
- Admin orders are appended to `mineflayer-bot/admin-orders.jsonl`, for example `{"kind":"unfreeze","player":"Name","by":"admin"}`. Never edit the ledger files while the processes run.
- Hourly rotating backups live in `mineflayer-bot/backups/`.

## Security

See [SECURITY.md](SECURITY.md) to report a vulnerability. Secrets and state
never enter git: everything sensitive is read from `.env` files and JSON state
files listed in `.gitignore`.

## License

[MIT](LICENSE).
