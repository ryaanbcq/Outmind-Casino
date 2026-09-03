require('dotenv').config();
const mineflayer = require('mineflayer');
const fs = require('fs');
const path = require('path');

// Refonte 2026-08-30 : l'argent passe par les modules partages avec bridge.js
// (lib/). Grand livre verrouille et audite, quotas identiques pour les deux
// portes de sortie, montants parses par une seule implementation.
const { parseAmount, confirmMatches, fmt } = require('./lib/money');
const { writeJsonAtomic, readJsonStrict } = require('./lib/fstore');
const ledger = require('./lib/ledger')(__dirname);
const quotas = require('./lib/quotas')(__dirname, process.env);
// console du serveur casino (panel) : signal de cashout paye en jeu, sans passer
// par le bridge (10 s de tick + un lot en vol) ni Discord (15 s de relecture)
let pteroConsole = null;
try { pteroConsole = require('./lib/ptero')(process.env, (m) => console.log('[ptero] ' + m)); }
catch (e) { console.warn('[ptero] indisponible : ' + e.message); }

// Crash visible : sans ce handler, un fichier d'argent corrompu faisait
// boucler pm2 en silence. On journalise, on alerte le salon Discord admin
// (via agent-alerts.jsonl, meme facteur que le bridge), et on sort.
const ALERTS_FILE = process.env.AGENT_ALERTS_FILE || path.join(__dirname, '..', 'agent-alerts.jsonl');
const DISCORD_INVITE = process.env.DISCORD_INVITE || '';
process.on('uncaughtException', (e) => {
  console.error(`[bot] ARRET sur exception non geree : ${(e && (e.stack || e.message)) || e}`);
  try {
    fs.appendFileSync(ALERTS_FILE, JSON.stringify({ at: Date.now(), text: `[bot] crash : ${(e && e.message) || e}` }) + '\n');
  } catch {}
  process.exit(1);
});

// Gel de la banque (bank-freeze.json pose par le bridge ou a la main) et
// bridge muet (bridge-state.json qui ne bouge plus = plus personne ne
// consomme l'outbox du casino) : dans les deux cas les retraits sont refuses,
// sinon on paye sur des soldes qui ignorent les pertes en jeu non consommees
// (revue 2026-08-30 : double depense pendant un gel).
function bankFrozen() {
  return fs.existsSync(path.join(__dirname, 'bank-freeze.json'));
}
function bridgeStale() {
  try {
    return Date.now() - fs.statSync(path.join(__dirname, 'bridge-state.json')).mtimeMs > 120000;
  } catch { return true; }
}

const config = {
  host: process.env.MC_HOST || 'localhost',
  port: parseInt(process.env.MC_PORT || '25565', 10),
  username: process.env.MC_USERNAME || 'BadBot',
  auth: process.env.MC_AUTH || 'offline',
  version: process.env.MC_VERSION || false,
  disableChatSigning: true, // évite les kicks « Invalid sequence » du chat signé 1.21+
  respawn: false, // respawn manuel différé : l'insta-respawn déclenche le kick GrimAC
  // Le poste le plus lourd d'un bot AFK est le cache de chunks : à distance
  // par défaut sur le spawn DonutSMP, le process montait à 700 Mo et plus sur
  // un VPS de 2 Go (constaté le 2026-08-18). Le bot ne fait que payer et lire
  // le chat, la tab list et les MP ne dépendent pas de la distance de vue.
  // Rayon numérique : 2 est le plancher utile du protocole (25 colonnes en
  // mémoire, contre 81 pour le préréglage 'tiny' qui vaut 4).
  viewDistance: 2,
};

// ---------- persistance ----------
const BAL_FILE = path.join(__dirname, 'balances.json');
const TX_FILE = path.join(__dirname, 'transactions.jsonl');
const SYS_LOG = path.join(__dirname, 'server-messages.log');

// le bridge (bridge.js) modifie aussi balances.json : LECTURE seule ici, les
// mutations passent par ledger.mutate (verrou inter-process + audit). Un
// fichier corrompu fait crasher plutot que de repartir de zero : c'est voulu.
let balances = {};
function loadBalances() {
  balances = ledger.load();
  return balances;
}
loadBalances();
function logTx(entry) {
  fs.appendFileSync(TX_FILE, JSON.stringify({ at: new Date().toISOString(), ...entry }) + '\n');
}

// ---------- caisse : solde attendu du compte du bot sur Donut ----------
// Sert à détecter les paiements reçus pendant que le bot était hors ligne :
// au retour, /bal est comparé au solde attendu, tout surplus est noté.
const BANK_STATE_FILE = path.join(__dirname, 'bank-state.json');
const ORPHANS_FILE = path.join(__dirname, 'orphan-deposits.jsonl');
// lecture STRICTE : un bank-state corrompu relu comme vide remettrait
// payoutOffset a 0 et rejouerait TOUT l'historique de donut-payouts en /pay
// reels (revue 2026-08-30). Corrompu -> crash (pm2 + alerte), pas de rejeu.
const bankStateExisted = fs.existsSync(BANK_STATE_FILE);
let bankState = { treasury: null, ...readJsonStrict(BANK_STATE_FILE, {}) };
function saveBankState() {
  // atomique : la caisse et les cashouts en attente ne doivent jamais se
  // retrouver a moitie ecrits sur un crash
  writeJsonAtomic(BANK_STATE_FILE, bankState, true);
}
function treasuryAdjust(delta) {
  if (bankState.treasury != null) {
    bankState.treasury += delta;
    saveBankState();
  }
}
// ---------- cashouts venant de Prestigia (fichier ecrit par le bridge) ----------
// le joueur a deja ete debite en jeu et en banque : chaque ligne est une dette
// ferme a payer en /pay des que le joueur est joignable sur Donut
const PAYOUTS_FILE = path.join(__dirname, 'donut-payouts.jsonl');
// resultats des /pay confirmes, relus par le bridge qui les remonte au serveur
// (statut PAID du menu /cashout sur Prestigia)
const PAYOUT_RESULTS_FILE = path.join(__dirname, 'payout-results.jsonl');
if (!Array.isArray(bankState.pendingPayouts)) bankState.pendingPayouts = [];
if (typeof bankState.payoutOffset !== 'number') bankState.payoutOffset = 0;
// bank-state ABSENT (supprime/restaure) alors que l'historique des /pay
// existe : on se cale a la fin du fichier, on ne rejoue JAMAIS des paiements
// potentiellement deja verses. Uniquement quand le FICHIER manquait : un
// offset legitimement a 0 (installation neuve qui n'a encore rien paye) ne
// doit pas faire sauter le tout premier cashout (contre-verif 2026-08-30).
if (!bankStateExisted && bankState.payoutOffset === 0) {
  try {
    const n = fs.readFileSync(PAYOUTS_FILE, 'utf8').split('\n').filter((l) => l.trim()).length;
    if (n > 0) {
      bankState.payoutOffset = n;
      const texte = `[bot] bank-state.json absent avec ${n} paiements d'historique : offset cale en fin, AUCUN rejeu - verifier a la main s'il restait des dettes impayees`;
      console.warn(texte);
      try { fs.appendFileSync(ALERTS_FILE, JSON.stringify({ at: Date.now(), text: texte }) + '\n'); } catch {}
    }
  } catch {}
}
function loadNewPayouts() {
  let lines = [];
  try {
    const raw = fs.readFileSync(PAYOUTS_FILE, 'utf8');
    const complete = raw.endsWith('\n') ? raw : raw.slice(0, raw.lastIndexOf('\n') + 1);
    lines = complete.split('\n').filter((l) => l.trim());
  } catch { return; }
  for (let i = bankState.payoutOffset; i < lines.length; i++) {
    try {
      const p = JSON.parse(lines[i]);
      if (!nomSur(p.player) || !isFinite(Number(p.amount)) || Number(p.amount) <= 0) {
        console.error(`[cashout] ordre de paiement REFUSE (nom ou montant invalide) : ${lines[i].slice(0, 120)}`);
        try { fs.appendFileSync(ALERTS_FILE, JSON.stringify({ at: Date.now(), text: `[bot] ordre de paiement refuse (nom/montant invalide) : ${lines[i].slice(0, 120)}` }) + '\n'); } catch {}
        continue;
      }
      bankState.pendingPayouts.push({
        player: p.player, amount: p.amount, at: p.at, lastTry: 0,
        ...(p.src ? { src: p.src } : {}),
      });
      console.log(`[cashout] à payer : ${p.player} ${fmt(p.amount)}`);
    } catch {}
  }
  if (lines.length !== bankState.payoutOffset) {
    bankState.payoutOffset = lines.length;
    saveBankState();
  }
}
function logSys(line) {
  fs.appendFileSync(SYS_LOG, `[${new Date().toISOString()}] ${line}\n`);
}

