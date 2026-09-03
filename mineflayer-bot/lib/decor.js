// ============================================================
//  decor : tout ce que le bridge pousse cote serveur qui n'est PAS de
//  l'argent - whitelist, statut du PNJ bot, leaderboard, podium,
//  hologramme de la caisse. Logique reprise a l'identique de l'ancien
//  bridge.js, juste sortie de la boucle d'argent : une erreur de
//  decor ne doit jamais interrompre une phase bancaire, et ces phases
//  tournent meme quand la banque est gelee.
// ============================================================
'use strict';
const fs = require('fs');
const path = require('path');
const { readJsonLoose } = require('./fstore');
const { fmtShort } = require('./money');

module.exports = function creerDecor({ dir, env, ptero, log }) {
  const ONLINE_FILE = path.join(dir, 'online.json');
  const BOT_STATUS_FILE = path.join(dir, 'bot-status.json');
  const NPC_ID = env.BOT_NPC_ID; // PNJ Citizens renomme selon l'etat du bot, vide = desactive
  const PODIUM_IDS = (env.PODIUM_NPC_IDS || '').split(',').map((s) => s.trim()).filter(Boolean);
  // comptes staff exclus du leaderboard/podium (ils gardent solde et retraits)
  const LB_EXCLUDE = new Set((env.LEADERBOARD_EXCLUDE || '')
    .split(',').map((s) => s.trim().toLowerCase()).filter(Boolean));

  function blacklistSet() {
    const bl = readJsonLoose(path.join(dir, 'blacklist.json'), []) || [];
    return new Set(bl.map((n) => String(n).toLowerCase()));
  }

  // 3) publier la liste en ligne pour le verrou anti double-depense du bot
  function publishOnline(statusSeen) {
    if (statusSeen) {
      try { fs.writeFileSync(ONLINE_FILE, JSON.stringify(statusSeen)); } catch {}
    }
  }

  // 4) whitelist Prestigia : tout joueur du grand livre est whiteliste une
  // fois ; les blacklistes en sont retires et kickes
  async function syncWhitelist(state, bals, blSet) {
    if (!Array.isArray(state.whitelisted)) state.whitelisted = [];
    for (const player of Object.keys(bals)) {
      if (blSet.has(player.toLowerCase())) continue;
      if (!state.whitelisted.includes(player)) {
        // pseudo Bedrock (point Floodgate) : la whitelist vanilla interroge
        // Mojang et echoue ; /fwhitelist resout le XUID, gamertag SANS point
        if (player.startsWith('.')) await ptero.sendCommand(`fwhitelist add ${player.slice(1)}`);
        else await ptero.sendCommand(`whitelist add ${player}`);
        state.whitelisted.push(player);
        log(`whitelist add ${player}`);
      }
    }
    for (const name of blSet) {
      const present = state.whitelisted.find((w) => w.toLowerCase() === name);
      if (present) {
        if (present.startsWith('.')) await ptero.sendCommand(`fwhitelist remove ${present.slice(1)}`);
        else await ptero.sendCommand(`whitelist remove ${present}`);
        await ptero.sendCommand(`kick ${present} You are blacklisted from the Outmind Casino`);
        state.whitelisted = state.whitelisted.filter((w) => w !== present);
        log(`blacklist : whitelist remove + kick ${present}`);
      }
    }
  }

  // 5) etat du bot cote serveur (verrou du /cashout dans le plugin, fraicheur
  // 2 min cote plugin donc reecriture reguliere) + PNJ Citizens.
  // forceOffline : pendant un gel de la banque, on publie offline pour que le
  // plugin FERME le menu /cashout au lieu d'empiler des ordres que le bridge
  // ne consommera pas (revue 2026-08-30).
  async function syncBotStatus(state, forceOffline) {
    const st = readJsonLoose(BOT_STATUS_FILE, null);
    const botOnline = !forceOffline && !!(st && st.inGame && Date.now() - st.at < 90000);
    if (state.npcOnline !== botOnline || Date.now() - (state.botStatusWrittenAt || 0) > 60000) {
      await ptero.writeFile('/plugins/OutMindLink/botstatus.json', JSON.stringify({ online: botOnline, at: Date.now() }));
      state.botStatusWrittenAt = Date.now();
    }
    if (NPC_ID && state.npcOnline !== botOnline) {
      // le nom du PNJ ne porte que le statut ; la ligne titre au-dessus est un
      // hologram trait Citizens statique, pose une fois a la main
      await ptero.sendCommand(`npc select ${NPC_ID}`);
      await ptero.sendCommand(`npc rename ${botOnline ? '&a&lᴏɴʟɪɴᴇ' : '&c&lᴏꜰꜰʟɪɴᴇ'}`);
      state.npcOnline = botOnline;
      log(`pnj bot -> ${botOnline ? 'ONLINE' : 'OFFLINE'}`);
    }
    return botOnline;
  }

  // 6) leaderboard du spawn + PNJ du podium
  async function syncLeaderboard(state, bals, blSet) {
    const top = Object.entries(bals)
      .filter(([p, v]) => v >= 1 && !blSet.has(p.toLowerCase()) && !LB_EXCLUDE.has(p.toLowerCase()))
      .sort((a, b) => b[1] - a[1])
      .slice(0, 3);
    const medals = ['&#FFD700', '&#C0C0C0', '&#CD7F32']; // or, argent, bronze
    const lines = [0, 1, 2].map((i) => top[i]
      ? `${medals[i]}&l#${i + 1} &f${top[i][0]} &8- ${medals[i]}$${fmtShort(top[i][1])}`
      : '&8---');
    const sig = JSON.stringify(lines);
    if (state.leaderboardSig !== sig) {
      if (!state.leaderboardInit) {
        for (const l of lines) await ptero.sendCommand(`dh line add leaderboard 1 ${l}`);
        state.leaderboardInit = true;
      } else {
        for (let i = 0; i < 3; i++) {
          await ptero.sendCommand(`dh line set leaderboard 1 ${i + 2} ${lines[i]}`);
        }
      }
      state.leaderboardSig = sig;
      log(`leaderboard : ${lines.join(' | ').replace(/&#?[0-9A-Fa-f]{6}|&[0-9a-fl]/g, '')}`);
    }

    // PNJ du podium : skin et nom du joueur classe, solde en hologramme.
    // Le skin n'est refetch chez Mojang que si le NOM change.
    if (PODIUM_IDS.length === 3) {
      if (!Array.isArray(state.podiumNames)) state.podiumNames = [null, null, null];
      if (!Array.isArray(state.podiumSig)) state.podiumSig = [null, null, null];
      if (!Array.isArray(state.podiumHolo)) state.podiumHolo = [false, false, false];
      for (let i = 0; i < 3; i++) {
        const name = top[i] ? top[i][0] : null;
        const balTxt = top[i] ? `${medals[i]}&l$${fmtShort(top[i][1])}` : '&8---';
        const sig2 = `${name}|${balTxt}`;
        if (state.podiumSig[i] === sig2) continue;
        await ptero.sendCommand(`npc select ${PODIUM_IDS[i]}`);
        if (state.podiumNames[i] !== name) {
          if (name) {
            await ptero.sendCommand(`npc rename ${medals[i]}&l${name}`);
            await ptero.sendCommand(`npc skin ${name}`);
          } else {
            await ptero.sendCommand(`npc rename &8---`);
          }
          state.podiumNames[i] = name;
        }
        if (!state.podiumHolo[i]) {
          await ptero.sendCommand(`npc hologram add ${balTxt}`);
          state.podiumHolo[i] = true;
        } else {
          await ptero.sendCommand(`npc hologram set 0 ${balTxt}`);
        }
        state.podiumSig[i] = sig2;
        log(`podium #${i + 1} -> ${name || 'vide'} (${balTxt.replace(/&#[0-9A-Fa-f]{6}|&[0-9a-fl]/g, '')})`);
      }
    }
  }

  // 7) hologramme « Outmind Fortune » : la caisse du bot Donut en ligne 2
  async function syncFortune(state) {
    const treasury = (readJsonLoose(path.join(dir, 'bank-state.json'), {}) || {}).treasury;
    if (typeof treasury === 'number' && isFinite(treasury)) {
      const txt = `&#FFD700&l$${fmtShort(treasury)}`;
      if (state.fortuneTxt !== txt) {
        if (!state.fortuneInit) {
          await ptero.sendCommand(`dh line add Balance 1 ${txt}`);
          state.fortuneInit = true;
        } else {
          await ptero.sendCommand(`dh line set Balance 1 2 ${txt}`);
        }
        state.fortuneTxt = txt;
        log(`fortune -> ${txt.replace(/&#[0-9A-Fa-f]{6}|&l/g, '')}`);
      }
    }
  }

  return { blacklistSet, publishOnline, syncWhitelist, syncBotStatus, syncLeaderboard, syncFortune };
};
