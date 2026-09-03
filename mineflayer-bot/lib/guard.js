// ============================================================
//  guard : le coupe-circuit de la banque.
//
//  1. GEL GLOBAL : si bank-freeze.json existe, le bridge saute toutes
//     les phases d'argent (ordres admin, outbox, reconciliation) et ne
//     garde que le decor. C'est le « pm2 stop bridge » sans arreter le
//     process : les offsets n'avancent pas, rien n'est perdu, on degele
//     en supprimant le fichier (ou par un ordre admin unfreeze_all).
//
//  2. GARDE DES POUSSEES : la reconciliation pousse en jeu tout ecart
//     bals - mirrored. C'est le canal des depots, et c'est aussi par la
//     que l'incident du 2026-08-29 a fabrique 2 x 70M (un ecart lu en
//     boucle comme un depot). Trois regles, verifiees AVANT d'emettre :
//      - R1 : une poussee unitaire > PUSH_SINGLE_MAX est bloquee.
//      - R2 : deux poussees quasi identiques (meme joueur, meme signe,
//        +-1 %) de plus de PUSH_REPEAT_MIN en moins de PUSH_REPEAT_MS :
//        la signature exacte d'une boucle. Bloque + joueur gele.
//      - R3 : une poussee >= PUSH_NOTIFY est juste signalee (visibilite).
//     Un joueur gele (state.frozenPlayers) n'est plus reconcilie du
//     tout : son ecart attend un humain. Rien n'est perdu, tout est
//     rejouable apres degel (ordre admin {kind:"unfreeze"}).
// ============================================================
'use strict';
const fs = require('fs');
const path = require('path');
const { fmtExact } = require('./money');