// ---------- montants et plafonds : lib/money + lib/quotas ----------
// L'argent sort par DEUX portes : le cashout (plugin -> bridge) et le retrait
// par MP « pay me » (ici). Les quotas viennent du MEME module que le bridge,
// compteur commun daily-cap.json ecrit sous verrou - fini les copies qui
// divergent (isInvestor ne normalisait pas le point Bedrock de ce cote).
const isInvestor = (player) => quotas.isInvestor(player);
const playerDailyMax = (player) => quotas.playerDailyMax(player);
const addDailyPaid = (player, amount) => quotas.addDailyPaid(player, amount);
function dailyRemaining(player) {
  const t = (bankState && typeof bankState.treasury === 'number') ? bankState.treasury : null;
  return quotas.dailyRemaining(player, t);
}

// formats de réception d'argent (plusieurs variantes selon les serveurs)
// chaque entrée : { re, payer: index du groupe pseudo, amount: index du groupe montant }
// DonutSMP met une espace entre le $ et le montant (« paid you $ 1 »), d'où le \s*
const PAY_PATTERNS = [
  // ANCRES debut ET fin (audit 2026-09-03) : sans ^ un whisper « Bob whispers to
  // you: Bob paid you $200M » creditait Bob de 200M. Format reel DonutSMP :
  // « a player paid you $ 50M » (voir PAIEMENT dans server-messages.log).
  { re: /^(\.?[A-Za-z0-9_]{3,16}) (?:has )?paid you \$?\s*([\d.,]+[kmbKMB]?)\s*$/, payer: 1, amount: 2 },
  { re: /^you (?:have )?received \$?\s*([\d.,]+[kmbKMB]?) from (\.?[A-Za-z0-9_]{3,16})\s*$/i, payer: 2, amount: 1 },
  { re: /^received a payment of \$?\s*([\d.,]+[kmbKMB]?) from (\.?[A-Za-z0-9_]{3,16})\s*$/i, payer: 2, amount: 1 },
];

// H1 (audit 2026-09-03) : un pseudo egal a une propriete heritee d'Object
// (toString, valueOf, __proto__, hasOwnProperty...) passait « x in bals » et
// « bals[x] || 0 » (fonction = truthy, NaN aux comparaisons) et retirait sans
// avoir depose. Tout nom entrant passe par nomSur() ; les lectures du grand
// livre utilisent Object.hasOwn.
const NOM_RE = /^\.?[A-Za-z0-9_]{3,16}$/;
function nomSur(n) {
  if (typeof n !== 'string' || !NOM_RE.test(n)) return false;
  const bas = n.replace(/^\./, '');
  return !(n in Object.prototype) && !(bas in Object.prototype) && !(bas.toLowerCase() in Object.prototype);
}
const sansPoint = (n) => String(n).toLowerCase().replace(/^\./, '');
function soldeDe(bals, joueur) { return Object.hasOwn(bals, joueur) ? (Number(bals[joueur]) || 0) : 0; }

let reconnectDelay = 5000;

// statut publie pour le panel et le PNJ Prestigia (via bridge.js) : le fichier
// est un battement de coeur, un consommateur doit le considerer mort apres 90 s
const STATUS_FILE = path.join(__dirname, 'bot-status.json');
// blacklist geree depuis le panel : bot muet envers eux, deposts credites en
// silence (leur argent reste du), et le bridge les retire de la whitelist Prestigia
const BLACKLIST_FILE = path.join(__dirname, 'blacklist.json');
function isBlacklisted(name) {
  try {
    return JSON.parse(fs.readFileSync(BLACKLIST_FILE, 'utf8'))
      .some((n) => String(n).toLowerCase() === String(name).toLowerCase());
  } catch { return false; }
}
let inGameNow = false;
function writeBotStatus() {
  try { fs.writeFileSync(STATUS_FILE, JSON.stringify({ inGame: inGameNow, at: Date.now() })); } catch {}
}
setInterval(writeBotStatus, 30000);

