# OutmindStats (plugin Paper, placeholders de stats casino)

Jar deploye : `/plugins/OutmindStats-1.0.0.jar` (chargeable par `plugman load OutmindStats-1.0.0`, aucun restart).

- `%outmind_profit%` : `&a+$1,234` / `&c-$1,234` / `&7$0`
- `%outmind_profit_raw%`, `%outmind_profit_abs%`
- profit = `%casino_balance_raw%` + `%casino_invested%` - mirrored - `%casino_bonus%`
- mirrored = net depose par joueur, ecrit par le bridge VPS dans `/plugins/OutmindStats/mirrored.json`
  apres chaque tick quand le contenu change (`publierMirrored()` dans bridge.js, commit ad987e7 du depot /root/bots).
  Le plugin relit le fichier toutes les 5 s si le mtime a change ; cles normalisees (minuscules, point Bedrock retire).

Build : `cd tools/outmindstats && JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn -q -o package` -> `target/OutmindStats-1.0.0.jar`.

Ajouts 2026-09-02 (apres-midi) :
- `%outmind_balance_short%` (52.9M), `%outmind_balance_k%` (solde/1000, valeur numerique du belowname TAB),
  `%outmind_profit_short%` (colore, +$969M), `%outmind_invested_short%`, `%outmind_playtime%` (Xd si >= 1 jour, sinon Xh Ym),
  `%outmind_discord%` (&aʟɪɴᴋᴇᴅ / &cɴᴏᴛ ʟɪɴᴋᴇᴅ) via `/plugins/OutmindStats/links.json` (liste de pseudos, poussee par le bridge
  depuis discord-bot/links.json, commit af24ff2).
- Redeploiement : delete du jar, upload (atterrit a la racine), rename vers /plugins/, `plugman reload OutmindStats`
  (l'expansion PAPI precedente est desenregistree dans onEnable, pas de doublon).
