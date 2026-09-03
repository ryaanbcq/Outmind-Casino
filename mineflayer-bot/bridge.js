// ============================================================
//  Pont OutMind <-> PrestigiaSMP : synchronise balances.json (banque
//  DonutSMP) avec l'economie Vault du serveur via l'API Pterodactyl et
//  le plugin OutMindLink.
//
//  Refonte 2026-08-30. Le comportement visible (formats de fichiers,
//  protocole inbox/outbox, messages joueurs, logs) est inchange ; ce
//  qui change est la solidite :
//   - le grand livre passe par lib/ledger (verrou inter-process avec
//     bot.js, ecritures atomiques, audit),
//   - les quotas par lib/quotas (memes regles pour les deux portes),
//   - chaque ligne d'argent de l'outbox est validee PUIS committee
//     offset compris, dans le meme verrou : un crash ne rejoue plus
//     tout le tick,
//   - la reconciliation est en deux phases : rien n'est mute tant que
//     l'inbox n'est pas ecrite (un echec d'ecriture ne perd plus de
//     depot),
//   - lib/guard coupe-circuit : gel global par fichier, detecteur de
//     poussees en boucle (incident 2x70M), plafond unitaire,
//   - l'outbox est lue en HTTP Range incrementale (elle pese des
//     dizaines de Mo, la retelecharger toutes les 10 s brulait la
//     bande passante du VPS),
//   - backups horaires, bilan quotidien a minuit Paris dans le salon
//     Discord admin, watchdogs (bot muet, panel en panne, cashouts
//     morts).
//
//  L'INVARIANT VITAL reste le meme : dans le handler cashout, bals et
//  mirrored descendent du MEME montant. Tout ecart bals - mirrored est
//  pousse en jeu par la reconciliation (canal des depots) : les faire
//  bouger de montants differents FABRIQUE de l'argent.
// ============================================================
'use strict';
require('dotenv').config();
const fs = require('fs');
const path = require('path');

const DIR = process.env.OUTMIND_DATA_DIR || __dirname;

const {
  CorruptStateError, readJsonStrict, readJsonLoose, writeJsonAtomic, appendLine, splitCompleteLines,
} = require('./lib/fstore');
const { fmtExact } = require('./lib/money');

function log(m) { console.log(`[bridge] [${new Date().toISOString()}] ${m}`); }

const ALERTS_FILE = process.env.AGENT_ALERTS_FILE || path.join(__dirname, '..', 'agent-alerts.jsonl');
const { alert } = require('./lib/alerts')(ALERTS_FILE, log);

const STATE_FILE = path.join(DIR, 'bridge-state.json');
const CASINO_STATS = path.join(DIR, 'casino-stats.json');
const CASINO_LOG = path.join(DIR, 'casino-deltas.jsonl');
const BOT_STATUS_FILE = path.join(DIR, 'bot-status.json');
const PAYOUTS_FILE = path.join(DIR, 'donut-payouts.jsonl');       // ordres de /pay pour le bot
const REFUSALS_FILE = path.join(DIR, 'cashout-refusals.jsonl');   // refus du plugin, lus par le bot Discord
const PAYOUT_RESULTS_FILE = path.join(DIR, 'payout-results.jsonl'); // /pay confirmes par le bot
const INBOX = '/plugins/OutMindLink/inbox.jsonl';
const OUTBOX = '/plugins/OutMindLink/outbox.jsonl';

const ledger = require('./lib/ledger')(DIR);
const quotas = require('./lib/quotas')(DIR, process.env);
const guard = require('./lib/guard')({ dir: DIR, env: process.env, alert, log });
const backups = require('./lib/backups')(DIR, log);
const report = require('./lib/report')({ dir: DIR, quotas, alertsFile: ALERTS_FILE, log });

let ptero;
try { ptero = require('./lib/ptero')(process.env, log); }
catch (e) { console.error(`[bridge] ${e.message}`); process.exit(1); }

const decor = require('./lib/decor')({ dir: DIR, env: process.env, ptero, log });
const adminOrders = require('./lib/admin-orders')({ dir: DIR, ledger, quotas, guard, log });

