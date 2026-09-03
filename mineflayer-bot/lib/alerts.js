// ============================================================
//  alerts : canal d'alerte vers Ryan.
//
//  Chaque ligne {at, text} ajoutee a agent-alerts.jsonl (a la racine du depot) est
//  postee par admin-ai.js (bot Discord) dans le salon admin verrouille
//  #outmind-ai. Zero infra nouvelle : le facteur existe deja.
//
//  Throttle par cle : une anomalie qui persiste (bot muet, panel en
//  panne) ne doit alerter qu'une fois par periode, pas a chaque tick.
// ============================================================
'use strict';
const fs = require('fs');

module.exports = function creerAlerts(alertsFile, log) {
  const lastSent = new Map(); // cle -> timestamp du dernier envoi

  function alert(key, text, throttleMs = 60 * 60 * 1000) {
    const now = Date.now();
    const last = lastSent.get(key) || 0;
    if (now - last < throttleMs) return false;
    lastSent.set(key, now);
    log(`ALERTE [${key}] ${text}`);
    try {
      fs.appendFileSync(alertsFile, JSON.stringify({ at: now, text: `[bridge] ${text}` }) + '\n');
    } catch (e) {
      log(`alerte non ecrite (${e.message}) - elle reste au moins dans ce journal`);
    }
    return true;
  }

  return { alert };
};
