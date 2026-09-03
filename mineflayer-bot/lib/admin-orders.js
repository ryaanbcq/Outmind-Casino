// ============================================================
//  admin-orders : les ordres du panneau /admin Discord (et du salon
//  agent), consommes par le bridge.
//
//  Pourquoi un fichier plutot qu'une ecriture directe du bot Discord
//  dans balances.json : le grand livre a deja deux ecrivains, un
//  troisieme multipliait les ecritures perdues. Depuis la refonte le
//  ledger est verrouille, mais le canal fichier reste le bon design :
//  ordres traces, rejouables, audites.
//
//  DURABILITE : l'offset est persiste ORDRE PAR ORDRE, dans le meme
//  verrou que la mutation du ledger. L'ancien code ne le sauvait qu'en
//  fin de tick : un crash au milieu rejouait des credits deja payes.
//
//  Kinds : credit, debit, pay, transfer (inchanges) + unfreeze /
//  unfreeze_all / freeze (degel-gel du coupe-circuit, sans montant).
// ============================================================
'use strict';
const path = require('path');
const fs = require('fs');
const { appendLine } = require('./fstore');

module.exports = function creerAdminOrders({ dir, ledger, quotas, guard, log }) {
  const ORDERS_FILE = path.join(dir, 'admin-orders.jsonl');
  const PAYOUTS_FILE = path.join(dir, 'donut-payouts.jsonl');
  const TX_FILE = path.join(dir, 'transactions.jsonl');

  // Prefixe point Bedrock (5e morsure, 2026-08-29) : un vouch/credit admin
  // arrive parfois avec le pseudo sans son point Floodgate. Si le compte
  // pointe existe et pas le nu, on recolle le point — sinon le credit cree
  // une cle fantome que la reconciliation pousse vers un profil inexistant.
  function reglerPoint(player, bals) {
    if (String(player).startsWith('.') || (player in bals)) return player;
    const pointe = Object.keys(bals).find((k) => k.toLowerCase() === ('.' + player).toLowerCase());
    if (pointe) {
      log(`ordre admin ${player} -> ${pointe} (point Bedrock recolle)`);
      return pointe;
    }
    return player;
  }

  function consume(state, saveState) {
    let lines;
    try { lines = fs.readFileSync(ORDERS_FILE, 'utf8').split('\n'); } catch { return; }
    if (typeof state.adminOffset !== 'number') state.adminOffset = 0;
    const complete = lines.length - 1; // la derniere entree est '' ou une ligne partielle
    if (complete < state.adminOffset) {
      // fichier retreci (rotation ? edition ?) : ne JAMAIS rejouer des ordres
      // d'argent — on saute a la fin et on previent
      log(`admin-orders.jsonl a retreci (${complete} < ${state.adminOffset}) : offset repositionne en fin, AUCUN rejeu`);
      state.adminOffset = complete;
      saveState();
      return;
    }
    if (complete === state.adminOffset) return;

    for (let i = state.adminOffset; i < complete; i++) {
      const commit = () => { state.adminOffset = i + 1; saveState(); };
      const line = lines[i].trim();
      if (!line) { commit(); continue; }
      let o;
      try { o = JSON.parse(line); } catch { log(`ordre admin illisible ligne ${i}`); commit(); continue; }

      // ordres sans montant : pilotage du coupe-circuit
      if (o.kind === 'unfreeze') {
        if (o.player) guard.unfreezePlayer(state, o.player);
        commit(); continue;
      }
      if (o.kind === 'unfreeze_all') {
        state.frozenPlayers = {};
        state.pushLog = {}; // meme logique que le degel individuel
        guard.unfreezeGlobal();
        log(`degel general par ${o.by || '?'}`);
        commit(); continue;
      }
      if (o.kind === 'freeze') {
        guard.freezeGlobal(`ordre admin de ${o.by || '?'} : ${o.reason || 'sans motif'}`);
        commit(); continue;
      }

      const amount = Number(o.amount);
      const NOM_OK = /^\.?[A-Za-z0-9_]{3,16}$/;
      const nomOk = (n) => typeof n === 'string' && NOM_OK.test(n) && !(n in Object.prototype) && !(n.replace(/^\./, '').toLowerCase() in Object.prototype);
      if (!nomOk(o.player) || !isFinite(amount) || amount <= 0 || (o.kind === 'transfer' && !nomOk(o.to))) {
        log(`ordre admin REFUSE (nom ou montant invalide) ligne ${i} : ${JSON.stringify(o).slice(0, 120)}`); commit(); continue;
      }

      if (o.kind === 'credit' || o.kind === 'debit') {
        // le credit/debit ne touche PAS a state.mirrored : la reconciliation
        // poussera la difference en jeu, exactement comme un depot
        ledger.mutate(`admin-${o.kind}`, (bals, note) => {
          o.player = reglerPoint(o.player, bals);
          const before = bals[o.player] || 0;
          note(o.player, o.kind === 'credit' ? amount : -Math.min(before, amount), { by: o.by });
          bals[o.player] = o.kind === 'credit' ? before + amount : Math.max(0, before - amount);
          log(`admin ${o.kind} ${o.player} ${amount} par ${o.by} (${before} -> ${bals[o.player]}) : ${o.reason || 'sans motif'}`);
          // le bonus vouch et la prime boost sont des evenements publics : ils
          // partent au journal des transactions, relaye dans #past-transaction
          if (o.kind === 'credit' && String(o.by || '').startsWith('vouch:')) {
            appendLine(TX_FILE, { at: new Date().toISOString(), type: 'vouch-bonus', player: o.player, amount, balance: bals[o.player] });
          }
          if (o.kind === 'credit' && String(o.by || '').startsWith('boost:')) {
            appendLine(TX_FILE, { at: new Date().toISOString(), type: 'boost-bonus', player: o.player, amount, balance: bals[o.player] });
          }
        }, { after: commit });
      } else if (o.kind === 'pay') {
        // Paiement direct sur DonutSMP, decision humaine. Compteur manualPaid
        // (ne mord pas sur les quotas joueurs), et DEDUPLIQUE : deux ordres
        // identiques a moins de 2 min = un double clic, pas une intention
        // (vecu : 42M partis pour un cashout de 21M). o.force saute la barriere.
        // Le point Bedrock est recolle ICI AUSSI (regression attrapee en revue :
        // un /pay au nom sans point part vers un compte inexistant ou pire, un
        // homonyme Java).
        o.player = reglerPoint(o.player, ledger.load());
        const key = `${o.player.toLowerCase()}|${amount}`;
        const last = (state.lastAdminPays || {})[key] || 0;
        if (!o.force && Date.now() - last < 2 * 60 * 1000) {
          log(`admin pay IGNORE (doublon a ${Math.round((Date.now() - last) / 1000)}s) : ${o.player} ${amount} par ${o.by}`);
          commit(); continue;
        }
        state.lastAdminPays = state.lastAdminPays || {};
        state.lastAdminPays[key] = Date.now();
        quotas.addManualPaid(o.player, amount);
        // commit AVANT l'ordre de /pay : un crash entre les deux donne un pay
        // manquant (re-emis a la main avec force), jamais un pay double — meme
        // doctrine que le handler cashout du bridge
        commit();
        appendLine(PAYOUTS_FILE, { at: Date.now(), player: o.player, amount });
        log(`admin pay ${o.player} ${amount} par ${o.by} : ${o.reason || 'sans motif'}`);
      } else if (o.kind === 'transfer') {
        // Transfert joueur -> joueur, ATOMIQUE : debit et credit dans la meme
        // mutation, donc conservation stricte — on credite exactement ce qu'on
        // a pu debiter, jamais plus.
        if (!o.to) { log(`transfer invalide ligne ${i} : cible absente`); commit(); continue; }
        ledger.mutate('admin-transfer', (bals, note) => {
          o.player = reglerPoint(o.player, bals);
          const from = bals[o.player] || 0;
          const taken = Math.min(from, amount);
          if (taken < 1) { log(`transfer IGNORE (solde vide) : ${o.player} -> ${o.to} ${amount} par ${o.by}`); return; }
          note(o.player, -taken, { by: o.by });
          note(o.to, taken, { by: o.by });
          bals[o.player] = from - taken;
          bals[o.to] = (bals[o.to] || 0) + taken;
          log(`transfer ${o.player} -> ${o.to} ${taken}${taken < amount ? ` (demande ${amount}, solde insuffisant)` : ''} par ${o.by} : ${o.reason || 'sans motif'}`);
          appendLine(TX_FILE, {
            at: new Date().toISOString(), type: 'transfer', player: o.player, to: o.to,
            amount: taken, balanceFrom: bals[o.player], balanceTo: bals[o.to],
          });
        }, { after: commit });
      } else {
        log(`ordre admin inconnu "${o.kind}" ligne ${i}`);
        commit();
      }
    }
  }

  return { consume };
};
