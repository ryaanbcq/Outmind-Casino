// ============================================================
//  backups : snapshots rotatifs des fichiers d'etat de la banque.
//
//  Toutes les heures, copie de {balances, bridge-state, daily-cap,
//  bank-state, investors}.json dans backups/AAAA-MM-JJ_HH/. On garde
//  48 snapshots horaires ; le snapshot de minuit est promu quotidien
//  (backups/daily/AAAA-MM-JJ/) et garde 14 jours.
//
//  C'est l'assurance-vie du grand livre : n'importe quel incident se
//  repare en comparant avec l'etat d'il y a une heure, au pire.
// ============================================================
'use strict';
const fs = require('fs');
const path = require('path');

const FILES = ['balances.json', 'bridge-state.json', 'daily-cap.json', 'bank-state.json', 'investors.json'];

module.exports = function creerBackups(dir, log) {
  const ROOT = path.join(dir, 'backups');
  const DAILY = path.join(ROOT, 'daily');
  const KEEP_HOURLY = 48;
  const KEEP_DAILY = 14;

  function stamp(d) {
    const p = (n) => String(n).padStart(2, '0');
    return `${d.getUTCFullYear()}-${p(d.getUTCMonth() + 1)}-${p(d.getUTCDate())}_${p(d.getUTCHours())}`;
  }

  function copyInto(dest) {
    fs.mkdirSync(dest, { recursive: true });
    for (const f of FILES) {
      try { fs.copyFileSync(path.join(dir, f), path.join(dest, f)); } catch {} // absent = pas grave
    }
  }

  function prune(root, keep) {
    let entries = [];
    try { entries = fs.readdirSync(root).filter((e) => e !== 'daily').sort(); } catch { return; }
    for (const e of entries.slice(0, Math.max(0, entries.length - keep))) {
      fs.rmSync(path.join(root, e), { recursive: true, force: true });
    }
  }

  // appele a chaque tick ; ne fait quelque chose qu'au changement d'heure
  function maybeSnapshot(state) {
    const now = new Date();
    const s = stamp(now);
    if (state.backupStamp === s) return;
    state.backupStamp = s;
    try {
      copyInto(path.join(ROOT, s));
      prune(ROOT, KEEP_HOURLY);
      if (now.getUTCHours() === 23) {
        // ~minuit Paris (23h UTC en ete) : promotion quotidienne
        copyInto(path.join(DAILY, s.slice(0, 10)));
        prune(DAILY, KEEP_DAILY);
      }
      log(`backup ${s} (${FILES.length} fichiers)`);
    } catch (e) {
      log(`backup rate : ${e.message}`);
    }
  }

  return { maybeSnapshot, ROOT };
};