// ---------- etat du bridge ----------
// bridge-state.json n'a QU'UN ecrivain (ce process) : pas de verrou, mais
// lecture stricte — un state corrompu relu comme vide remettrait outboxOffset
// a 0 et rejouerait TOUT l'historique de l'outbox (des annees de deltas et de
// cashouts). Fail-stop obligatoire.
const STATE_DEFAULTS = { nextSeq: 1, mirrored: {}, outboxOffset: 0, retryAfter: {}, pluginLastSeq: 0 };
let state;
let bals0;
try {
  state = { ...STATE_DEFAULTS, ...readJsonStrict(STATE_FILE, {}) };
  bals0 = ledger.load();
} catch (e) {
  if (e instanceof CorruptStateError) {
    alert('boot-corrupt', `DEMARRAGE REFUSE : ${e.message}. La banque ne tourne pas sur un etat illisible — restaurer depuis backups/ puis relancer.`, 0);
    console.error(`[bridge] ${e.message}`);
    process.exit(1);
  }
  throw e;
}
// coherence croisee : un grand livre vide avec un miroir plein, c'est un
// balances.json perdu — la reconciliation debiterait tout le monde en jeu
if (Object.keys(bals0).length === 0 && Object.keys(state.mirrored).some((p) => Math.abs(state.mirrored[p]) >= 1)) {
  alert('boot-divergence', 'DEMARRAGE REFUSE : balances.json est vide mais le miroir ne l\'est pas. Restaurer balances.json depuis backups/ avant de relancer.', 0);
  console.error('[bridge] balances.json vide mais mirrored non vide : arret.');
  process.exit(1);
}
// menage : champs morts et retryAfter expires depuis plus de 24 h
delete state.payoutDay;
delete state.payoutToday;
for (const [p, ts] of Object.entries(state.retryAfter)) {
  if (Date.now() - ts > 24 * 60 * 60 * 1000) delete state.retryAfter[p];
}

function saveState() { writeJsonAtomic(STATE_FILE, state, true); }

function treasuryNow() {
  const t = (readJsonLoose(path.join(DIR, 'bank-state.json'), {}) || {}).treasury;
  return (typeof t === 'number' && isFinite(t)) ? t : null;
}

// comptabilite du casino : chaque delta en jeu est un coup, delta negatif =
// le joueur perd (profit maison). Stats pures, aucun effet sur l'argent.
function recordCasino(player, delta) {
  let s = { since: Date.now(), profit: 0, playerWins: 0, playerLosses: 0, count: 0 };
  s = { ...s, ...readJsonLoose(CASINO_STATS, {}) };
  s.profit -= delta;
  if (delta >= 0) s.playerWins += delta; else s.playerLosses += -delta;
  s.count++;
  s.updatedAt = Date.now();
  writeJsonAtomic(CASINO_STATS, s, true);
  appendLine(CASINO_LOG, { at: Date.now(), player, delta });
}

let inboxCache = null; // contenu connu de l'inbox (seul le bridge y ecrit)

