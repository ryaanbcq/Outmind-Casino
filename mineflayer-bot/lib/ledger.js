// ============================================================
//  ledger : le grand livre (balances.json).
//
//  SEUL point d'ecriture pour les deux process (bot.js et bridge.js).
//  Chaque mutation :
//   - prend le verrou inter-process (un depot du bot pendant un tick du
//     bridge ne peut plus etre ecrase par une ecriture croisee),
//   - relit le fichier sous le verrou (jamais de copie perimee),
//   - ecrit en atomique,
//   - laisse une ligne d'audit avant/apres dans ledger-audit.jsonl.
//
//  L'audit ne sert a rien tant que tout va bien ; le jour ou un solde
//  est conteste, il dit qui a bouge quoi, quand, et de combien.
// ============================================================
'use strict';
const path = require('path');
const {
  readJsonStrict, writeJsonAtomic, appendLine, withLock,
} = require('./fstore');

module.exports = function creerLedger(dir) {
  const BAL_FILE = path.join(dir, 'balances.json');
  const AUDIT_FILE = path.join(dir, 'ledger-audit.jsonl');
  const LOCK = path.join(dir, '.ledger.lock');

  // lecture seule, SANS verrou : pour l'affichage des soldes. Grace aux
  // ecritures atomiques on lit toujours un fichier entier et valide.
  function load() {
    // prototype nul : un pseudo « toString » ou « __proto__ » ne trouve plus
    // de propriete heritee (audit 2026-09-03, H1)
    return Object.assign(Object.create(null), readJsonStrict(BAL_FILE, {}));
  }

  // mutation generique : fn recoit le grand livre fraichement relu et un
  // enregistreur d'evenements d'audit ; opts.after tourne encore SOUS le
  // verrou, apres l'ecriture des soldes — c'est la que le bridge persiste son
  // offset d'outbox. ATTENTION : ce sont DEUX ecritures atomiques distinctes,
  // pas une transaction — un kill -9 pile entre les deux rejoue la ligne au
  // redemarrage (fenetre de quelques microsecondes ; l'audit garde la double
  // trace pour la reparation). Les signaux d'arret, eux, sont differes par le
  // bridge jusqu'a la fin du tick.
  function mutate(op, fn, opts = {}) {
    return withLock(LOCK, () => {
      const bals = load();
      const events = [];
      const note = (player, delta, extra) => {
        const before = bals[player] || 0;
        events.push({ player, delta, before, ...extra });
      };
      const result = fn(bals, note);
      writeJsonAtomic(BAL_FILE, bals, true);
      for (const ev of events) {
        appendLine(AUDIT_FILE, {
          at: new Date().toISOString(),
          op,
          player: ev.player,
          delta: ev.delta,
          before: ev.before,
          after: bals[ev.player],
          ...(ev.by ? { by: ev.by } : {}),
        });
      }
      if (opts.after) opts.after(bals);
      return result;
    });
  }

  return { file: BAL_FILE, load, mutate };
};