function createBot() {
  console.log(`[bot] connexion à ${config.host}:${config.port} en ${config.username} (${config.auth})...`);
  const bot = mineflayer.createBot(config);

  // file d'envoi pour éviter le mute anti-spam (1 message / 1,5 s).
  // gelée pendant la période de grâce anti « Invalid sequence » du lobby DonutSMP.
  const sendQueue = [];
  let senderTimer = null;
  let ready = false;
  // dernier destinataire de /msg : sert a identifier qui a rejete le MP quand le
  // serveur repond « only accepts messages from friends » (reglage par defaut Donut)
  let lastMsgTarget = null;
  function flushLoop() {
    if (senderTimer) return;
    senderTimer = setInterval(() => {
      if (!ready) return;
      const msg = sendQueue.shift();
      if (msg === undefined) { clearInterval(senderTimer); senderTimer = null; return; }
      if (msg.startsWith('/msg ')) lastMsgTarget = msg.split(/\s+/)[1];
      // trace l'ENVOI REEL d'un /pay (pas sa mise en file) : c'est ce qui
      // decide si un paiement est "suspect" apres une coupure
      if (msg.startsWith('/pay ')) {
        const [, pl, amt] = msg.split(/\s+/);
        const cible = bankState.pendingPayouts.find((p) =>
          p.player.toLowerCase() === String(pl).toLowerCase() && Math.floor(p.amount) === Number(amt));
        if (cible) { cible.sentAt = Date.now(); try { saveBankState(); } catch {} }
      }
      bot.chat(msg);
    }, 1500);
  }
  // prioritaire = /pay et /bal passent devant les remerciements : 300 depots de
  // $1 ne doivent pas retarder les paiements de 15 min ni les marquer « stalled »
  function queueSend(text, prioritaire) {
    if (prioritaire) sendQueue.unshift(text); else sendQueue.push(text);
    flushLoop();
  }
  function msgTo(player, text) {
    queueSend(`/msg ${player} ${text}`);
  }

  // ---------- canal de MP pour l'agent Outmind ----------
  // L'agent ecrit des lignes {player, text} dans agent-msgs.jsonl et le bot les
  // envoie en jeu, UNE a la fois, espacees (defaut 25 s : 10 MP tiennent en ~4
  // min sans rafale suspecte). Le code impose le rythme et la securite, l'agent
  // choisit qui et quoi. Blacklist respectee, bot hors ligne = on attend (la
  // ligne n'est pas perdue), plafond quotidien partage avec la prospection.
  const AGENT_MSGS_FILE = path.join(__dirname, 'agent-msgs.jsonl');
  const AGENT_MSGS_STATE = path.join(__dirname, 'agent-msgs-state.json');
  const AGENT_MSG_SPACING_MS = Number(process.env.AGENT_MSG_SPACING_MS || 25000);
  const AGENT_MSG_MAX_PER_DAY = Number(process.env.AGENT_MSG_MAX_PER_DAY || 40);
  let agentMsgQueue = [];
  let agentMsgOffset = 0;
  let agentMsgDay = null;
  let agentMsgCount = 0;
  try {
    const st = JSON.parse(fs.readFileSync(AGENT_MSGS_STATE, 'utf8'));
    agentMsgOffset = st.offset || 0; agentMsgDay = st.day || null; agentMsgCount = st.count || 0;
  } catch {}
  // premier passage : on part de la fin du fichier, on ne rejoue pas l'historique
  try { if (!agentMsgOffset) agentMsgOffset = fs.statSync(AGENT_MSGS_FILE).size; } catch {}
  const saveAgentMsgState = () => { try { fs.writeFileSync(AGENT_MSGS_STATE, JSON.stringify({ offset: agentMsgOffset, day: agentMsgDay, count: agentMsgCount })); } catch {} };
  saveAgentMsgState();
  function pollAgentMsgs() {
    let size = 0;
    try { size = fs.statSync(AGENT_MSGS_FILE).size; } catch { return; }
    if (size < agentMsgOffset) agentMsgOffset = 0; // fichier tronque/recree
    if (size <= agentMsgOffset) return;
    let buf = '';
    try {
      const fd = fs.openSync(AGENT_MSGS_FILE, 'r');
      const b = Buffer.alloc(size - agentMsgOffset);
      fs.readSync(fd, b, 0, b.length, agentMsgOffset);
      fs.closeSync(fd); buf = b.toString('utf8');
    } catch { return; }
    agentMsgOffset = size; saveAgentMsgState();
    for (const line of buf.split('\n')) {
      const l = line.trim(); if (!l) continue;
      try {
        const o = JSON.parse(l);
        if (o && o.player && o.text) agentMsgQueue.push({ player: String(o.player), text: String(o.text).slice(0, 240) });
      } catch {}
    }
  }
  function sendNextAgentMsg() {
    if (!agentMsgQueue.length) return;
    if (!bot || !bot.player || !bot.entity) return; // hors ligne : on garde la file
    const today = new Date().toISOString().slice(0, 10);
    if (agentMsgDay !== today) { agentMsgDay = today; agentMsgCount = 0; }
    if (agentMsgCount >= AGENT_MSG_MAX_PER_DAY) {
      if (agentMsgQueue.length) { console.log(`[agent-msg] plafond ${AGENT_MSG_MAX_PER_DAY}/jour atteint, ${agentMsgQueue.length} en attente`); agentMsgQueue = []; }
      return;
    }
    const m = agentMsgQueue.shift();
    if (isBlacklisted(m.player)) { console.log(`[agent-msg] ${m.player} blackliste, ignore`); return; }
    if (m.player.toLowerCase() === String(bot.username).toLowerCase()) return;
    msgTo(m.player, m.text);
    agentMsgCount++;
    saveAgentMsgState();
    console.log(`[agent-msg] MP a ${m.player} (${agentMsgCount}/${AGENT_MSG_MAX_PER_DAY} du jour, ${agentMsgQueue.length} en file)`);
  }
  setInterval(pollAgentMsgs, 5000);
  setInterval(sendNextAgentMsg, AGENT_MSG_SPACING_MS);

  // l'agent ecrit des lignes {cmd:"/tpa Joueur"} dans agent-cmds.jsonl ;
  // strictement limite aux commandes de teleportation, rien d'autre ne passe
  const AGENT_CMDS_FILE = path.join(__dirname, 'agent-cmds.jsonl');
  const AGENT_CMDS_OK = /^\/(tpa|tpaccept|tpahere)(\s|$)/;
  let agentCmdsOffset = 0;
  try { agentCmdsOffset = fs.statSync(AGENT_CMDS_FILE).size; } catch {}
  setInterval(() => {
    let size = 0;
    try { size = fs.statSync(AGENT_CMDS_FILE).size; } catch { return; }
    if (size < agentCmdsOffset) agentCmdsOffset = 0;
    if (size <= agentCmdsOffset) return;
    let buf = '';
    try {
      const fd = fs.openSync(AGENT_CMDS_FILE, 'r');
      const b = Buffer.alloc(size - agentCmdsOffset);
      fs.readSync(fd, b, 0, b.length, agentCmdsOffset);
      fs.closeSync(fd); buf = b.toString('utf8');
    } catch { return; }
    agentCmdsOffset = size;
    for (const line of buf.split('\n')) {
      const l = line.trim(); if (!l) continue;
      try {
        const o = JSON.parse(l);
        if (o && typeof o.cmd === 'string') {
          const cmd = o.cmd.slice(0, 120).trim();
          if (!AGENT_CMDS_OK.test(cmd)) { console.log(`[agent-cmd] refuse (hors whitelist) : ${cmd}`); continue; }
          if (!bot || !bot.player) { console.log('[agent-cmd] bot hors ligne, commande ignoree'); continue; }
          bot.chat(cmd);
          console.log(`[agent-cmd] execute : ${cmd}`);
        }
      } catch {}
    }
  }, 3000);

  // MP rejete (reglage vie privee du destinataire) : on lui envoie une demande
  // d'ami (l'accepter debloque les MP) et UNE seule explication en chat public
  const unreachableNotified = new Set();
  function handleUnreachable(player) {
    if (!player || unreachableNotified.has(player)) return;
    unreachableNotified.add(player);
    console.log(`[banque] MP bloqués vers ${player} (friends only), demande d'ami envoyée`);
    queueSend(`/friend add ${player}`);
    queueSend(`${player} your DMs are closed! Accept my friend request (or enable DMs) to see your OutMind bank replies.`);
  }

  // ---------- dépôts ----------
  function handleDeposit(payer, amountStr) {
    const amount = parseAmount(amountStr);
    if (!isFinite(amount) || amount <= 0) return;
    if (payer === bot.username) return;
    // mutation sous verrou : un tick du bridge au meme instant ne peut plus
    // ecraser ce depot (le read-modify-write croise perdait une ecriture)
    try {
      ledger.mutate('depot', (bals, note) => {
        // DonutSMP retire parfois le point Floodgate dans ses confirmations de
        // paiement : si le nom recu n'a pas de compte mais que sa version
        // pointee en a un, c'est le meme joueur Bedrock -- on recolle le point.
        if (!payer.startsWith('.') && Object.hasOwn(bals, '.' + payer)) {
          if (!Object.hasOwn(bals, payer)) {
            payer = '.' + payer;
          } else {
            // homonyme Java ET Bedrock : on tranche par le tab (UUID Floodgate =
            // 00000000-0000-0000-0009-...). Inconnu = depot parque pour credit
            // manuel, jamais credite au hasard (audit 2026-09-03, H4)
            const tab = (bot.players && (bot.players['.' + payer] || bot.players[payer])) || null;
            const uuid = tab && tab.uuid ? String(tab.uuid) : '';
            if (bot.players && bot.players['.' + payer]) payer = '.' + payer;
            else if (/^00000000-0000-0000-0009-/i.test(uuid)) payer = '.' + payer;
            else if (!uuid) throw new Error(`homonyme Java/Bedrock ${payer}, UUID inconnu au tab : credit manuel`);
          }
        }
        note(payer, amount);
        bals[payer] = (bals[payer] || 0) + amount;
        balances = bals;
      });
    } catch (e) {
      // le paiement Donut est DEJA encaisse : si le credit echoue (verrou
      // bloque, fichier corrompu), la trace part dans un journal dedie + une
      // alerte, pour un credit manuel - jamais un depot avale en silence
      console.error(`[banque] DEPOT NON CREDITE ${payer} ${fmt(amount)} : ${e.message}`);
      try {
        fs.appendFileSync(path.join(__dirname, 'deposits-failed.jsonl'),
          JSON.stringify({ at: new Date().toISOString(), payer, amount, error: e.message }) + '\n');
        fs.appendFileSync(ALERTS_FILE,
          JSON.stringify({ at: Date.now(), text: `[bot] depot NON credite : ${payer} ${fmt(amount)} (${e.message}) - voir deposits-failed.jsonl, credit manuel requis` }) + '\n');
      } catch {}
      return;
    }
    logTx({ type: 'depot', player: payer, amount, balance: balances[payer] });
    treasuryAdjust(amount);
    console.log(`[banque] dépôt : ${payer} +${fmt(amount)} (solde ${fmt(balances[payer])})${isBlacklisted(payer) ? ' [blacklist, pas de confirmation]' : ''}`);
    if (!isBlacklisted(payer)) {
      msgTo(payer, `Thank you. Your money has been added to your account. Meet us on prestigiasmp.net to play our casino. Your balance: ${fmt(balances[payer])}`);
      // follow du client apres son depot. Fire-and-forget voulu : si le bot le
      // follow deja, DonutSMP repond une erreur en message systeme qu'aucun
      // handler ne consomme, et le depot est deja credite quoi qu'il arrive.
      const jour = new Date().toISOString().slice(0, 10);
      if (followSent.get(payer) !== jour) { followSent.set(payer, jour); queueSend(`/follow ${payer}`); }
    }
  }

  // ---------- retraits ----------
  function isOnPrestigia(player) {
    // online.json est ecrit par bridge.js depuis les statuts du plugin OutMindLink
    try {
      const o = JSON.parse(fs.readFileSync(path.join(__dirname, 'online.json'), 'utf8'));
      // statut perime = inconnu : on REFUSE (fail closed). Avant, perime valait
      // « pas en ligne » et permettait un double retrait MP + /cashout (audit 2026-09-03)
      if (!(Date.now() - o.at < 90000) || !Array.isArray(o.players)) return 'unknown';
      return o.players.includes(player);
    } catch { return 'unknown'; }
  }

  function handleWithdraw(player, arg) {
    if (bankFrozen() || bridgeStale()) {
      // banque gelee ou bridge muet : les soldes peuvent ignorer des pertes en
      // jeu pas encore consommees - payer maintenant serait une double depense
      msgTo(player, `Withdrawals are paused for a few minutes (bank maintenance). Your balance is safe, try again shortly.`);
      console.log(`[banque] retrait refuse (${bankFrozen() ? 'banque gelee' : 'bridge muet'}) : ${player}`);
      return;
    }
    const enLigne = isOnPrestigia(player);
    if (enLigne === 'unknown') {
      msgTo(player, `I cannot confirm you are logged out of the casino right now. Try again in a minute.`);
      console.log(`[banque] retrait differe : ${player}, statut casino inconnu (online.json perime)`);
      return;
    }
    if (enLigne) {
      msgTo(player, `Your balance is currently in use on prestigiasmp.net. Disconnect from there first, then try again.`);
      return;
    }
    loadBalances();
    const bal = soldeDe(balances, player);
    if (bal <= 0) {
      msgTo(player, `You have no OutMind balance.`);
      return;
    }
    let amount = (!arg || arg === 'all' || arg === 'tout') ? bal : parseAmount(arg);
    if (!isFinite(amount) || amount <= 0) {
      msgTo(player, `Invalid amount. Usage: !withdraw <amount|all>`);
      return;
    }
    if (amount > bal) {
      msgTo(player, `Insufficient balance. Your exact balance: $${bal.toLocaleString('en-US', { maximumFractionDigits: 2 })} : say "pay me all" to withdraw everything.`);
      return;
    }
    amount = Math.floor(amount);
    // MEME plafond journalier que les cashouts. Ce chemin-ci sort l'argent sans
    // passer par le bridge : le 2026-08-16, a player a demande « cashout »
    // en MP, le bot lui a repondu la syntaxe « pay me », et il a sorti 90M alors
    // que le plafond du jour etait deja consomme. Le compteur est partage entre
    // les deux processus par daily-cap.json, sinon chacun compte dans son coin.
    const left = dailyRemaining(player);
    const grade = isInvestor(player) ? 'Investor' : 'Gambler';
    if (left <= 0) {
      msgTo(player, `Daily withdrawal limit reached (${grade}: ${fmt(playerDailyMax(player))}/day). Your balance is safe, it resets at midnight.`);
      console.log(`[banque] retrait refuse : ${player} ${fmt(amount)} (plafond journalier atteint, ${grade})`);
      return;
    }
    if (amount > left) {
      msgTo(player, `Daily limit: you can take ${fmt(left)} more today (${grade}: ${fmt(playerDailyMax(player))}/day). The rest stays on your balance.`);
      console.log(`[banque] retrait plafonne : ${player} demande ${fmt(amount)}, reste ${fmt(left)} (${grade})`);
      amount = Math.floor(left);
    }
    // Refonte 2026-08-30 : le retrait passe par le MEME pipeline que les
    // cashouts (donut-payouts.jsonl -> pendingPayouts, marque src:'mp') au
    // lieu d'un /pay a l'aveugle. Ce que ca change : retries bornes si le
    // /pay echoue, confirmation « You paid » exigee avant d'ajuster la caisse
    // et de journaliser, alerte admin si jamais confirme. Le debit du grand
    // livre, lui, reste immediat et ferme. Le quota est RESERVE atomiquement
    // (plus de check-then-act entre les deux portes).
    const granted = Math.floor(quotas.reserve(player, amount,
      (bankState && typeof bankState.treasury === 'number') ? bankState.treasury : null));
    if (granted <= 0) {
      msgTo(player, `Daily withdrawal limit reached (${grade}: ${fmt(playerDailyMax(player))}/day). Your balance is safe, it resets at midnight.`);
      return;
    }
    if (granted < amount) {
      msgTo(player, `Daily limit: paying ${fmt(granted)} now, the rest stays on your balance.`);
    }
    amount = granted;
    try {
      ledger.mutate('retrait', (bals, note) => {
        // le solde a ete lu HORS verrou : un cashout casino applique entre-temps
        // par le bridge ne doit pas etre paye une seconde fois ici
        if (soldeDe(bals, player) < amount) throw new Error('solde insuffisant sous verrou');
        note(player, -amount);
        bals[player] = soldeDe(bals, player) - amount;
        balances = bals;
      });
    } catch (e) {
      quotas.release(player, amount);
      console.warn(`[banque] retrait abandonne : ${player} ${fmt(amount)} (${e.message})`);
      msgTo(player, `Your balance just changed. Check it with "balance" and try again.`);
      return;
    }
    fs.appendFileSync(PAYOUTS_FILE, JSON.stringify({ at: Date.now(), player, amount, src: 'mp' }) + '\n');
    console.log(`[banque] retrait : ${player} -${fmt(amount)} (solde ${fmt(balances[player])}), /pay via le pipeline cashout`);
    msgTo(player, `${fmt(amount)} is on its way to your DonutSMP purse. Remaining balance: ${fmt(balances[player])}`);
    processPayouts(player); // le joueur est la, on paye tout de suite
  }

  // ---------- paiement des cashouts ----------
  let payoutTimer = null;
  // Un cashout n'est retente qu'un nombre BORNE de fois. Sans ce plafond, une
  // confirmation non reconnue fait repayer indefiniment : le 2026-08-16,
  // a player a recu trois fois 1,99M pour un seul ordre, parce que DonutSMP
  // confirme « You paid a player $ 1.9M » et que la tolerance de 2 % rejetait
  // l'arrondi. Le detecteur est corrige plus bas, ce plafond est la ceinture :
  // meme detection cassee, la perte reste bornee et l'admin est prevenu.
  const PAYOUT_MAX_TRIES = 3;

  // Fenetre "in-flight" : un /pay vient de partir, sa confirmation peut mettre
  // quelques secondes (file d'envoi a 1,5 s/msg). Pendant ce temps, AUCUN
  // re-envoi, meme force - sans ce plancher, un simple whisper du joueur
  // re-declenchait processPayouts(force) et re-payait le meme retrait (revue
  // 2026-08-30, finding critique : 3 paiements pour un debit).
  const PAYOUT_INFLIGHT_MS = 90 * 1000;

  function processPayouts(forcePlayer) {
    if (!ready) return;
    if (bankFrozen()) return; // banque gelee : les dettes attendent, rien n'est perdu
    loadNewPayouts();
    const now = Date.now();
    for (const p of bankState.pendingPayouts) {
      const due = forcePlayer
        ? p.player.toLowerCase() === forcePlayer.toLowerCase()
        : now - p.lastTry > 5 * 60 * 1000;
      if (!due) continue;
      if (p.alerted) continue; // deja abandonne : plus d'increments cosmetiques (vu : 706 tries)
      if (now - (p.lastTry || 0) < PAYOUT_INFLIGHT_MS) continue; // /pay en vol, on attend sa confirmation
      if (p.suspect) {
        // un /pay est parti juste avant une deconnexion, sans confirmation :
        // il a peut-etre ete PAYE. Re-payer a l'aveugle serait le vrai risque -
        // verification humaine (transactions Donut) avant toute relance.
        if (!p.alerted) {
          p.alerted = true;
          console.warn(`[cashout] ⚠ ${p.player} ${fmt(p.amount)} : /pay parti sans confirmation avant une deconnexion, relances suspendues`);
          if (pteroConsole) pteroConsole.sendCommand(`cashoutfailed ${p.player} payment_delayed`).catch(() => {});
          if (process.env.ADMIN_USER) {
            msgTo(process.env.ADMIN_USER, `Cashout ${p.player} ${fmt(p.amount)}: /pay sent right before a disconnect, no confirmation. Check Donut history before resending.`);
          }
        }
        continue;
      }
      p.tries = (p.tries || 0) + 1;
      if (p.tries > PAYOUT_MAX_TRIES) {
        p.alerted = true;
        console.warn(`[cashout] ⚠ ${p.player} ${fmt(p.amount)} : ${PAYOUT_MAX_TRIES} tentatives sans confirmation, arret des relances`);
        if (pteroConsole) pteroConsole.sendCommand(`cashoutfailed ${p.player} payment_stalled`).catch(() => {});
        if (process.env.ADMIN_USER) {
          msgTo(process.env.ADMIN_USER, `Cashout ${p.player} ${fmt(p.amount)} non confirme apres ${PAYOUT_MAX_TRIES} essais, verifie a la main.`);
        }
        continue;
      }
      p.lastTry = now;
      const cmd = `/pay ${p.player} ${Math.floor(p.amount)}`;
      // ceinture : jamais deux /pay identiques dans la file d'envoi
      if (!sendQueue.includes(cmd)) queueSend(cmd, true);
    }
    saveBankState();
  }

  // ---------- réconciliation de caisse ----------
  let awaitingBalSince = 0;
  let balTimer = null;
  // Lecture de /bal aberrante en attente de confirmation. Le 2026-08-27, un
  // /bal servi pendant une reconnexion a repondu ~$3 : la caisse est passee
  // de 398M a $3 d'un coup, et un cashout de 30M a ete plafonne a $0.9.
  // Un ecart enorme n'est donc cru qu'apres DEUX lectures concordantes.
  let suspectBal = null;
  function checkTreasury() {
    awaitingBalSince = Date.now();
    queueSend('/bal', true);
  }
  function reconcileTreasury(actual) {
    if (!isFinite(actual)) return;
    if (bankState.treasury == null) {
      bankState.treasury = actual;
      saveBankState();
      console.log(`[caisse] référence initiale : ${fmt(actual)}`);
      return;
    }
    const diff = actual - bankState.treasury;
    // /bal peut afficher un montant abrégé/arrondi, d'où la tolérance
    const tol = Math.max(2000, actual * 0.02);
    const enorme = Math.max(10000000, bankState.treasury * 0.10);
    if (Math.abs(diff) > enorme) {
      const confirme = suspectBal
        && Math.abs(actual - suspectBal.value) <= Math.max(2000, actual * 0.02)
        && Date.now() - suspectBal.at < 5 * 60 * 1000;
      if (!confirme) {
        suspectBal = { value: actual, at: Date.now() };
        console.log(`[caisse] lecture suspecte : ${fmt(actual)} au lieu de ${fmt(bankState.treasury)} attendu, re-verification dans 30 s`);
        setTimeout(checkTreasury, 30000);
        return;
      }
    }
    suspectBal = null;
    if (diff > tol) {
      fs.appendFileSync(ORPHANS_FILE, JSON.stringify({ at: new Date().toISOString(), amount: diff }) + '\n');
      console.log(`[caisse] surplus : +${fmt(diff)} reçu pendant une absence (payeur inconnu, noté dans orphan-deposits.jsonl)`);
      if (process.env.ADMIN_USER) {
        msgTo(process.env.ADMIN_USER, `OutMind: ~${fmt(diff)} received while I was offline (payer unknown). Logged for manual credit.`);
      }
    } else if (diff < -tol) {
      console.log(`[caisse] ⚠ sortie d'argent inattendue : ${fmt(diff)}`);
      if (process.env.ADMIN_USER) {
        msgTo(process.env.ADMIN_USER, `OutMind WARNING: treasury dropped by ${fmt(-diff)} unexpectedly. Check the account.`);
      }
    }
    bankState.treasury = actual;
    saveBankState();
  }

  // ---------- commandes ----------
  function handleCommand(player, message) {
    // en mode furtif la file d'envoi est gelée : traiter un !retrait débiterait
    // le solde sans que le /pay parte jamais, donc on ignore toute commande
    if (!ready) return;
    const [cmd, ...args] = message.trim().split(/\s+/);
    switch (cmd.toLowerCase()) {
      case '!solde':
      case '!balance':
        loadBalances();
        msgTo(player, `Your OutMind balance: ${fmt(balances[player] || 0)}`);
        break;
      case '!retrait':
      case '!withdraw':
      case '!rendre':
        handleWithdraw(player, args[0]);
        break;
      case '!banque':
      case '!aide':
      case '!help':
        msgTo(player, `OutMind bank: /pay ${bot.username} <amount> to deposit. Tell me "pay me x" or "pay me all" to get your money back, !balance to check.`);
        break;
      case '!ping':
        msgTo(player, 'pong');
        break;
    }
  }

  // Le lobby DonutSMP kick « Invalid sequence » les clients qui répondent aux pings
  // anticheat pendant la phase d'arrivée. Le client vanilla « skip » ses ticks (donc
  // ne pong pas) ~2000 ticks. On retire le pong auto de mineflayer et on ne répond
  // qu'une fois la période de grâce finie.
  // GrimAC kick « Invalid sequence » les clients 1.21.2+ qui n'envoient pas
  // client_tick_end à chaque tick (mineflayer ne l'envoie pas encore, PR #3948
  // pas mergée) : on l'envoie nous-mêmes toutes les 50 ms
  let tickEndTimer = null;
  // GC de chunks (2026-08-21) : malgré viewDistance:2, le serveur pousse bien
  // plus de colonnes (constaté : 5 202 colonnes, ~310 Mo de tas, montée à
  // ~50 Mo/min -> max_memory_restart toutes les ~20 min). On garde un rayon
  // de CHUNK_KEEP chunks autour du compte (la physique/GrimAC lit ces blocs,
  // tout décharger = kick « Invalid sequence ») et on jette le reste.
  let chunkGcTimer = null;
  bot.on('login', () => {
    console.log(`[bot] [${new Date().toISOString()}] connecté en tant que ${bot.username}`);
    reconnectDelay = 5000;
    inGameNow = true;
    writeBotStatus();
    // tick_end est envoyé par le patch local dans node_modules/mineflayer/lib/plugins/physics.js
    // (PR #3948) : synchronisé avec la boucle physique. NE PAS doubler ici.
  });

  // Retour a la banque (demande Ryan 2026-09-02). Deux cas font perdre la
  // position : la mort (respawn au spawn du serveur) et une reconnexion apres
  // un redemarrage/maj DonutSMP (limbo puis keepAliveError : le compte repop au
  // spawn, constate le 2026-09-02 a 00:53). Dans les deux cas on rentre au
  // /home Banque apres un delai. L'orientation est posee par le /home lui-meme.
  // Le delai de reconnexion laisse passer la fenetre de maj : le serveur previent
  // « do not teleport or you will lose your location, you will be put back shortly ».
  const HOME_CMD = process.env.HOME_CMD || '/home Banque';
  const HOME_AFTER_DEATH_MS = parseInt(process.env.HOME_AFTER_DEATH_MS || '60000', 10);
  const HOME_AFTER_SPAWN_MS = parseInt(process.env.HOME_AFTER_SPAWN_MS || '60000', 10);
  let homeTimer = null;

  function planifierRetourBanque(delayMs, raison) {
    if (homeTimer) clearTimeout(homeTimer);
    homeTimer = setTimeout(() => {
      homeTimer = null;
      // hors ligne ou mode furtif : la file d'envoi est gelee, la commande
      // resterait coincee dedans et partirait a contretemps
      if (!ready || !bot.player) { console.log(`[home] retour banque annule (${raison}, bot pas pret)`); return; }
      console.log(`[home] retour banque (${raison}) : ${HOME_CMD}`);
      queueSend(HOME_CMD);
    }, delayMs);
  }

  bot.on('death', () => {
    console.log(`[bot] [${new Date().toISOString()}] mort, respawn dans 2s...`);
    setTimeout(() => bot.respawn(), 2000);
    planifierRetourBanque(HOME_AFTER_DEATH_MS, 'mort');
  });
  let firstSpawn = true;
  bot.on('spawn', () => {
    console.log('[bot] spawn dans le monde');
    if (!chunkGcTimer) {
      const KEEP = parseInt(process.env.CHUNK_KEEP || '4', 10);
      chunkGcTimer = setInterval(() => {
        try {
          if (!bot.entity || !bot.world) return;
          const cx = Math.floor(bot.entity.position.x / 16);
          const cz = Math.floor(bot.entity.position.z / 16);
          let dropped = 0;
          for (const { chunkX, chunkZ } of bot.world.getColumns()) {
            if (Math.max(Math.abs(chunkX - cx), Math.abs(chunkZ - cz)) > KEEP) {
              bot.world.unloadColumn(chunkX, chunkZ);
              dropped++;
            }
          }
          if (dropped) console.log(`[gc] ${dropped} colonnes de chunks déchargées (> ${KEEP} chunks du compte)`);
        } catch {}
      }, 60 * 1000);
    }
    if (!firstSpawn) return;
    firstSpawn = false;
    if (process.env.BOT_SILENT === '1') {
      // mode furtif : le compte est restreint (pas de /pay, /msg), on idle
      // sans rien envoyer pour farmer du playtime jusqu'au déblocage
      console.log('[bot] mode furtif : aucun envoi, farm de playtime');
      return;
    }
    ready = true;
    if (process.env.ADMIN_USER) {
      setTimeout(() => msgTo(process.env.ADMIN_USER, 'Bot OutMind en ligne.'), 8000);
    }
    // vérifie la caisse au retour en ligne (paiements reçus pendant l'absence) puis toutes les 15 min
    setTimeout(checkTreasury, 25000);
    balTimer = setInterval(checkTreasury, 15 * 60 * 1000);
    // cashouts en attente : tentative au retour puis toutes les 10 s (demande
    // Ryan 2026-08-30 : cashout typique ~15-25 s au lieu de ~1 min). Le retry
    // par joueur reste espace de 5 min et l'in-flight de 90 s : la boucle ne
    // fait que ramasser les NOUVEAUX ordres plus vite.
    setTimeout(processPayouts, 30000);
    payoutTimer = setInterval(processPayouts, 10 * 1000);
    // une reconnexion apres redemarrage/maj DonutSMP repop au spawn : on rentre
    planifierRetourBanque(HOME_AFTER_SPAWN_MS, 'reconnexion');
  });

  // chat public
  bot.on('chat', (username, message) => {
    if (username === bot.username) return;
    console.log(`[chat] <${username}> ${message}`);
    maybeProspect(username, message);
    if (message.startsWith('!')) handleCommand(username, message);
  });

  // messages privés (/msg)
  const lastGreet = {};
  // joueurs remercies pour leur follow cette session : leur « help » a droit a
  // une reponse meme hors grand livre, c'est la suite promise par le message
  // de bienvenue. Perdu au redemarrage, et c'est acceptable : le cas couvert
  // est le follower qui repond dans la foulee.
  // ---------- prospection : un MP unique a qui parle gambling en chat ----------
  // Le risque (MP non sollicite depuis le compte caisse) est le meme que la pub
  // existante, assume par Ryan. Les garde-fous le contiennent : UN MP par joueur
  // a vie (persiste), quota journalier, 3 min minimum entre deux MP, delai
  // aleatoire de 20 a 50 s pour ne pas repondre au mot-cle a la milliseconde
  // comme un robot, et jamais aux clients existants ni aux blacklistes.
  // "bet" est exclu des mots-cles : en slang c'est un simple "ok".
  const PROSPECT_FILE = path.join(__dirname, 'prospected.json');
  const PROSPECT_ON = (process.env.PROSPECT || 'on') === 'on';
  const PROSPECT_WORDS = /\b(gambl(?:e|ing|er)s?|casinos?|roulette|jackpots?|slots|wagers?)\b/i;
  const PROSPECT_GAP_MS = 3 * 60 * 1000;
  const PROSPECT_MAX_PER_DAY = 30;
  let prospected = { sent: {}, day: null, count: 0, lastAt: 0 };
  try { prospected = { ...prospected, ...JSON.parse(fs.readFileSync(PROSPECT_FILE, 'utf8')) }; } catch {}
  const saveProspected = () => fs.writeFileSync(PROSPECT_FILE, JSON.stringify(prospected));
  // File d'attente PERSISTEE : un mot-cle enfile un prospect avec l'heure a
  // laquelle l'envoyer (delai aleatoire), et un ticker draine la file quand le
  // bot est en ligne. prospected.json ne marque un joueur "contacte" qu'APRES
  // l'envoi reel. Consequence : un pm2 restart (frequent, socketClosed) ne perd
  // plus rien, les prospects en attente survivent sur disque et repartent au
  // retour. C'est ce que "rendre la db prospect persistante" veut dire.
  const PROSPECT_PENDING_FILE = path.join(__dirname, 'prospect-pending.json');
  let prospectPending = [];
  try { prospectPending = JSON.parse(fs.readFileSync(PROSPECT_PENDING_FILE, 'utf8')) || []; } catch {}
  const savePending = () => { try { fs.writeFileSync(PROSPECT_PENDING_FILE, JSON.stringify(prospectPending)); } catch {} };
  function isBlacklistedName(key) {
    try {
      const bl = JSON.parse(fs.readFileSync(path.join(__dirname, 'blacklist.json'), 'utf8')) || [];
      return bl.map(x => String(x).toLowerCase()).includes(key);
    } catch { return false; }
  }
  function maybeProspect(username, message) {
    if (!PROSPECT_ON || !PROSPECT_WORDS.test(message)) return;
    const key = username.toLowerCase();
    if (prospected.sent[key]) return;                                   // deja contacte (a vie)
    if (prospectPending.some(p => p.player.toLowerCase() === key)) return; // deja en file
    if (username === process.env.ADMIN_USER) return;
    loadBalances();
    if (Object.keys(balances).some(pl => pl.toLowerCase() === key)) return; // client existant
    if (isBlacklistedName(key)) return;
    // enfile seulement : le quota et l'espacement s'appliquent a l'envoi reel,
    // dans le drain. Le delai aleatoire evite de repondre au mot-cle en robot.
    const delay = 20000 + Math.floor(Math.random() * 30000);
    prospectPending.push({ player: username, sendAfter: Date.now() + delay });
    savePending();
  }
  function drainProspects() {
    if (!prospectPending.length) return;
    if (!bot || !bot.player || !bot.entity) return;   // hors ligne : la file attend, rien perdu
    const now = Date.now();
    const today = new Date().toISOString().slice(0, 10);
    if (prospected.day !== today) { prospected.day = today; prospected.count = 0; saveProspected(); }
    if (prospected.count >= PROSPECT_MAX_PER_DAY) return;   // plafond du jour : on reprendra demain
    if (now - prospected.lastAt < PROSPECT_GAP_MS) return;  // espacement entre envois reels
    const idx = prospectPending.findIndex(p => p.sendAfter <= now);
    if (idx < 0) return;
    const item = prospectPending[idx];
    const key = item.player.toLowerCase();
    // re-verifie les gardes au moment de l'envoi : l'etat a pu changer depuis l'enfilage
    loadBalances();
    const drop = prospected.sent[key]
      || key === String(bot.username).toLowerCase()
      || Object.keys(balances).some(pl => pl.toLowerCase() === key)
      || isBlacklistedName(key);
    if (drop) { prospectPending.splice(idx, 1); savePending(); return; }
    prospectPending.splice(idx, 1);
    prospected.sent[key] = now;
    prospected.count++;
    prospected.lastAt = now;
    saveProspected();
    savePending();
    msgTo(item.player, 'Saw you mention gambling. We run a real casino on prestigiasmp.net: roulette, crash, jackpot, blackjack.');
    setTimeout(() => msgTo(item.player, '500K welcome bonus to try it free, and we pay winners back HERE in Donut dollars. Whisper "discord" for proof and vouches.'), 1500);
    console.log(`[prospect] MP envoye a ${item.player} (${prospected.count}/${PROSPECT_MAX_PER_DAY} du jour, ${prospectPending.length} en file)`);
  }
  setInterval(drainProspects, 10000);

  const followThanked = new Set();
  const followSent = new Map(); // payeur -> jour du dernier /follow
  bot.on('whisper', (username, message) => {
    if (username === bot.username) return;
    console.log(`[mp] <${username}> ${message}`);
    logSys(`WHISPER <${username}> ${message}`);
    // commande admin : exécute n'importe quelle commande in-game (réservé à ADMIN_USER, en MP)
    if (username === process.env.ADMIN_USER && message.startsWith('!cmd ')) {
      const raw = message.slice(5).trim();
      console.log(`[admin] ${username} exécute : ${raw}`);
      queueSend(raw);
      return;
    }
    // gestion des paiements parques (suspect/alerted), sans editer bank-state
    // a la main : « !payout drop <joueur> <montant> » abandonne la dette
    // (verifiee payee), « !payout retry <joueur> <montant> » relance a zero.
    if (username === process.env.ADMIN_USER && message.startsWith('!payout ')) {
      const [, action, cible, montantBrut] = message.trim().split(/\s+/);
      const montant = parseAmount(montantBrut);
      const idxP = bankState.pendingPayouts.findIndex((p) =>
        p.player.toLowerCase() === String(cible || '').toLowerCase()
        && (!isFinite(montant) || Math.abs(p.amount - montant) < 1));
      if (idxP < 0) { msgTo(username, `No pending payout for ${cible}.`); return; }
      const p = bankState.pendingPayouts[idxP];
      if (action === 'drop') {
        bankState.pendingPayouts.splice(idxP, 1);
        saveBankState();
        logTx({ type: 'payout-drop', player: p.player, amount: p.amount, by: username });
        console.log(`[admin] payout drop : ${p.player} ${fmt(p.amount)}`);
        msgTo(username, `Dropped: ${p.player} ${fmt(p.amount)} (marked as settled).`);
      } else if (action === 'retry') {
        p.suspect = false; p.alerted = false; p.tries = 0; p.lastTry = 0; p.sentAt = 0;
        saveBankState();
        console.log(`[admin] payout retry : ${p.player} ${fmt(p.amount)}`);
        msgTo(username, `Retrying: ${p.player} ${fmt(p.amount)}.`);
        processPayouts(p.player);
      } else {
        msgTo(username, `Usage: !payout drop|retry <player> <amount>`);
      }
      return;
    }
    // le « help » et le « discord » promis par le message de bienvenue du
    // follow. AVANT le filtre grand livre : un follower n'a en general pas
    // encore paye, le silence ici casserait l'entonnoir qu'on vient d'ouvrir.
    if (followThanked.has(username) && /^help[\s?!.]*$/i.test(message.trim())) {
      msgTo(username, `Here is how it works. Join prestigiasmp.net and you get a 500K bonus to try the games, free.`);
      msgTo(username, `Want to play bigger? /pay ${bot.username} <amount> right here, it lands on your casino balance in seconds.`);
      msgTo(username, `When you cash out, I pay you back here in real DonutSMP dollars. Whisper "discord" for our Discord. See you at the tables!`);
      return;
    }
    // l'invite Discord repond a TOUT le monde, meme hors grand livre : un
    // prospect qui whisper "discord" comme invite le copy fait partie de
    // l'entonnoir, l'ignorer casserait la promesse du message de prospection.
    if (/^discord[\s?!.]*$/i.test(message.trim())) {
      if (DISCORD_INVITE) msgTo(username, `Join the casino Discord: ${DISCORD_INVITE}`);
      return;
    }
    // silence total envers qui n'a jamais payé : seuls les joueurs du grand
    // livre (une entrée existe, même a 0) recoivent une reponse. Choix de Ryan,
    // 2026-08-16 : payer d'abord, parler ensuite. L'admin passe au-dessus.
    if (!nomSur(username) && username !== process.env.ADMIN_USER) { console.log(`[mp] <${username}> ignore (nom suspect)`); return; }
    if (!Object.hasOwn(loadBalances(), username) && username !== process.env.ADMIN_USER) {
      console.log(`[mp] <${username}> ignoré (hors grand livre)`);
      return;
    }
    if (isBlacklisted(username) && username !== process.env.ADMIN_USER) {
      console.log(`[mp] <${username}> ignoré (blacklist)`);
      return;
    }
    // le joueur se manifeste sur Donut : s'il a un cashout en attente, on paye
    // tout de suite au lieu d'attendre le prochain cycle de retry
    if (bankState.pendingPayouts.some((p) => p.player.toLowerCase() === username.toLowerCase())) {
      processPayouts(username);
    }
    if (message.startsWith('!')) { handleCommand(username, message); return; }
    // langage naturel annoncé dans l'accueil : « Pay me 20$ », « pay me all », « withdraw 5k »
    // le $ optionnel avant ou après le montant, car l'accueil écrit « Pay me x$ »
    const natural = message.trim().match(/^(?:pay\s*me|paid\s*me|give\s*me|send\s*me|refund(?:\s*me)?|rends?(?:-moi)?|withdraw)\s+\$?\s*(all|tout|[\d.,]+\s*[kmbKMB]?)\s*(?:\$|dollars?|bucks?)?\s*[.!]?$/i);
    if (natural) {
      handleCommand(username, `!retrait ${natural[1].replace(/\s/g, '').toLowerCase()}`);
      return;
    }
    // demande de solde en langage naturel : « sold », « solde », « balance », « bal »...
    if (/^(?:sold[eo]?|balance|bal|money|combien|how much)[\s?!.]*$/i.test(message.trim())) {
      const bal = loadBalances()[username] || 0;
      msgTo(username, `Your OutMind balance: $${bal.toLocaleString('en-US', { maximumFractionDigits: 2 })}`);
      return;
    }
    // lien Discord sur demande. Le filtre grand livre est deja passe plus haut,
    // un inconnu n'arrive jamais ici : meme discretion que balance et pay me.
    if (/^discord[\s?!.]*$/i.test(message.trim())) {
      if (DISCORD_INVITE) msgTo(username, `Rejoins le Discord du casino : ${DISCORD_INVITE}`);
      return;
    }
    // accueil sur tout autre MP, au plus une fois toutes les 10 min par joueur,
    // pour ne pas nourrir le spam ni saturer la file d'envoi (1 msg / 1,5 s)
    if (!ready) return;
    const now = Date.now();
    if (now - (lastGreet[username] || 0) < 600000) return;
    lastGreet[username] = now;
    const greetBal = loadBalances()[username] || 0;
    msgTo(username, `Hello, thank you for trusting OutMind. Your current balance: $${greetBal.toLocaleString('en-US', { maximumFractionDigits: 2 })}`);
    msgTo(username, `Pay me to add money to your balance. Say "pay me [number]" to withdraw, "pay me all" to get everything back, or "balance" anytime. Meet us on prestigiasmp.net to play our casino!`);
  });

  // messages système : détection des paiements
  bot.on('messagestr', (message) => {
    // JAMAIS de détection sur du chat joueur (<pseudo> ...) : n'importe qui peut
    // écrire « X paid you $Y » dans le chat pour se faire créditer (tentative vue en vrai)
    if (/^\s*<.+?>/.test(message)) return;
    // le destinataire du dernier /msg refuse les MP des inconnus
    if (/^\s*\S*\s*only accepts messages from friends/i.test(message)) {
      handleUnreachable(lastMsgTarget);
      return;
    }
    // un joueur nous follow : on le remercie et on lui vend le casino. C'est le
    // SEUL cas ou le bot parle a quelqu'un hors grand livre, et c'est voulu :
    // un follow est une main tendue, le silence la refuserait. Une fois par
    // joueur par session, un follow/unfollow en boucle ne spamme pas la file.
    const followed = message.match(/^(\.?[A-Za-z0-9_]{3,16}) followed you\b/);
    if (followed && followed[1] !== bot.username) {
      const who = followed[1];
      if (isBlacklisted(who)) {
        console.log(`[banque] follow de ${who} ignoré (blacklist)`);
        return;
      }
      // un follow fait ENTRER au grand livre (solde 0, jamais ecrase). Ce n'est
      // pas que de la compta : le bridge whiteliste tout joueur du grand livre
      // sur Prestigia, donc le follow suffit pour pouvoir rejoindre le casino
      // et toucher le bonus de bienvenue. C'est tout l'entonnoir : follow ->
      // whitelist -> 500K -> premier depot.
      if (!nomSur(who)) { console.log(`[banque] follow de ${who} ignore (nom suspect)`); return; }
      loadBalances();
      if (Object.hasOwn(balances, '.' + who)) { console.log(`[banque] follow de ${who} : un compte Bedrock .${who} existe deja, pas de jumeau Java`); return; }
      if (!Object.hasOwn(balances, who)) {
        ledger.mutate('follow', (bals, note) => {
          if (Object.hasOwn(bals, who)) return; // apparu entre-temps : jamais ecrase
          note(who, 0);
          bals[who] = 0;
          balances = bals;
        });
        console.log(`[banque] ${who} ajouté au grand livre (follow)`);
      }
      if (!followThanked.has(who)) {
        followThanked.add(who);
        logSys(`FOLLOW ${message}`);
        console.log(`[banque] follow reçu de ${who}, message de bienvenue envoyé`);
        msgTo(who, `Thanks for the follow! Come try our casino on prestigiasmp.net, a 500K welcome bonus is waiting for you, on the house.`);
        msgTo(who, `Win at the tables and cash out your profit right here, in real DonutSMP dollars. Whisper "help" if you want the full tour.`);
      }
      return;
    }
    // confirmation d'un /pay sortant : si elle correspond a un cashout en attente,
    // il est solde (caisse ajustee, transaction journalisee, joueur prevenu).
    // Voir confirmMatches : DonutSMP TRONQUE le montant confirme, une tolerance
    // en pourcentage ne suffit pas.
    // reponse negative de DonutSMP a un /pay en vol : le joueur est prevenu en jeu
    // (dialogue + son via Skript), une seule fois par cause, sans toucher aux relances
    if (/^That player does not exist/.test(message) || /^That user is not online/.test(message)) {
      try {
        const code = /exist/.test(message) ? 'player_unknown' : 'player_offline';
        for (const p of bankState.pendingPayouts) {
          if (Date.now() - (p.lastTry || 0) < 90 * 1000 && !p['notif_' + code]) {
            p['notif_' + code] = true;
            if (pteroConsole) pteroConsole.sendCommand(`cashoutfailed ${p.player} ${code}`).catch(() => {});
          }
        }
      } catch (e) { console.warn('[cashout] notif echec : ' + e.message); }
    }
    const paid = message.match(/^You paid (\.?[A-Za-z0-9_]{3,16}) \$?\s*([\d.,]+\s*[kmbKMB]?)/);
    if (paid) {
      // les entrees PARQUEES (suspect/alerted) ne matchent qu'en dernier
      // recours : sinon la confirmation d'un admin pay de rattrapage soldait
      // la vieille entree parquee et laissait la neuve repartir en retry -
      // joueur paye deux fois (contre-verif 2026-08-30)
      const chercher = (pred) => bankState.pendingPayouts.findIndex((p) =>
        pred(p)
        && sansPoint(p.player) === sansPoint(paid[1]) // DonutSMP peut manger le point Floodgate
        && confirmMatches(p.amount, paid[2]));
      let idx = chercher((p) => !p.suspect && !p.alerted);
      if (idx < 0) idx = chercher(() => true);
      if (idx >= 0) {
        const p = bankState.pendingPayouts.splice(idx, 1)[0];
        treasuryAdjust(-p.amount);
        saveBankState();
        logTx({ type: 'retrait', player: p.player, amount: p.amount, balance: loadBalances()[p.player] || 0 });
        console.log(`[cashout] payé : ${p.player} ${fmt(p.amount)}${p.src === 'mp' ? ' (retrait MP)' : ''}`);
        // signal immediat en jeu (script Skript cashout-status.sk : son + actionbar) ;
        // le plugin renverra le meme signal a l'arrivee du statut PAID, Skript ignore le doublon
        if (p.src !== 'mp' && pteroConsole) pteroConsole.sendCommand(`cashoutdone ${p.player} PAID`).catch(() => {});
        // source:'mp' = retrait bancaire par MP : le bridge NE le relaie pas au
        // plugin (le menu /cashout n'a rien demande), et le message au joueur
        // reste celui d'un retrait, pas d'un cashout casino
        fs.appendFileSync(PAYOUT_RESULTS_FILE, JSON.stringify({
          at: Date.now(), player: p.player, amount: p.amount, status: 'PAID',
          ...(p.src ? { source: p.src } : {}),
        }) + '\n');
        if (p.src === 'mp') {
          msgTo(p.player, `${fmt(p.amount)} has been sent back to you. Thanks for banking with OutMind!`);
        } else {
          msgTo(p.player, `Your cashout of ${fmt(p.amount)} has been paid to your DonutSMP account. Thanks for playing at the Outmind Casino!`);
        }
        return;
      }
    }
    // réponse au /bal de la réconciliation de caisse
    if (awaitingBalSince && Date.now() - awaitingBalSince < 15000) {
      // format réel DonutSMP : « You have $ 64,082,809 » (aucun mot-clé balance/money)
      // UNIQUEMENT le format officiel ancre « You have $ X » : l'ancien
      // fallback (balance|purse|pouch|money) matchait « money3 » dans le
      // pseudo du spammeur a player et lisait une caisse de $3,
      // detruisant la treasury (incident du 2026-08-27).
      const mb = message.match(/^you\s+have\s+\$?\s*([\d.,]+\s*[kmbKMB]?)/i);
      if (mb) {
        awaitingBalSince = 0;
        logSys(`BAL ${message}`);
        reconcileTreasury(parseAmount(mb[1]));
        return;
      }
    }
    for (const p of PAY_PATTERNS) {
      const m = message.match(p.re);
      if (m) {
        if (!nomSur(m[p.payer])) { logSys(`PAIEMENT REFUSE (nom suspect) ${message}`); console.warn(`[banque] depot refuse, nom suspect : ${m[p.payer]}`); return; }
        logSys(`PAIEMENT ${message}`);
        handleDeposit(m[p.payer], m[p.amount]);
        return;
      }
    }
    // trace les messages système qui parlent d'argent pour affiner les regex
    if (/pa(id|y)|received|sent|\$[\d.,]+/i.test(message) && !/^</.test(message)) {
      logSys(`SYS ${message}`);
    }
    // debug : affiche tous les messages système (le chat joueur est déjà filtré
    // par le return en tête de handler ; l'ancien filtre excluait à tort tout
    // message commençant par un mot de 3 à 16 lettres, donc quasiment tout)
    if (process.env.BOT_DEBUG_SYS === '1') {
      console.log(`[sys] ${message}`);
    }
  });

  bot.on('kicked', (reason) => {
    const txt = typeof reason === 'string' ? reason : JSON.stringify(reason);
    console.log(`[bot] [${new Date().toISOString()}] kick :`, txt);
    // session fantôme sur le proxy : retenter vite entretient le join-cache, on attend 3 min
    if (/already online/i.test(txt)) reconnectDelay = 180000;
  });
  bot.on('error', (err) => console.log('[bot] erreur :', err.message));

  bot.on('end', (reason) => {
    inGameNow = false;
    writeBotStatus();
    // un /pay parti dans les 3 dernieres minutes sans confirmation est
    // SUSPECT : il a pu aboutir juste avant la coupure. On le suspend au lieu
    // de le re-payer a l'aveugle au retour (revue 2026-08-30).
    {
      const cutoff = Date.now() - 3 * 60 * 1000;
      let marques = 0;
      for (const p of bankState.pendingPayouts) {
        if ((p.sentAt || 0) > cutoff && !p.suspect) { p.suspect = true; marques++; }
      }
      if (marques) {
        try { saveBankState(); } catch {}
        console.warn(`[cashout] ${marques} paiement(s) sans confirmation avant la coupure : suspendus (verification manuelle)`);
      }
    }
    if (homeTimer) { clearTimeout(homeTimer); homeTimer = null; }
    if (senderTimer) { clearInterval(senderTimer); senderTimer = null; }
    if (tickEndTimer) { clearInterval(tickEndTimer); tickEndTimer = null; }
    if (chunkGcTimer) { clearInterval(chunkGcTimer); chunkGcTimer = null; }
    if (balTimer) { clearInterval(balTimer); balTimer = null; }
    if (payoutTimer) { clearInterval(payoutTimer); payoutTimer = null; }
    console.log(`[bot] déconnecté (${reason}), reconnexion dans ${reconnectDelay / 1000}s...`);
    setTimeout(createBot, reconnectDelay);
    reconnectDelay = Math.min(reconnectDelay * 2, 60000);
  });
}

createBot();