// ---------- traitement d'une ligne d'outbox ----------
// ctx.commitLine() persiste l'avancee (offsets + state) ; pour les lignes
// d'argent il est execute DANS le verrou du ledger, juste apres l'ecriture
// des soldes : "solde applique" et "ligne consommee" sont indissociables.
function processOutboxLine(o, ctx) {
  if (o.type === 'status') {
    ctx.statusSeen = { at: Date.now(), players: o.online || [] };
    state.pluginLastSeq = Math.max(state.pluginLastSeq, o.lastSeq || 0);
    ctx.advance();

  } else if (o.type === 'delta') {
    // l'argent purement Prestigia (500k de bienvenue) n'est pas adosse a la
    // banque : une perte qui passerait le solde banque sous 0 est absorbee
    // (clamp), et le miroir ne bouge que de la part reellement appliquee,
    // sinon la reconciliation rembourserait la perte en boucle
    if (typeof o.delta !== 'number' || !isFinite(o.delta) || typeof o.player !== 'string' || !o.player) {
      log(`delta invalide ignore : ${JSON.stringify(o).slice(0, 120)}`);
      ctx.commitLine();
      return;
    }
    ledger.mutate('casino-delta', (bals, note) => {
      const prev = bals[o.player] || 0;
      const next = Math.max(0, prev + o.delta);
      note(o.player, next - prev);
      bals[o.player] = next;
      state.mirrored[o.player] = (state.mirrored[o.player] || 0) + (next - prev);
      log(`delta ${o.player} ${o.delta >= 0 ? '+' : ''}${o.delta.toFixed(2)} (jeu) -> solde ${next.toFixed(2)}${prev + o.delta < 0 ? ' (perte hors banque absorbee)' : ''}`);
    }, { after: ctx.commitLine });
    recordCasino(o.player, o.delta);

  } else if (o.type === 'cashout') {
    // le joueur a deja ete debite en jeu par le plugin. Le VAULT fait foi
    // (decision Ryan 2026-08-29) : chaque dollar paye detruit un dollar en
    // jeu. Seuls les quotas journaliers bornent le paiement ; l'excedent
    // revient en jeu tout seul (mirrored abaisse -> reconcile pousse un
    // credit inbox de la difference).
    const asked = o.amount;
    // Compte GELE par le coupe-circuit : si une vraie boucle a fabrique de
    // l'argent, il est dans son VAULT — le laisser cashout, c'est encaisser
    // l'incident. Rien n'est paye : tout revient sur son solde casino, qui
    // sera pousse en jeu au degel (refund attendu). Message clair au joueur.
    if (guard.isFrozenPlayer(state, o.player)) {
      state.mirrored[o.player] = (state.mirrored[o.player] || 0) - asked;
      state.refundExpect = state.refundExpect || {};
      state.refundExpect[o.player] = Math.round(((state.refundExpect[o.player] || 0) + asked) * 100) / 100;
      state.pendingTxResults = (state.pendingTxResults || []).concat({ player: o.player, amount: asked, status: 'FAILED' });
      ctx.commitLine();
      log(`cashout ${o.player} : demande ${asked}, RETENU (compte gele, revue en cours)`);
      const P = '§x§A§1§8§C§D§1§lOutmind Casino §f§l';
      ptero.sendCommand(`tell ${o.player} ${P}Your account is under a staff review, cashouts are paused. Your §a§l${fmtExact(asked)}§f§l is safe and will return to your balance once the review is done.`).catch(() => {});
      ptero.sendCommand(`cashoutfailed ${o.player} review`).catch(() => {}); // dialogue + son en jeu (Skript)
      return;
    }
    // reservation ATOMIQUE du quota : lecture du reliquat, decision et
    // increment dans le meme verrou — les deux portes de sortie ne peuvent
    // plus depenser le meme reliquat en parallele
    const pay = quotas.reserve(o.player, asked, treasuryNow());
    const refund = asked - pay;
    const cappedByDay = pay < asked;
    // le refund reviendra en jeu par la reconciliation : on note qu'une
    // poussee de ce montant est ATTENDUE, pour que le coupe-circuit ne la
    // prenne pas pour une boucle (les refunds retentes ont exactement la
    // signature de l'incident 2x70M sans en etre)
    const attendreRefund = () => {
      if (refund <= 0) return;
      state.refundExpect = state.refundExpect || {};
      state.refundExpect[o.player] = Math.round(((state.refundExpect[o.player] || 0) + refund) * 100) / 100;
    };

    if (pay > 0) {
      // INVARIANT VITAL : bals et mirrored descendent du MEME montant, SANS
      // plancher a 0 — un bals negatif est une dette de stats, pas un ecart
      // (incident 2026-08-29 : 2x70M fantomes pousses a a player parce que
      // le clamp faisait diverger les deux).
      ledger.mutate('cashout', (bals, note) => {
        note(o.player, -pay);
        bals[o.player] = (bals[o.player] || 0) - pay;
        state.mirrored[o.player] = (state.mirrored[o.player] || 0) - pay;
        if (refund > 0) {
          state.mirrored[o.player] -= refund;
          attendreRefund();
        }
      }, {
        after: () => {
          // l'ordre de /pay part DANS le meme commit que l'offset : plus de
          // fenetre "ligne consommee mais dette invisible" (revue 2026-08-30).
          // Et l'append reste APRES le saveState : un crash pile entre les
          // deux donne un /pay manquant (rattrapable), jamais un double.
          ctx.commitLine();
          appendLine(PAYOUTS_FILE, { at: Date.now(), player: o.player, amount: pay });
        },
      });
    } else {
      if (refund > 0) {
        state.mirrored[o.player] = (state.mirrored[o.player] || 0) - refund;
        attendreRefund();
        // rien de payable : echec net pour le joueur. Dans le state AVANT le
        // commit (persiste avec l'offset) : un crash ne perd plus le statut
        // FAILED du menu /cashout.
        state.pendingTxResults = (state.pendingTxResults || []).concat({ player: o.player, amount: asked, status: 'FAILED' });
      }
      ctx.commitLine();
    }
    log(`cashout ${o.player} : demande ${asked}, paye ${pay}${refund > 0 ? ', rembourse en jeu ' + refund + ' (plafond journalier atteint)' : ''}`);

    if (cappedByDay) {
      const t = treasuryNow();
      const d = quotas.readDailyCap();
      const investor = quotas.isInvestor(o.player);
      const perso = quotas.playerDailyMax(o.player);
      const persoUsed = d.players[String(o.player).toLowerCase()] || 0;
      const persoLimite = persoUsed >= perso;
      const par = persoLimite
        ? `quota personnel ${investor ? 'Investor' : 'Gambler'}`
        : ((t != null && t * quotas.limits.DAILY_VAULT_PCT < quotas.limits.DAILY_MAX)
          ? `${quotas.limits.DAILY_VAULT_PCT * 100}% de la caisse` : 'plafond fixe');
      log(`⚠ plafond journalier atteint (${par}) : ${o.player} a pris ${Math.round(persoUsed)} sur ${perso}, maison ${Math.round(d.paid || 0)} sur ${Math.round(quotas.dailyCap(t))}`);
      // Le plugin ne dit au joueur que « failed », sans la raison : on lui
      // explique depuis la console, un message par cas (demande Ryan).
      const P = '§x§A§1§8§C§D§1§lOutmind Casino §f§l';
      const D = fmtExact;
      let msg;
      if (pay > 0) {
        msg = `${P}Daily limit hit halfway: §a§l${D(pay)}§f§l was sent to your DonutSMP purse, the remaining §a§l${D(refund)}§f§l is back on your casino balance. Withdrawals reopen at midnight.`;
      } else if (persoLimite) {
        const grade = investor ? 'Investor' : 'Gambler';
        const tip = investor ? '' : ` §7§oTip: invest ${D(quotas.limits.INVESTOR_MIN)}+ to unlock the Investor limit (${D(quotas.limits.PLAYER_MAX_INVESTOR)}/day).`;
        msg = `${P}You reached your personal ${grade} limit of §a§l${D(perso)}§f§l per day. Your §a§l${D(refund)}§f§l is back on your balance, come back after midnight.${tip}`;
      } else {
        msg = `${P}The casino vault reached its daily withdrawal limit. Your §a§l${D(refund)}§f§l is back on your balance, withdrawals reopen at midnight.`;
      }
      ptero.sendCommand(`tell ${o.player} ${msg}`).catch(() => {});
      // dialogue + son en jeu (Skript cashoutfailed) avec le code du plafond
      const code = pay > 0 ? 'limit_partial' : (persoLimite ? 'limit_personal' : 'limit_vault');
      ptero.sendCommand(`cashoutfailed ${o.player} ${code}`).catch(() => {});
    }

  } else if (o.type === 'cashout_refused') {
    // refus cote plugin (vault insuffisant, bot offline...) : relaye au bot
    // Discord, et la raison est dite au joueur en jeu (sinon il retente en
    // boucle en croyant le casino casse)
    appendLine(REFUSALS_FILE, { at: Date.now(), player: o.player, amount: o.amount, allowed: o.allowed, reason: o.reason });
    log(`cashout refuse par le plugin : ${o.player} demande ${o.amount}, autorise ${o.allowed} (${o.reason})`);
    const P = '§x§A§1§8§C§D§1§lOutmind Casino §f§l';
    const okPart = o.allowed > 0
      ? ` You can withdraw up to §a§l${fmtExact(o.allowed)}§f§l right now.`
      : '';
    ptero.sendCommand(`tell ${o.player} ${P}Your cashout of §a§l${fmtExact(o.amount)}§f§l could not be processed (§c${o.reason}§f§l).${okPart}`).catch(() => {});
    ctx.advance();

  } else if (o.type === 'apply_failed') {
    // le serveur n'a pas pu appliquer (joueur jamais venu ?) : on annule le
    // miroir et on retentera dans 10 min ou quand il se connecte. La poussee
    // annulee est aussi PURGEE du journal du coupe-circuit : le retry re-pousse
    // le meme montant, et sans cette purge R2 le prenait pour une boucle et
    // gelait chaque premier depot >= 5M d'un joueur pas encore venu (revue
    // 2026-08-30).
    state.mirrored[o.player] = (state.mirrored[o.player] || 0) - o.amount;
    state.retryAfter[o.player] = Date.now() + 10 * 60 * 1000;
    if (state.pushLog) delete state.pushLog[o.player];
    // si cet echec concerne une entree du lot ENCORE EN VOL (ecriture ambigue
    // lue par le plugin avant notre commit), on le note sur le lot : son
    // commit gardera le retryAfter et n'inscrira pas la poussee au journal du
    // coupe-circuit — sinon le retry repartait aussitot et R2 gelait a tort
    if (state.pendingBatch && state.pendingBatch.entries.some((e) => e.seq === o.seq)) {
      state.pendingBatch.appliedFailed = state.pendingBatch.appliedFailed || {};
      state.pendingBatch.appliedFailed[o.seq] = true;
    }
    log(`echec seq ${o.seq} ${o.player} ${o.amount}, retentera dans 10 min ou quand il se connecte`);
    ctx.commitLine();

  } else {
    ctx.advance();
  }
}

