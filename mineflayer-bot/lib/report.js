// ============================================================
//  report : le bilan quotidien de la banque, poste au changement de
//  jour (minuit heure de Paris) dans le salon Discord admin via
//  agent-alerts.jsonl, et archive dans reports/AAAA-MM-JJ.txt.
//
//  But : que Ryan n'ait JAMAIS besoin d'aller fouiller les fichiers
//  pour savoir si la banque va bien. Tout ce qui demande un regard
//  humain (payout mort, joueur gele, derive) est dans ce message.
// ============================================================
'use strict';
const fs = require('fs');
const path = require('path');
const { readJsonLoose } = require('./fstore');
const { fmtExact } = require('./money');

module.exports = function creerReport({ dir, quotas, alertsFile, log }) {
  const REPORTS_DIR = path.join(dir, 'reports');

  function composer({ jour, capRaw, bals, state, bankState, casino }) {
    const l = [];
    l.push(`Bilan banque du ${jour} :`);

    const treasury = bankState && typeof bankState.treasury === 'number' ? bankState.treasury : null;
    const sumBals = Object.values(bals).reduce((a, v) => a + (v > 0 ? v : 0), 0);
    l.push(`- caisse Donut : ${treasury == null ? 'inconnue' : fmtExact(treasury)} ; soldes clients : ${fmtExact(sumBals)}`
      + (treasury ? ` (couverture x${(treasury / Math.max(1, sumBals)).toFixed(1)})` : ''));

    if (capRaw && capRaw.day === jour) {
      const paid = capRaw.paid || 0;
      const manual = capRaw.manualPaid || 0;
      const top = Object.entries(capRaw.players || {}).sort((a, b) => b[1] - a[1]).slice(0, 3)
        .map(([p, v]) => `${p} ${fmtExact(v)}`).join(', ');
      l.push(`- sorties du jour : ${fmtExact(paid)} via quotas${manual ? ` + ${fmtExact(manual)} manuelles` : ''}${top ? ` (top : ${top})` : ''}`);
    } else {
      l.push('- sorties du jour : aucune');
    }

    if (casino) {
      const delta = casino.profit - (state.reportLastProfit || casino.profit);
      l.push(`- casino : profit total ${fmtExact(casino.profit)} (${delta >= 0 ? '+' : ''}${fmtExact(delta)} sur la journee, ${casino.count} coups en tout)`);
      state.reportLastProfit = casino.profit;
    }

    const dead = ((bankState && bankState.pendingPayouts) || []).filter((p) => p.tries > 3 || p.suspect);
    if (dead.length) {
      l.push(`- A REGARDER : ${dead.length} cashout(s) jamais confirmes : `
        + dead.map((p) => `${p.player} ${fmtExact(p.amount)} (depuis le ${new Date(p.at).toISOString().slice(0, 10)})`).join(', '));
    }
    const frozen = Object.keys(state.frozenPlayers || {});
    if (frozen.length) {
      l.push(`- A REGARDER : reconciliation gelee pour ${frozen.join(', ')} (ordre admin unfreeze pour degeler)`);
    }
    return l.join('\n');
  }

  // appele a chaque tick ; ne poste qu'au changement de jour Paris
  function maybeDailyReport(state, { bals, bankState, casino }) {
    const today = quotas.parisDay();
    if (state.reportDay === today) return;
    const first = !state.reportDay;
    const jour = state.reportDay || today;
    state.reportDay = today;
    if (first) return; // premier demarrage : rien a bilaner

    try {
      // le fichier daily-cap porte encore la veille tant que personne n'a
      // ecrit aujourd'hui : c'est la seule fenetre pour lire son contenu brut
      const capRaw = quotas.readDailyCapRaw();
      const texte = composer({
        jour,
        capRaw,
        bals,
        state,
        bankState,
        casino: readJsonLoose(path.join(dir, 'casino-stats.json'), null) || casino,
      });
      fs.mkdirSync(REPORTS_DIR, { recursive: true });
      fs.writeFileSync(path.join(REPORTS_DIR, `${jour}.txt`), texte + '\n');
      fs.appendFileSync(alertsFile, JSON.stringify({ at: Date.now(), text: texte }) + '\n');
      log(`bilan quotidien ${jour} poste`);
    } catch (e) {
      log(`bilan quotidien rate : ${e.message}`);
    }
  }

  return { maybeDailyReport, composer };
};
