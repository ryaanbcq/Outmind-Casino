# OutMindLink

Paper plugin bridging the OutMind bank (DonutSMP side) with the server economy (Vault).

- `/outmind cashout <amount|max>` - withdraw from the in-game vault; the request is written to an outbox consumed by the VPS bridge, which pays the player on DonutSMP (bounded only by daily quotas).
- `/invest <amount|max>` - donate to the house capital (counts toward the Investor tier).
- `/verify` - one-time code to link a Discord account.
- Daily reward, PlaceholderAPI expansion, console signals (`cashoutpending`, `cashoutdone`, `cashoutrefused`) consumed by Skript for player-facing UX.

## Provenance

This is **reconstructed source** (CFR decompiler) of the production jar, published by the plugin's owner. The original source was lost; the production jar also carries local binary patches (sound/UX tweaks documented in the repo history), which are therefore included in this reconstruction. It compiles against Paper API 1.21 + Vault + PlaceholderAPI + Gson, but no build script is provided - treat it as reference code for auditing the money flow.