// ---------- lecture de l'outbox ----------
// Retourne { lines, byteLens } a traiter, ou null si rien / anomalie.
async function fetchOutboxNews() {
  // chemin incremental : HTTP Range depuis l'octet suivant la derniere ligne
  // complete consommee
  if (typeof state.outboxBytes === 'number' && state.outboxBytes >= 1) {
    const tail = await ptero.readTail(OUTBOX, state.outboxBytes);
    if (tail.status === 'shrunk' || tail.status === 'missing') {
      guard.freezeGlobal(`outbox.jsonl a retreci ou disparu cote serveur (${tail.status}) : consommation stoppee pour ne rien rejouer. Verifier le fichier, puis reinitialiser outboxBytes/outboxOffset dans bridge-state.json.`);
      return null;
    }
    const { lines, consumed } = splitCompleteLines(tail.buf);
    if (!lines.length) return null;
    const byteLens = [];
    let pos = 0;
    for (const l of lines) {
      const b = Buffer.byteLength(l, 'utf8') + 1;
      byteLens.push(b);
      pos += b;
    }
    if (pos !== consumed) log(`outbox : comptage d'octets ${pos} != ${consumed} (verifier)`);
    return { lines, byteLens };
  }

  // premier passage (migration) : lecture complete, comme avant la refonte,
  // puis bascule sur les offsets en octets
  const out = await ptero.readFile(OUTBOX);
  if (out == null) return null;
  const buf = Buffer.from(out, 'utf8');
  const lastNl = buf.lastIndexOf(0x0a);
  const allLines = out.split('\n');
  const complete = allLines.length - 1;
  const lines = [];
  const byteLens = [];
  for (let i = state.outboxOffset; i < complete; i++) {
    lines.push(allLines[i]);
    byteLens.push(Buffer.byteLength(allLines[i], 'utf8') + 1);
  }
  if (lastNl >= 0) {
    // octets deja consommes = tout jusqu'a la fin de la ligne complete
    // state.outboxOffset - 1 ; on le calcule en retranchant ce qui reste
    let restant = 0;
    for (const b of byteLens) restant += b;
    state.outboxBytes = (lastNl + 1) - restant;
    log(`outbox : bascule en lecture incrementale (offset initial ${state.outboxBytes} octets, ${complete} lignes)`);
  }
  return { lines, byteLens };
}

