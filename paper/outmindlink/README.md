# OutMindLink

Paper plugin bridging the OutMind bank (DonutSMP side) with the server economy (Vault).

- `/outmind cashout <amount|max>` - withdraw from the in-game vault; the request is written to an outbox consumed by the VPS bridge, which pays the player on DonutSMP (bounded only by daily quotas).
- `/invest <amount|max>` - donate to the house capital (counts toward the Investor tier).
- `/verify` - one-time code to link a Discord account.
- Daily reward, PlaceholderAPI expansion, console signals (`cashoutpending`, `cashoutdone`, `cashoutrefused`) consumed by Skript for player-facing UX.

## Provenance

This is **reconstructed source** (CFR decompiler) of the production jar, published by the plugin's owner. The original source was lost; the production jar also carries local binary patches (sound/UX tweaks documented in the repo history), which are therefore included in this reconstruction. Since 2026-09-03 this source is buildable (`mvn package`, pom included) and IS what production runs: the decompile artifacts were fixed, the rebuilt jar was decompile-diffed against the production jar to prove semantic equivalence, and the optional cashout 2FA listener was added on top (dialog submissions arrive through PlayerCustomClickEvent and leave as sha256 pre-hashes, so the secret never touches a command, the chat, a log line, or the append-only outbox).