module.exports = function creerGuard({ dir, env, alert, log }) {
  const FREEZE_FILE = path.join(dir, 'bank-freeze.json');

  const PUSH_SINGLE_MAX = Number(env.BRIDGE_PUSH_SINGLE_MAX || 250000000);
  const PUSH_REPEAT_MIN = Number(env.BRIDGE_PUSH_REPEAT_MIN || 5000000);
  const PUSH_REPEAT_MS = Number(env.BRIDGE_PUSH_REPEAT_MS || 15 * 60 * 1000);
  const PUSH_NOTIFY = Number(env.BRIDGE_PUSH_NOTIFY || 25000000);

  function frozenGlobal() {
    return fs.existsSync(FREEZE_FILE);
  }

  function freezeGlobal(reason) {
    try {
      fs.writeFileSync(FREEZE_FILE, JSON.stringify({ at: Date.now(), reason }));
    } catch {}
    alert('freeze-global', `BANQUE GELEE : ${reason}. Degel : supprimer ${FREEZE_FILE} ou ordre admin {"kind":"unfreeze_all"}.`, 30 * 60 * 1000);
  }

  function unfreezeGlobal() {
    try { fs.unlinkSync(FREEZE_FILE); } catch {}
  }

  function freezePlayer(state, player, reason) {
    state.frozenPlayers = state.frozenPlayers || {};
    if (state.frozenPlayers[player]) return;
    state.frozenPlayers[player] = { at: Date.now(), reason };
    alert(`freeze-${player}`,
      `reconciliation GELEE pour ${player} : ${reason}. Son ecart attend un humain, rien n'est perdu. `
      + `Degel : echo '{"kind":"unfreeze","player":"${player}","by":"admin"}' >> mineflayer-bot/admin-orders.jsonl`,
      30 * 60 * 1000);
  }

  function unfreezePlayer(state, player) {
    if (state.frozenPlayers && state.frozenPlayers[player]) {
      delete state.frozenPlayers[player];
      // degeler = « j'ai verifie, laisse passer » : on purge aussi l'historique
      // des poussees, sinon la poussee retentee re-declenche R2 dans la seconde
      if (state.pushLog) delete state.pushLog[player];
      log(`degel de ${player}`);
      return true;
    }
    return false;
  }

  function isFrozenPlayer(state, player) {
    return !!(state.frozenPlayers && state.frozenPlayers[player]);
  }

  // purge du journal des poussees : on ne garde que la fenetre utile
  function prunePushLog(state) {
    if (!state.pushLog) { state.pushLog = {}; return; }
    const cutoff = Date.now() - PUSH_REPEAT_MS;
    for (const [p, arr] of Object.entries(state.pushLog)) {
      const kept = arr.filter((e) => e.at > cutoff);
      if (kept.length) state.pushLog[p] = kept;
      else delete state.pushLog[p];
    }
  }

  // Notification seule (R3), pour les poussees qu'on ne bloque jamais
  function notifyBigPush(player, amount) {
    if (Math.abs(amount) >= PUSH_NOTIFY) {
      alert(`push-notify-${player}`,
        `grosse poussee en jeu : ${player} ${amount >= 0 ? '+' : ''}${fmtExact(amount)} (visibilite, non bloquee)`,
        6 * 60 * 60 * 1000);
    }
  }

  // Verdict AVANT d'emettre la poussee. NE CONCERNE QUE LES POUSSEES
  // POSITIVES (credits en jeu) : la classe d'incident visee est la
  // FABRICATION d'argent (2x70M). Bloquer une poussee negative (retrait deja
  // opere en banque) laisserait le vault du joueur plus riche que sa banque -
  // divergence encaissable en cashout, demontree en contre-verification
  // 2026-08-30. Les negatives passent toujours, avec notification.
  // Ne modifie rien d'autre que les gels : l'appelant n'enregistre la poussee
  // (recordPush) que si elle est reellement partie a l'inbox.
  function checkPush(state, player, amount) {
    if (isFrozenPlayer(state, player)) {
      return { ok: false, reason: 'joueur gele' };
    }
    const abs = Math.abs(amount);
    if (abs > PUSH_SINGLE_MAX) {
      freezePlayer(state, player,
        `poussee unitaire de ${fmtExact(amount)} > plafond ${fmtExact(PUSH_SINGLE_MAX)}`);
      return { ok: false, reason: 'poussee unitaire hors plafond' };
    }
    if (abs >= PUSH_REPEAT_MIN) {
      const hist = (state.pushLog && state.pushLog[player]) || [];
      const cutoff = Date.now() - PUSH_REPEAT_MS;
      const repeat = hist.find((e) => e.at > cutoff
        && Math.sign(e.amount) === Math.sign(amount)
        && Math.abs(e.amount - amount) <= abs * 0.01);
      if (repeat) {
        freezePlayer(state, player,
          `poussee de ${fmtExact(amount)} quasi identique a celle de ${new Date(repeat.at).toISOString()} `
          + `(${fmtExact(repeat.amount)}) - signature d'une boucle de reconciliation (incident 2x70M du 2026-08-29)`);
        return { ok: false, reason: 'poussee repetee' };
      }
    }
    if (abs >= PUSH_NOTIFY) {
      alert(`push-notify-${player}`,
        `grosse poussee en jeu : ${player} ${amount >= 0 ? '+' : ''}${fmtExact(amount)} (depot/retrait legitime a priori, simple visibilite)`,
        6 * 60 * 60 * 1000);
    }
    return { ok: true };
  }

  function recordPush(state, player, amount) {
    if (Math.abs(amount) < PUSH_REPEAT_MIN) return; // sous le seuil : aucun interet
    state.pushLog = state.pushLog || {};
    (state.pushLog[player] = state.pushLog[player] || []).push({ at: Date.now(), amount });
  }

  return {
    FREEZE_FILE,
    frozenGlobal,
    freezeGlobal,
    unfreezeGlobal,
    freezePlayer,
    unfreezePlayer,
    isFrozenPlayer,
    prunePushLog,
    checkPush,
    notifyBigPush,
    recordPush,
    limits: { PUSH_SINGLE_MAX, PUSH_REPEAT_MIN, PUSH_REPEAT_MS, PUSH_NOTIFY },
  };
};