// ---------- le tick ----------
async function tick() {
  // COUPE-CIRCUIT : banque gelee = seules les phases de decor tournent, les
  // offsets n'avancent pas, rien n'est perdu. On guette juste un eventuel
  // ordre unfreeze_all dans le canal admin.
  const frozen = guard.frozenGlobal();
  if (frozen) {
    alert('frozen-tick', `banque GELEE (${guard.FREEZE_FILE} present) : phases d'argent suspendues, decor maintenu.`, 60 * 60 * 1000);
    try {
      const raw = fs.readFileSync(path.join(DIR, 'admin-orders.jsonl'), 'utf8').split('\n');
      for (let i = state.adminOffset || 0; i < raw.length - 1; i++) {
        try {
          if (JSON.parse(raw[i].trim() || '{}').kind === 'unfreeze_all') {
            guard.unfreezeGlobal();
            log('degel general demande via le canal admin');
            break;
          }
        } catch {}
      }
    } catch {}
  }

  let statusSeen = null;
  const txResults = [];

  if (!frozen) {
    // 0) ordres admin en premier : un credit pose maintenant part en jeu dans
    // la reconciliation de ce meme tick
    adminOrders.consume(state, saveState);

    // 1) outbox du plugin : deltas casino, cashouts, statuts, echecs
    const news = await fetchOutboxNews();
    if (news) {
      const ctx = {
        statusSeen: null,
        txResults,
        advance: null,     // avance en memoire (lignes sans argent)
        commitLine: null,  // avance + persiste (lignes d'argent)
      };
      let dirty = false;
      for (let i = 0; i < news.lines.length; i++) {
        const line = news.lines[i].trim();
        const nb = news.byteLens[i];
        ctx.advance = () => {
          state.outboxOffset += 1;
          state.outboxBytes += nb;
          dirty = true;
        };
        ctx.commitLine = () => {
          state.outboxOffset += 1;
          state.outboxBytes += nb;
          saveState();
          dirty = false;
        };
        if (!line) { ctx.advance(); continue; }
        let o;
        try { o = JSON.parse(line); } catch { ctx.advance(); continue; }
        if (!o || typeof o !== 'object' || Array.isArray(o)) { log(`outbox : ligne ignoree (pas un objet) : ${line.slice(0, 80)}`); ctx.advance(); continue; }
        processOutboxLine(o, ctx);
        if (ctx.statusSeen) statusSeen = ctx.statusSeen;
      }
      if (dirty) saveState();
    }

    // garde-fou : ne JAMAIS emettre un seq <= celui deja traite par le
    // plugin, sinon les lignes sont silencieusement ignorees cote serveur
    if (state.nextSeq <= state.pluginLastSeq) state.nextSeq = state.pluginLastSeq + 1;

    // 1bis) /pay confirmes par le bot -> statut PAID pour le menu /cashout.
    // Suspendu tant qu'un lot est en attente (ils rejoindraient un lot deja
    // fige) : au pire un tick de retard. Les retraits MP (source 'mp') ne
    // sont PAS relayes : le plugin recevrait un PAID pour un cashout qu'il
    // n'a jamais emis.
    if (!state.pendingBatch) {
      if (typeof state.payoutResultsOffset !== 'number') state.payoutResultsOffset = 0;
      try {
        const rlines = fs.readFileSync(PAYOUT_RESULTS_FILE, 'utf8').split('\n');
        const rcomplete = rlines.length - 1;
        if (rcomplete < state.payoutResultsOffset) {
          log('payout-results.jsonl a retreci : offset repositionne en fin, aucun rejeu');
          state.payoutResultsOffset = rcomplete;
        }
        for (let i = state.payoutResultsOffset; i < rcomplete; i++) {
          const l = rlines[i].trim();
          if (!l) continue;
          try {
            const r = JSON.parse(l);
            if (r.source === 'mp') continue;
            txResults.push({ player: r.player, amount: r.amount, status: r.status || 'PAID' });
          } catch {}
        }
        state.payoutResultsOffset = rcomplete;
      } catch {}
    }
    // les txresults (outbox de ce tick + /pay confirmes) attendent dans le
    // state jusqu'a leur embarquement dans un lot : rien ne se perd si un lot
    // est deja en vol ou si le tick echoue ici
    if (txResults.length) {
      state.pendingTxResults = (state.pendingTxResults || []).concat(txResults);
    }

    // 2) reconciliation du grand livre -> inbox, par LOT WRITE-AHEAD.
    //
    // Le lot (seqs + montants + effets miroir) est persiste dans l'etat AVANT
    // l'ecriture de l'inbox. Si l'ecriture echoue — y compris le cas ambigu ou
    // wings a ecrit le fichier mais repond 502 — le MEME lot repart au tick
    // suivant avec les MEMES seqs : le plugin deduplique par seq <= lastSeq,
    // donc exactly-once quoi qu'il arrive. C'est le correctif du finding
    // critique de la revue 2026-08-30 (re-poussee sous un seq neuf = argent
    // double, la classe exacte de l'incident 2x70M).
    //
    // Les effets sont des DELTAS (mirrored += montant pousse), pas des valeurs
    // absolues : un delta casino qui bouge mirrored pendant que le lot est en
    // vol n'est jamais ecrase par le commit du lot.
    guard.prunePushLog(state);
    if (!state.pendingBatch) {
      const balsNow = ledger.load();
      const entries = [];
      const onlineNow = statusSeen ? statusSeen.players : null;
      const construire = (player, amount) => {
        // poussee NEGATIVE = retrait deja opere en banque. La retenir
        // laisserait le vault du joueur plus riche que sa banque, divergence
        // encaissable en cashout (exploit demontre en contre-verification) :
        // elle passe TOUJOURS, gel ou pas, juste signalee si elle est grosse.
        if (amount < 0) {
          guard.notifyBigPush(player, amount);
          entries.push({ seq: state.nextSeq++, player, amount });
          return;
        }
        // gel = credits en jeu suspendus, y compris les refunds (ils
        // couleront au degel) : l'ecart attend un humain
        if (guard.isFrozenPlayer(state, player)) return;
        // part de refund ATTENDUE (cashout plafonne) : elle est DUE, elle
        // court-circuite le coupe-circuit et n'entre pas dans son journal.
        // Elle est consommee MEME quand la poussee est fusionnee avec un
        // autre ecart (depot, credit admin) — sinon le reliquat fuyait et
        // offrait un bypass permanent (contre-verification 2026-08-30).
        const expect = (state.refundExpect || {})[player] || 0;
        if (expect > 0) {
          const use = Math.round(Math.min(amount, expect) * 100) / 100;
          if (amount <= expect + 0.01) {
            entries.push({ seq: state.nextSeq++, player, amount, refundUse: use });
            return;
          }
          // fusionnee : seul le SURPLUS au-dela du refund est juge
          if (!guard.checkPush(state, player, Math.round((amount - use) * 100) / 100).ok) return;
          entries.push({ seq: state.nextSeq++, player, amount, refundUse: use, record: true });
          return;
        }
        if (!guard.checkPush(state, player, amount).ok) return;
        entries.push({ seq: state.nextSeq++, player, amount, record: true });
      };
      for (const [player, amt] of Object.entries(balsNow)) {
        const blocked = state.retryAfter[player] && Date.now() < state.retryAfter[player]
          && !(onlineNow && onlineNow.includes(player));
        if (blocked) continue;
        const diff = amt - (state.mirrored[player] || 0);
        if (Math.abs(diff) < 0.01) continue;
        construire(player, Math.round(diff * 100) / 100);
      }
      for (const [player, m] of Object.entries(state.mirrored)) {
        if (!(player in balsNow) && Math.abs(m) >= 0.01) {
          construire(player, -Math.round(m * 100) / 100);
        }
      }
      // resultats de cashout : informatifs (kind=txresult), le plugin ne
      // touche pas a l'economie en les lisant
      for (const r of (state.pendingTxResults || [])) {
        entries.push({ seq: state.nextSeq++, player: r.player, amount: r.amount, kind: 'txresult', status: r.status });
      }
      delete state.pendingTxResults;
      if (entries.length) {
        state.pendingBatch = { at: Date.now(), entries };
        saveState(); // WRITE-AHEAD : lot et nextSeq persistes avant l'ecriture
      }
    }

    if (state.pendingBatch) {
      const batch = state.pendingBatch;
      let newCache = inboxCache == null ? ((await ptero.readFile(INBOX)) || '') : inboxCache;
      // purge des lignes deja traitees par le plugin pour que l'inbox ne grossisse pas
      if (newCache.split('\n').length > 20 && state.pluginLastSeq > 0) {
        newCache = newCache.split('\n').filter((l) => {
          try { return JSON.parse(l).seq > state.pluginLastSeq; } catch { return false; }
        }).join('\n');
        if (newCache && !newCache.endsWith('\n')) newCache += '\n';
      }
      // retire les fantomes d'une eventuelle ecriture ambigue precedente : les
      // lignes du lot re-emises sont identiques, mais autant ne pas les doubler
      const seqsDuLot = new Set(batch.entries.map((e) => e.seq));
      newCache = newCache.split('\n').filter((l) => {
        if (!l.trim()) return false;
        try { return !seqsDuLot.has(JSON.parse(l).seq); } catch { return false; }
      }).join('\n');
      if (newCache) newCache += '\n';
      newCache += batch.entries.map((e) => JSON.stringify({
        seq: e.seq, player: e.player, amount: e.amount,
        ...(e.kind ? { kind: e.kind, status: e.status } : {}), at: Date.now(),
      })).join('\n') + '\n';
      await ptero.writeFile(INBOX, newCache);
      inboxCache = newCache;
      // COMMIT du lot : effets miroir en deltas, consommation des refunds
      // attendus, journal du coupe-circuit. Une entree dont l'apply_failed a
      // DEJA ete consomme (ecriture ambigue + plugin plus rapide que le tick)
      // garde son retryAfter et n'entre pas au journal : sans ca, le retry
      // repartait immediatement et R2 gelait le joueur a tort.
      for (const e of batch.entries) {
        if (e.kind === 'txresult') {
          log(`txresult ${e.player} ${e.amount} ${e.status}`);
          continue;
        }
        const dejaEchoue = (batch.appliedFailed || {})[e.seq];
        state.mirrored[e.player] = (state.mirrored[e.player] || 0) + e.amount;
        if (!dejaEchoue) delete state.retryAfter[e.player];
        if (e.refundUse) {
          const rest = Math.round((((state.refundExpect || {})[e.player] || 0) - e.refundUse) * 100) / 100;
          if (rest > 0.009) state.refundExpect[e.player] = rest;
          else if (state.refundExpect) delete state.refundExpect[e.player];
          if (e.record && !dejaEchoue) guard.recordPush(state, e.player, e.amount);
        } else if (e.record && !dejaEchoue) {
          guard.recordPush(state, e.player, e.amount);
        }
        log(`inbox seq ${e.seq} : ${e.player} ${e.amount >= 0 ? '+' : ''}${e.amount}${dejaEchoue ? ' (deja en echec cote plugin, retry programme)' : ''}`);
      }
      delete state.pendingBatch;
      saveState();
    }
  }

  // 3-7) decor : liste en ligne, whitelist, statut bot, leaderboard, podium,
  // caisse. Jamais bloquant pour l'argent, tourne meme gele.
  const balsDecor = ledger.load();
  const blSet = decor.blacklistSet();
  decor.publishOnline(statusSeen);
  await decor.syncWhitelist(state, balsDecor, blSet);
  // pendant un gel, on publie offline : le plugin ferme le menu /cashout au
  // lieu d'empiler des ordres que personne ne consommera
  const botOnline = await decor.syncBotStatus(state, frozen);
  await decor.syncLeaderboard(state, balsDecor, blSet);
  await decor.syncFortune(state);

  // 8) filets : backups horaires, bilan quotidien, watchdogs
  backups.maybeSnapshot(state);
  const bankState = readJsonLoose(path.join(DIR, 'bank-state.json'), {});
  report.maybeDailyReport(state, { bals: balsDecor, bankState });

  // lot d'inbox en vol depuis trop longtemps = les ecritures echouent en
  // boucle : depots, refunds et statuts PAID sont retenus — un humain doit voir
  if (state.pendingBatch && Date.now() - state.pendingBatch.at > 5 * 60 * 1000) {
    alert('batch-bloque',
      `le lot de reconciliation (${state.pendingBatch.entries.length} poussee(s)) n'arrive pas a s'ecrire depuis ${Math.round((Date.now() - state.pendingBatch.at) / 60000)} min : panel en panne d'ecriture ? Rien n'est perdu, il repart a chaque tick.`,
      60 * 60 * 1000);
  }

  if (!botOnline && !frozen) {
    const st = readJsonLoose(BOT_STATUS_FILE, null);
    const age = st ? Math.round((Date.now() - st.at) / 60000) : null;
    if (age == null || age > 10) {
      alert('bot-muet', `le bot DonutSMP est muet depuis ${age == null ? '?' : age} min (bot-status.json${age == null ? ' absent' : ''}). Depots et /pay en pause tant qu'il ne revient pas.`, 60 * 60 * 1000);
    }
  }
  for (const p of (bankState.pendingPayouts || [])) {
    if ((p.tries || 0) > 3 || p.suspect) {
      const motif = p.suspect
        ? `/pay parti sans confirmation avant une coupure — il a PEUT-ETRE ete paye, verifier l'historique Donut avant de solder`
        : `${p.tries} essais sans confirmation`;
      alert(`payout-mort-${p.player}-${p.amount}`,
        `cashout en attente d'un humain : ${p.player} ${fmtExact(p.amount)} (${motif}, depuis le ${new Date(p.at).toISOString().slice(0, 10)}). `
        + `Verifier transactions.jsonl et la caisse, puis solder a la main (admin pay ou credit).`,
        12 * 60 * 60 * 1000);
    }
  }

  saveState();
}

// ---------- boucle et signaux ----------
// Les signaux d'arret sont DIFFERES pendant un tick : pm2 stop en plein
// commit d'une ligne d'argent laisserait un demi-etat. On finit le tick, on
// sort proprement juste apres.
let enTick = false;
let stopSignal = null;
process.on('uncaughtException', (e) => { log(`ARRET sur exception non geree : ${(e && (e.stack || e.message)) || e}`); process.exit(1); });
process.on('unhandledRejection', (e) => { log(`ARRET sur rejet non gere : ${(e && (e.stack || e.message)) || e}`); process.exit(1); });
for (const sig of ['SIGINT', 'SIGTERM', 'SIGHUP', 'SIGBREAK']) {
  process.on(sig, () => {
    if (enTick) {
      if (!stopSignal) log(`signal ${sig} recu en plein tick : arret a la fin du tick en cours`);
      stopSignal = sig;
      return;
    }
    log(`ARRET demande par signal ${sig}`);
    process.exit(0);
  });
}
process.on('exit', (code) => { log(`fin du process bridge, code ${code}`); });

// --- Stats en jeu : net depose par joueur (mirrored) pousse vers le plugin
// OutmindStats (%outmind_profit% = vault + investi - mirrored - bonus).
// Lecture seule de state.mirrored, aucun effet sur l argent. Republie
// uniquement quand le contenu change (1 ecriture panel, pas une par tick).
const MIRRORED_FILE = '/plugins/OutmindStats/mirrored.json';
let dernierMirroredPublie = null;
async function publierMirrored() {
  const obj = {};
  for (const k of Object.keys(state.mirrored).sort()) obj[k] = Math.round(state.mirrored[k]);
  const json = JSON.stringify(obj);
  if (json === dernierMirroredPublie) return;
  await ptero.writeFile(MIRRORED_FILE, json);
  dernierMirroredPublie = json;
}

// Comptes Discord lies (links.json du discord-bot, lecture seule) -> liste de
// pseudos pour %outmind_discord% (Linked / Not linked sur le scoreboard).
const LINKS_SRC = require('path').join(__dirname, '..', 'discord-bot', 'links.json');
const LINKS_FILE = '/plugins/OutmindStats/links.json';
let dernierLiensPublie = null;
async function publierLiens() {
  let links;
  try { links = JSON.parse(fs.readFileSync(LINKS_SRC, 'utf8')).links || {}; }
  catch (e) { return; } // fichier absent ou en cours d'ecriture : on reessaie au tick suivant
  const noms = [...new Set(Object.values(links).map((l) => l && l.player).filter(Boolean))].sort();
  const json = JSON.stringify(noms);
  if (json === dernierLiensPublie) return;
  await ptero.writeFile(LINKS_FILE, json);
  dernierLiensPublie = json;
}

async function boucle() {
  for (;;) {
    enTick = true;
    try { await tick(); await publierMirrored(); await publierLiens(); }
    catch (e) {
      if (e instanceof CorruptStateError) {
        alert('tick-corrupt', `ARRET : ${e.message}. Restaurer depuis backups/ puis relancer.`, 0);
        log(`ARRET sur etat corrompu : ${e.message}`);
        process.exit(1);
      }
      log('erreur : ' + e.message);
      if (ptero.failures() >= 6) {
        alert('panel-muet', `l'API du panel echoue en boucle (${ptero.failures()} echecs consecutifs) : reconciliation en pause, rien n'est perdu.`, 60 * 60 * 1000);
      }
    }
    enTick = false;
    if (stopSignal) { log(`ARRET demande par signal ${stopSignal} (differe)`); process.exit(0); }
    await new Promise((r) => setTimeout(r, 10000));
  }
}

if (require.main === module) {
  log(`demarrage : panel ${ptero.PANEL}, serveur ${ptero.SRV}`);
  log(`grand livre : ${Object.keys(bals0).length} comptes, ${fmtExact(Object.values(bals0).reduce((a, v) => a + v, 0))} au total`);
  const geles = Object.keys(state.frozenPlayers || {});
  if (geles.length) log(`joueurs geles au demarrage : ${geles.join(', ')}`);
  if (guard.frozenGlobal()) log('BANQUE GELEE au demarrage (bank-freeze.json present)');
  backups.maybeSnapshot(state); // assurance avant la premiere mutation
  saveState();
  boucle();
}

// exporte pour le banc d'essai (test/) : jamais utilise en production
module.exports = { tick, state: () => state, _test: { processOutboxLine, fetchOutboxNews } };
