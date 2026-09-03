// ============================================================
//  Outmind Casino : bot Discord
//
//  Deux fonctions :
//
//  1. LIAISON DE COMPTE. Le joueur tape /verify EN JEU (plugin
//     OutMindLink), recoit un code de 6 caracteres valable 10 min,
//     et le rentre ici (slash /verify ou bouton du panneau).
//     C'est le seul moyen de prouver qu'il possede le pseudo.
//
//  2. PANNEAU DE RETRAIT dans le channel cashout. Solde retirable,
//     montants rapides, montant libre, confirmation, puis
//     "outmind cashout <joueur> <montant>" en console via l'API
//     Pterodactyl. Le pipeline existant fait tout le reste :
//     plugin (debit + outbox) -> bridge (plafond banque + ordre)
//     -> bot mineflayer (/pay sur DonutSMP) -> payout-results.
//
//  AUCUN retrait sans compte lie. Le montant vit dans le customId
//  du bouton, mais l'identite vient TOUJOURS de interaction.user.id
//  croise avec links.json : un customId bricole ne peut pas retirer
//  l'argent de quelqu'un d'autre.
//
//  Les soldes sont lus en LOCAL dans les fichiers du bot mineflayer
//  (meme machine), donc sans latence : bridge-state.json est le
//  miroir des soldes en jeu tenu par le bridge. Il peut avoir
//  quelques secondes de retard sur une partie en cours, ce n'est
//  qu'un affichage : le plugin recalcule le vrai plafond au moment
//  de la commande et refuse tout seul si le miroir etait en avance.
//
//  Le token n'est dans AUCUN fichier : variable User DISCORD_BOT_TOKEN.
// ============================================================
const {
  Client, GatewayIntentBits, REST, Routes, SlashCommandBuilder, MessageFlags,
  EmbedBuilder, ActionRowBuilder, ButtonBuilder, ButtonStyle,
  ModalBuilder, TextInputBuilder, TextInputStyle, PermissionFlagsBits,
  AttachmentBuilder, StringSelectMenuBuilder, StringSelectMenuOptionBuilder,
} = require('discord.js');
const fs = require('fs');
const path = require('path');
const { execSync, spawn } = require('child_process');
const autodeposit = require('./autodeposit');
const stats = require('./stats');
const adminLib = require('./admin');
const pitch = require('./pitch');
const adminAI = require('./admin-ai');
const bank = require('./bank');
const agentPost = require('./agent-post');
const publicAI = require('./public-ai');
const chain = require('./chain');

// ---------- reglages ----------

// Le cashout console accepte les joueurs hors ligne depuis le jar OutMindLink
// deploye le 2026-08-16 a 12h50 (doCashout prend un OfflinePlayer). Repasser a
// false si un jour on revient a un jar anterieur : le bot refusera alors
// proprement au lieu d'envoyer une commande qui ne ferait rien.
const OFFLINE_CASHOUT = true;

const BOT_DIR = process.env.BOT_DIR || path.join(__dirname, '..', 'mineflayer-bot');
// Identifiants Discord et panel : jamais en dur, tout vient de l'environnement
// (discord-bot/.env via ecosystem.config.js) ou du .env de mineflayer-bot.
const APP_ID = process.env.DISCORD_APP_ID || '';
const GUILD_ID = process.env.DISCORD_GUILD_ID || '';
if (!APP_ID || !GUILD_ID) { console.error('DISCORD_APP_ID / DISCORD_GUILD_ID absents de l\'environnement.'); process.exit(1); }
const DISCORD_INVITE = process.env.DISCORD_INVITE || '';

const PTERO_PANEL_URL = process.env.PTERO_PANEL_URL || readBotEnvKey('PTERO_PANEL_URL') || '';
const PTERO_SERVER_ID = process.env.PTERO_SERVER_ID || readBotEnvKey('PTERO_SERVER_ID') || '';
const VERIFY_FILE = '/plugins/OutMindLink/discord-verify.json';
const STATE_FILE = '/plugins/OutMindLink/state.yml';

const LINKS_FILE = path.join(__dirname, 'links.json');
const VERIFIED_ROLE = 'Verified';

// journal public des mouvements de banque. Le channel est resolu par son NOM :
// le bot n'a pas Manage Channels, il ne peut donc pas le creer, il faut qu'il
// existe. Tant qu'il manque, l'offset n'avance pas et tout est rattrape le jour
// ou le channel apparait. Le salon reel est au singulier, le pluriel est
// accepte pour ne pas casser si Ryan le renomme.
const TX_CHANNELS = ['past-transaction', 'past-transactions'];

// salon du panneau de retrait. Le bot l'y pose tout seul au demarrage et
// reutilise ensuite le MEME message (id garde dans links.json) : sans ca chaque
// redemarrage empilerait un panneau de plus.
// Rafraichi sur le cycle d'une minute (VAULT_REFRESH_MS) et pas sur un rythme
// a lui : le bouton de retrait doit se griser des que la banque tombe.
const PANEL_CHANNELS = ['cashout', 'cash-out'];

// salon vitrine : fortune de la caisse, statut de la banque, top 10 des soldes.
// Meme principe que le panneau, un seul message reutilise et edite.
const VAULT_CHANNELS = ['vault', 'the-vault'];
// Comptes staff exclus des classements publics (meme liste que le podium du
// spawn, tenue par bridge.js) : balances et retraits intacts, juste invisibles
// au tableau. Rien a voir avec la blacklist, qui coupe les retraits.
const LB_EXCLUDE = new Set((process.env.LEADERBOARD_EXCLUDE || '')
  .split(',').map((s) => s.trim().toLowerCase()).filter(Boolean));
const VAULT_REFRESH_MS = 60 * 1000;
const VAULT_TOP = 10;

// salon du rapport quotidien en image (module stats.js). Poste a 10h heure de
// Paris le bilan de la VEILLE, pas celui du jour en cours : les joueurs
// terminent leurs sessions vers 3h du matin, un rapport publie a minuit
// couperait la nuit en deux. 10h est aussi une heure morte sur le serveur,
// personne ne regarde des chiffres qui bougent encore.
//
// #bank-console est dans la categorie ADMIN, donc le rapport est PRIVE : il
// montre la marge de la maison et les pertes nominatives des joueurs, ce qui
// n'a rien a faire dans un salon public.
const STATS_CHANNELS = ['bank-console', 'bank_console'];
const STATS_HOUR = 10;
const STATS_TICK_MS = 5 * 60 * 1000;

// salon des avis. Il est ferme a l'ecriture pour @everyone : la parole y est
// ouverte joueur par joueur, pour 15 minutes, apres un /vouch 5 etoiles. Sans
// ce verrou la « permission temporaire » ne veut rien dire, tout le monde peut
// deja y ecrire.
const VOUCH_CHANNELS = ['vouch-us', 'vouch', 'vouches'];
const VOUCH_BONUS = 2000000;          // credite une seule fois par compte Minecraft
const VOUCH_WINDOW_MS = 15 * 60 * 1000;
const VOUCH_MIN_CHARS = 40;           // ignore si l'intent MessageContent est coupe
const VOUCH_SWEEP_MS = 60 * 1000;

// salons de presentation. Le contenu vit dans pitch.js, un message par
// document, repose au demarrage puis reedite sur place : les chiffres cites
// bougent, le message ne doit pas se dupliquer pour autant.
const PITCH_REFRESH_MS = 10 * 60 * 1000;

const BANK_ACCOUNT = 'OutmindCompany'; // le compte a payer sur DonutSMP
const DONUT_HOST = 'donutsmp.net';
const CASINO_HOST = 'prestigiasmp.net'; // le serveur ou l'on joue
const WELCOME_BONUS = 500000;      // reserve incashoutable des beneficiaires
// Plafonds de sortie LUS dans le .env de mineflayer-bot (BOT_DIR/.env), memes
// cles et memes defauts que lib/quotas.js : plus de copie a synchroniser a la
// main (2026-09-02 : Discord annoncait encore 50M/100M alors que le bridge
// appliquait 150M/250M). Un changement du .env demande un pm2 restart des deux.
function lireQuotasBotEnv() {
  const env = {};
  try {
    for (const ligne of fs.readFileSync(path.join(BOT_DIR, '.env'), 'utf8').split(/\r?\n/)) {
      const m = ligne.match(/^\s*([A-Z0-9_]+)\s*=\s*(.*?)\s*$/);
      if (m) env[m[1]] = m[2];
    }
  } catch (e) { console.warn(`quotas : .env de mineflayer-bot illisible (${e.message}), defauts utilises`); }
  return env;
}
const QUOTA_ENV = lireQuotasBotEnv();
const DAILY_MAX = QUOTA_ENV.CASHOUT_DAILY_MAX === '0'
  ? Infinity
  : Number(QUOTA_ENV.CASHOUT_DAILY_MAX || 50000000);
const DAILY_VAULT_PCT = Number(QUOTA_ENV.CASHOUT_DAILY_VAULT_PCT || 30) / 100;
const PLAYER_MAX_GAMBLER = Number(QUOTA_ENV.CASHOUT_PLAYER_MAX_GAMBLER || 50000000);
const PLAYER_MAX_INVESTOR = Number(QUOTA_ENV.CASHOUT_PLAYER_MAX_INVESTOR || 100000000);
const INVESTOR_MIN = Number(QUOTA_ENV.INVESTOR_MIN || 3000000); // seuil du grade Investor, comme cote plugin

// ---------- offre boost : 1 boost du serveur = 10M sur la balance ----------
// Un seul versement par compte Discord, a vie (claimed), meme si la personne
// re-booste. Un boosteur pas encore verifie est mis en attente (pending) et
// touche sa prime a l'instant du /verify. Kill switch : BOOST_OFFER=off.
const BOOST_REWARD = 10000000;
const BOOST_OFFER = (process.env.BOOST_OFFER || 'on') === 'on';
const BOOST_FILE = path.join(__dirname, 'boost-offers.json');
function loadBoosts() {
  try { return JSON.parse(fs.readFileSync(BOOST_FILE, 'utf8')); } catch { return { claimed: {}, pending: {} }; }
}
function saveBoosts(b) { fs.writeFileSync(BOOST_FILE, JSON.stringify(b, null, 2)); }
function grantBoost(discordId, tag) {
  const b = loadBoosts();
  if (b.claimed[discordId]) return 'claimed';
  const link = state.links[discordId];
  if (!link) {
    if (!b.pending[discordId]) { b.pending[discordId] = Date.now(); saveBoosts(b); }
    return 'pending';
  }
  adminLib.queueOrder({
    kind: 'credit', player: link.player, amount: BOOST_REWARD,
    reason: 'Server boost reward (limited offer)', by: `boost:${tag}`,
  });
  delete b.pending[discordId];
  b.claimed[discordId] = Date.now();
  saveBoosts(b);
  console.log(`Boost reward : ${BOOST_REWARD} pour ${link.player} (${tag})`);
  return 'granted';
}
const QUICK_AMOUNTS = [100000, 500000, 1000000];
const COLOR = 0xa18cd1;            // debut du degrade maison A18CD1 -> FBC2EB
const COLOR_BAD = 0xe05c5c;
const COLOR_OK = 0x5cc98c;
const BOT_STALE_MS = 2 * 60 * 1000; // bot Donut considere mort au-dela (comme le plugin)
const LOCK_MS = 90 * 1000;          // un seul cashout en vol par joueur
const ORDER_LOCK_MS = 30 * 1000;    // transfert/chain en attente du bridge (tick 10 s) : pas de cashout dessus
const YML_TTL_MS = 60 * 1000;       // cache du state.yml serveur
const POLL_MS = 15 * 1000;          // frequence de relecture des journaux du casino
const TX_BURST_MAX = 8;             // au-dela, un seul message recapitulatif

// ---------- environnement ----------

// process.env peut etre vide si le process parent a demarre AVANT le setx
// (vecu au premier lancement) : repli sur la variable User dans le registre
function userEnv(name) {
  if (process.env[name]) return process.env[name];
  try {
    const out = execSync(`reg query HKCU\\Environment /v ${name}`, { encoding: 'utf8' });
    const m = out.match(/REG_(?:EXPAND_)?SZ\s+(.+)/);
    return m ? m[1].trim() : null;
  } catch { return null; }
}

// la cle Ptero vit deja dans le .env du bot mineflayer, on la reutilise plutot
// que de la dupliquer (une seule cle a faire tourner le jour venu)
function readBotEnvKey(key) {
  try {
    const env = fs.readFileSync(path.join(BOT_DIR, '.env'), 'utf8');
    const m = env.match(new RegExp('^' + key + '=(.+)$', 'm'));
    return m ? m[1].trim() : null;
  } catch { return null; }
}
function readPteroKeyFromBotEnv() { return readBotEnvKey('PTERO_API_KEY'); }

const TOKEN = userEnv('DISCORD_BOT_TOKEN');
const PTERO_API_KEY = process.env.PTERO_API_KEY || readPteroKeyFromBotEnv();
autodeposit.init({ userEnv });
// Auto-depot (token Minecraft confie par le joueur) : COUPE par defaut depuis le
// 2026-09-03 (decision de Ryan). AUTODEPOSIT_ENABLED=on pour le rouvrir. Les
// autorisations deja chiffrees sur disque restent en place mais inutilisables.
const AUTODEPOSIT_ON = process.env.AUTODEPOSIT_ENABLED === 'on';
const AUTODEPOSIT_PAUSED = 'Auto deposit is paused for now. Deposit with `/pay` on DonutSMP as usual.';

if (!TOKEN) { console.error('DISCORD_BOT_TOKEN absent de l\'environnement.'); process.exit(1); }
if (!PTERO_API_KEY) { console.error('PTERO_API_KEY introuvable.'); process.exit(1); }

// ---------- etat local du bot ----------

// links : comptes lies. usedCodes : anti-reutilisation pendant le TTL du code.
// payoutOffset / txOffset : lignes deja traitees dans payout-results.jsonl et
// transactions.jsonl.
// lastStatsDay : dernier jour DEJA publie dans #stats. C'est ce champ, et pas
// une minuterie, qui empeche le doublon : le bot redemarre plusieurs fois par
// jour et une minuterie repartirait de zero a chaque fois.
// vouches : un avis paye par compte MINECRAFT, pas par compte Discord. La
// liaison est verifiee en jeu, mais un joueur peut delier et relier ; c'est le
// pseudo qui porte le droit au bonus.
// vouchWindows : droit de parole ouvert dans #vouch-us, avec sa date de
// peremption. Persiste exprès : une minuterie en memoire ne survivrait pas a un
// redemarrage et laisserait un overwrite ouvert pour toujours.
let state = {
  links: {}, usedCodes: [], payoutOffset: null, txOffset: null,
  panelMessageId: null, vaultMessageId: null, lastStatsDay: null,
  vouches: {}, vouchWindows: {}, ratings: [], vouchTopic: null, pitch: {},
};
if (fs.existsSync(LINKS_FILE)) {
  // links.json est la SEULE base des comptes lies. Un parse rate ne doit jamais
  // passer en silence : le bot repartirait d'un etat vide et l'ecraserait au
  // premier saveState, effacant tous les liens. On sort en erreur, le runner
  // reboucle et ca se voit dans discord.log. Le BOM est retire avant le parse
  // parce que Set-Content -Encoding utf8 en PowerShell 5.1 en pose un, et que
  // JSON.parse le refuse (vecu le 2026-08-16, base des liens perdue).
  try {
    state = { ...state, ...JSON.parse(fs.readFileSync(LINKS_FILE, 'utf8').replace(/^﻿/, '')) };
  } catch (e) {
    console.error(`links.json illisible (${e.message}). Refus de demarrer pour ne pas ecraser les comptes lies.`);
    process.exit(1);
  }
}
function saveState() { fs.writeFileSync(LINKS_FILE, JSON.stringify(state, null, 2)); }

const cashoutLocks = new Map(); // pseudo minuscule -> expiration du verrou

function linkOf(discordId) { return state.links[discordId] || null; }
function discordIdOf(player) {
  const key = String(player).toLowerCase();
  for (const [id, l] of Object.entries(state.links)) {
    if (String(l.player).toLowerCase() === key) return id;
  }
  return null;
}

// ---------- lecture des fichiers du casino (locaux) ----------

function readJson(file, fallback) {
  try { return JSON.parse(fs.readFileSync(path.join(BOT_DIR, file), 'utf8')); }
  catch { return fallback; }
}

function casinoSnapshot() {
  const bridge = readJson('bridge-state.json', {});
  const online = readJson('online.json', { players: [] });
  const botStatus = readJson('bot-status.json', { inGame: false, at: 0 });
  return {
    mirrored: bridge.mirrored || {},                 // solde en jeu (miroir du bridge)
    balances: readJson('balances.json', {}),         // grand livre banque
    treasury: (readJson('bank-state.json', {}).treasury) || 0,
    onlinePlayers: (online.players || []).map(p => String(p).toLowerCase()),
    blacklist: (readJson('blacklist.json', []) || []).map(p => String(p).toLowerCase()),
    // le bot Donut est considere mort au-dela de 2 min sans battement de coeur,
    // exactement comme cote plugin (un bridge mort est couvert par la meme regle)
    botOnline: !!botStatus.inGame && (Date.now() - (botStatus.at || 0) < BOT_STALE_MS),
  };
}

// ---------- API Pterodactyl ----------

async function api(pathname, opts = {}) {
  // meme regle que le bridge : jamais de fetch sans timeout vers le panel,
  // un appel qui pend bloquerait le handler d'interaction en cours
  return fetch(`${PTERO_PANEL_URL}/api/client/servers/${PTERO_SERVER_ID}${pathname}`, {
    signal: AbortSignal.timeout(20000),
    ...opts,
    headers: { Authorization: `Bearer ${PTERO_API_KEY}`, Accept: 'application/json', ...(opts.headers || {}) },
  });
}

async function readFileApi(file) {
  const res = await api(`/files/contents?file=${encodeURIComponent(file)}`);
  if (res.status === 404 || res.status === 400) return null; // pas encore cree
  if (!res.ok) throw new Error(`GET ${file} -> ${res.status}`);
  return res.text();
}

async function sendCommandApi(cmd) {
  const res = await api('/command', {
    method: 'POST',
    body: JSON.stringify({ command: cmd }),
    headers: { 'Content-Type': 'application/json' },
  });
  if (!res.ok && res.status !== 204) throw new Error(`COMMAND -> ${res.status}`);
}

async function fetchVerifyCodes() {
  const txt = await readFileApi(VERIFY_FILE);
  if (txt == null) return []; // aucun /verify tape en jeu pour l'instant
  const data = JSON.parse(txt);
  return Array.isArray(data.codes) ? data.codes : [];
}

// state.yml du plugin : on n'y lit que bonus-given (la reserve de 500K) et tx
// (statut du dernier retrait). Cache 60 s, ce fichier bouge peu et chaque appel
// est une requete au panel.
let ymlCache = { at: 0, bonusGiven: new Set(), tx: {}, invested: {} };
async function fetchPluginState() {
  if (Date.now() - ymlCache.at < YML_TTL_MS) return ymlCache;
  const txt = await readFileApi(STATE_FILE);
  const bonusGiven = new Set();
  const tx = {};
  const invested = {};
  if (txt) {
    // parseur volontairement minimal : deux sections plates et connues, pas de
    // dependance YAML pour ca
    const lines = txt.split(/\r?\n/);
    let section = null, txPlayer = null;
    for (const raw of lines) {
      const line = raw.replace(/\s+$/, '');
      if (/^[a-z-]+:/.test(line)) {
        section = line.split(':')[0];
        txPlayer = null;
        continue;
      }
      if (section === 'bonus-given') {
        const m = line.match(/^-\s+(.+)$/);
        if (m) bonusGiven.add(m[1].trim().toLowerCase());
      } else if (section === 'tx') {
        const p = line.match(/^ {2}([^\s:]+):$/);
        if (p) { txPlayer = p[1].toLowerCase(); tx[txPlayer] = {}; continue; }
        const kv = line.match(/^ {4}([a-z]+):\s*(.+)$/);
        if (kv && txPlayer) tx[txPlayer][kv[1]] = kv[2].trim();
      } else if (section === 'invested') {
        // total investi par joueur, en notation Java (1.05E8)
        const kv = line.match(/^ {2}([^\s:]+):\s*(.+)$/);
        if (kv) invested[kv[1].toLowerCase().replace(/^\./, '')] = Number(kv[2].trim()) || 0;
      }
    }
  }
  ymlCache = { at: Date.now(), bonusGiven, tx, invested };
  // Le bot Discord est le seul des trois processus branche sur state.yml : il
  // publie donc les totaux investis dans un fichier que le bridge et le bot
  // mineflayer lisent pour appliquer le plafond de retrait selon le grade.
  try {
    fs.writeFileSync(path.join(BOT_DIR, 'investors.json'),
      JSON.stringify({ at: Date.now(), invested }, null, 2));
  } catch (e) { console.warn('investors.json :', e.message); }
  return ymlCache;
}

// ---------- calcul du retirable ----------

// Meme regle que le plugin : solde en jeu moins la reserve du bonus de
// bienvenue, arrondi au dollar inferieur. Le solde banque est le plafond de ce
// qui partira reellement en dollars Donut, l'excedent revient en jeu tout seul.
async function withdrawableFor(player) {
  const snap = casinoSnapshot();
  const yml = await fetchPluginState();
  const key = player.toLowerCase();
  const findKey = (obj) => Object.keys(obj).find(k => k.toLowerCase() === key);
  const inGame = Number(snap.mirrored[findKey(snap.mirrored)] || 0);
  const bank = Number(snap.balances[findKey(snap.balances)] || 0);
  const reserve = yml.bonusGiven.has(key) ? WELCOME_BONUS : 0;
  const withdrawable = Math.max(0, Math.floor(inGame - reserve));
  return {
    inGame, bank, reserve, withdrawable,
    payable: Math.min(withdrawable, bank),
    blacklisted: snap.blacklist.includes(key),
    playerOnline: snap.onlinePlayers.includes(key),
    botOnline: snap.botOnline,
    treasury: snap.treasury,
    tx: yml.tx[key] || null,
    invested: yml.invested[key.replace(/^\./, '')] || 0,
    daily: dailyStatus(player),
    get dailyLeft() { return this.daily.left; },
    // ce qu'il peut REELLEMENT demander maintenant : son solde retirable rabote
    // par ce qui lui reste sur la journee. Proposer un bouton MAX a 60M quand
    // le quota n'en laisse passer que 43 serait un piege a reclamation.
    get cashoutMax() { return Math.min(withdrawable, this.daily.left); },
  };
}

// Ce qu'il reste a CE joueur sur la journee, en lisant les memes fichiers que
// le bridge et le bot mineflayer : son quota personnel selon son grade, et le
// plafond global de la maison. Le plus serre des deux gagne, exactement comme
// a l'application.
function dailyStatus(player) {
  const snap = casinoSnapshot();
  const today = new Date().toLocaleDateString('en-CA', { timeZone: 'Europe/Paris' });
  const d = readJson('daily-cap.json', {});
  const fresh = d.day === today ? d : { paid: 0, players: {} };
  const invested = (readJson('investors.json', {}).invested) || {};
  const key = String(player || '').toLowerCase();

  const isInvestor = (invested[key.replace(/^\./, '')] || 0) >= INVESTOR_MIN;
  const personalMax = isInvestor ? PLAYER_MAX_INVESTOR : PLAYER_MAX_GAMBLER;
  const personalUsed = (fresh.players || {})[key] || 0;
  const personalLeft = Math.max(0, personalMax - personalUsed);
  const houseLeft = Math.max(0, Math.min(DAILY_MAX, snap.treasury * DAILY_VAULT_PCT) - (fresh.paid || 0));

  return {
    grade: isInvestor ? 'Investor' : 'Gambler',
    personalMax, personalUsed, personalLeft, houseLeft,
    left: Math.min(personalLeft, houseLeft),
    blockedByHouse: houseLeft < personalLeft,
  };
}

// accepte 300000, 300k, 1.5M, $2b : les joueurs tapent naturellement les
// suffixes. Meme grammaire que parseAmount cote plugin. -1 si illisible.
function parseAmount(arg) {
  let raw = String(arg).replace(/,/g, '').replace(/\$/g, '').trim().toLowerCase();
  let mult = 1;
  if (raw.endsWith('k')) { mult = 1e3; raw = raw.slice(0, -1); }
  else if (raw.endsWith('m')) { mult = 1e6; raw = raw.slice(0, -1); }
  else if (raw.endsWith('b')) { mult = 1e9; raw = raw.slice(0, -1); }
  const n = Number(raw);
  if (!isFinite(n) || n < 0) return -1;
  return Math.floor(n * mult);
}

const money = (n) => '$' + Math.floor(n).toLocaleString('en-US');
function shortMoney(n) {
  if (n >= 1e9) return '$' + (n / 1e9).toFixed(n % 1e9 === 0 ? 0 : 1) + 'B';
  if (n >= 1e6) return '$' + (n / 1e6).toFixed(n % 1e6 === 0 ? 0 : 1) + 'M';
  if (n >= 1e3) return '$' + (n / 1e3).toFixed(n % 1e3 === 0 ? 0 : 1) + 'K';
  return '$' + n;
}

// ---------- panneau permanent ----------

// Les customId sont fixes et sans etat serveur : le panneau reste vivant apres
// un redemarrage du bot, sans avoir a le reposter.
// Le panneau reflete l'etat du bot banque. `refusal()` refusait deja un retrait
// bot eteint, mais APRES le clic : le joueur voyait un bouton vert, appuyait, et
// se prenait un refus. Un bouton grise qui dit pourquoi vaut mieux qu'un bouton
// vert qui ment. C'est aussi ce qui evite le ticket « j'ai cliqué et ça marche
// pas » a 3h du matin.
// ---------- Donut Pay : la marque des paiements ----------
// Le logo vit dans donut-pay.png a cote du bot. Fichier absent : tout marche
// sans vignette ; le deposer suffit a activer la marque, sans redeploiement.
// attachment:// plutot qu'une URL CDN Discord : celles-ci expirent (liens
// signes), le fichier joint est le seul hebergement fiable.
const DONUT_PAY_LOGO = path.join(__dirname, 'donut-pay.png');
function donutPay(embed, payload) {
  if (!fs.existsSync(DONUT_PAY_LOGO)) return payload;
  embed.setThumbnail('attachment://donut-pay.png');
  payload.files = [DONUT_PAY_LOGO];
  return payload;
}

function panelMessage(treasury, botOnline) {
  const embed = new EmbedBuilder()
    .setColor(botOnline ? COLOR : COLOR_BAD)
    .setTitle('⛁ OUTMIND CASINO ⛁')
    .setDescription(
      'Cash out your in-game balance to real DonutSMP dollars, paid by **OutmindCompany**.\n\n' +
      '**1.** Type `/verify` in game on the casino server.\n' +
      '**2.** Hit **Link account** below and enter your code.\n' +
      '**3.** **Deposit** to fund your account, **Cash out** to take it back.\n\n' +
      'Your money is backed 1:1. Anything above the bank balance comes straight back to your in-game balance.'
    )
    .addFields(
      { name: 'House vault', value: shortMoney(treasury), inline: true },
      { name: 'Bank', value: botOnline ? 'Online' : 'Offline', inline: true })
    .setFooter({ text: botOnline ? 'Fair, fast, honest.' : 'Cash outs reopen on their own, nothing to do.' });

  if (!botOnline) {
    embed.addFields({ name: 'Cash outs are paused', value:
      'The bank bot is offline on DonutSMP, so nobody can be paid right now. ' +
      'Your balance is untouched and the button comes back on its own within a minute of the bank returning.\n\n' +
      'Depositing still works, but a payment sent while the bank is offline is not seen live and has to be credited by hand. Better to wait.', inline: false });
  }

  const row = new ActionRowBuilder().addComponents(
    new ButtonBuilder().setCustomId('oc_dep').setLabel('Deposit').setStyle(ButtonStyle.Primary),
    new ButtonBuilder().setCustomId('oc_cash').setLabel(botOnline ? 'Cash out' : 'Cash out (bank offline)')
      .setStyle(ButtonStyle.Success).setDisabled(!botOnline),
    new ButtonBuilder().setCustomId('oc_bal').setLabel('My balance').setStyle(ButtonStyle.Secondary),
    new ButtonBuilder().setCustomId('oc_link').setLabel('Link account').setStyle(ButtonStyle.Secondary),
  );
  return donutPay(embed, { embeds: [embed], components: [row] });
}

// ---------- ecrans de retrait ----------

function amountRows(info) {
  const buttons = QUICK_AMOUNTS.map(a =>
    new ButtonBuilder()
      .setCustomId(`oc_pick_${a}`)
      .setLabel(shortMoney(a))
      .setStyle(ButtonStyle.Primary)
      .setDisabled(a > info.cashoutMax));
  buttons.push(new ButtonBuilder()
    .setCustomId('oc_pick_max')
    .setLabel('MAX')
    .setStyle(ButtonStyle.Success)
    .setDisabled(info.cashoutMax < 1));
  return [
    new ActionRowBuilder().addComponents(buttons),
    new ActionRowBuilder().addComponents(
      new ButtonBuilder().setCustomId('oc_custom').setLabel('Custom amount').setStyle(ButtonStyle.Secondary),
    ),
  ];
}

function balanceEmbed(player, info) {
  const embed = new EmbedBuilder()
    .setColor(COLOR)
    .setTitle('Your casino balance')
    .setDescription(`Linked to **${player}**`)
    .addFields(
      { name: 'In game', value: money(info.inGame), inline: true },
      { name: 'Withdrawable', value: money(info.withdrawable), inline: true },
      // ce qu'il peut encore sortir aujourd'hui, quota du grade et plafond de
      // la maison confondus : c'est la stat qui evite les « pourquoi ca marche
      // pas » quand un retrait est refuse
      {
        name: `Daily limit left (${info.daily.grade})`,
        value: `${money(info.daily.left)} of ${money(info.daily.personalMax)}`
          + (info.daily.blockedByHouse ? '\n-# capped by the house daily limit' : ''),
        inline: true,
      },
    );
  if (info.reserve > 0) {
    embed.addFields({
      name: 'Locked welcome bonus',
      value: `${money(info.reserve)} stays in the casino`,
      inline: false,
    });
  }
  if (info.bank < info.withdrawable) {
    embed.addFields({
      name: 'Bank note',
      value: `The bank can pay ${money(info.payable)} right now. The rest returns to your in-game balance.`,
      inline: false,
    });
  }
  if (info.tx && info.tx.status && info.tx.status !== 'NONE') {
    const amt = Number(info.tx.amount || 0);
    const at = Number(info.tx.at || 0);
    embed.addFields({
      name: 'Last cash out',
      value: `${money(amt)} - **${info.tx.status}**${at ? ` <t:${Math.floor(at / 1000)}:R>` : ''}`,
      inline: false,
    });
  }
  return embed;
}

// Verrous communs a tous les chemins de retrait. Renvoie une chaine (le refus a
// afficher) ou null quand la voie est libre.
function refusal(player, info, ownLock) {
  if (info.blacklisted) return 'Your account is not allowed to cash out. Contact staff.';
  if (!info.botOnline) return 'Cash outs are closed right now: the bank bot is offline on DonutSMP. Try again in a few minutes.';
  const lock = ownLock ? null : cashoutLocks.get(player.toLowerCase());
  if (lock && lock > Date.now()) return 'You already have a cash out going through. Give it a minute.';
  if (info.withdrawable < 1) {
    return info.reserve > 0
      ? 'Nothing to cash out: your 500K welcome bonus cannot leave the casino. Win above it or deposit on DonutSMP first.'
      : 'Nothing to cash out yet. Pay **OutmindCompany** on DonutSMP to fund your account.';
  }
  // Le plafond du jour est applique par le bridge, mais s'il est deja atteint
  // autant le dire ICI : sinon le joueur lance un cashout, voit « failed » sans
  // raison et croit que le casino est casse.
  if (info.dailyLeft <= 0) {
    return info.daily.blockedByHouse
      ? 'The casino reached its daily withdrawal limit. Your balance is untouched, cash outs reopen at midnight (Paris time).'
      : `You reached your daily withdrawal limit (${info.daily.grade}: ${money(info.daily.personalMax)} per day). Your balance is untouched, it resets at midnight (Paris time).`;
  }
  if (!OFFLINE_CASHOUT && !info.playerOnline) {
    return 'Join the casino server first, then hit Cash out again. Withdrawals from Discord alone are coming soon.';
  }
  return null;
}

function confirmScreen(player, amount, info) {
  const embed = new EmbedBuilder()
    .setColor(COLOR)
    .setTitle('Confirm your cash out')
    .setDescription(`**${money(amount)}** will leave your casino balance and be paid to **${player}** on DonutSMP by OutmindCompany.`)
    .addFields(
      { name: 'Withdrawable after', value: money(Math.max(0, info.withdrawable - amount)), inline: true },
    );
  if (amount > info.bank) {
    embed.addFields({
      name: 'Heads up',
      value: `The bank pays ${money(info.bank)} now, the remaining ${money(amount - info.bank)} returns to your in-game balance.`,
      inline: false,
    });
  }
  const row = new ActionRowBuilder().addComponents(
    new ButtonBuilder().setCustomId(`oc_go_${amount}`).setLabel(`Cash out ${shortMoney(amount)}`).setStyle(ButtonStyle.Success),
    new ButtonBuilder().setCustomId('oc_cancel').setLabel('Cancel').setStyle(ButtonStyle.Secondary),
  );
  return { embeds: [embed], components: [row] };
}

// ---------- depot (beta) ----------

// Un depot ne peut PAS partir de Discord : l'argent est sur DonutSMP, seul le
// joueur peut taper /pay. Ce que le bot apporte, c'est la commande exacte prete
// a copier, l'avertissement quand la banque est hors ligne (un paiement recu
// pendant ce temps devient un depot orphelin a attribuer a la main), et la
// confirmation en DM a l'arrivee, la ou le MP en jeu ne passe souvent pas
// puisque le reglage Donut par defaut est "friends only".
function depositScreen(info, amount) {
  const command = amount ? `/pay ${BANK_ACCOUNT} ${Math.floor(amount)}` : `/pay ${BANK_ACCOUNT} <amount>`;
  const botOnline = info ? info.botOnline : casinoSnapshot().botOnline;
  const embed = new EmbedBuilder()
    .setColor(botOnline ? COLOR : COLOR_BAD)
    .setTitle('Deposit to your casino balance')
    .setDescription(
      `Join **${DONUT_HOST}** and run this in game:\n` +
      '```\n' + command + '\n```\n' +
      (info
        ? 'Your casino balance is credited within a minute, and you get a DM here as soon as it lands.'
        : 'Your casino balance is credited within a minute. **Link your account** to get a DM here when it lands.')
    )
    .addFields({ name: 'Bank bot', value: botOnline ? 'Online, ready to receive' : 'Offline', inline: true });
  if (info) embed.addFields({ name: 'Current balance', value: money(info.inGame), inline: true });
  if (!botOnline) {
    embed.addFields({
      name: 'Wait before paying',
      value: 'The bank bot is offline right now. A payment sent while it is down has to be credited by hand. Come back in a few minutes.',
      inline: false,
    });
  }
  const row = new ActionRowBuilder().addComponents(
    new ButtonBuilder().setCustomId('oc_dep_amt')
      .setLabel(amount ? 'Change amount' : 'Build my command').setStyle(ButtonStyle.Secondary),
  );
  // l'auto deposit n'est pas sur le panneau public : c'est une option
  // d'investisseur, elle vit ici, dans le parcours de depot
  if (info && AUTODEPOSIT_ON) {
    row.addComponents(new ButtonBuilder().setCustomId('oc_auto')
      .setLabel('Auto deposit (Investor)').setStyle(ButtonStyle.Secondary));
  }
  return { embeds: [embed], components: [row] };
}

// ---------- depot automatique : autorisation (BETA) ----------

// INVESTOR_MIN est declare en tete du fichier, avec les autres plafonds
// plafond par paiement automatique. Ce n'est pas une limite technique mais un
// garde-fou de beta : un bug de montant coute au maximum ca.
const AUTOPAY_MAX = 10000000;
const AUTOPAY_WORKER = path.join(BOT_DIR, 'autopay-worker.js');
const authFlows = new Map();   // pseudo minuscule -> true tant qu'un flux tourne
const autopayLocks = new Map(); // pseudo minuscule -> true pendant un paiement

// Un seul paiement automatique a la fois sur toute l'installation. Ce n'est pas
// une limite de machine mais de reputation : chaque worker ouvre une session
// Minecraft de plus depuis l'IP du casino, en plus du bot caisse qui y est en
// permanence. Plusieurs comptes connectes en meme temps depuis la meme IP,
// c'est la signature d'alt que DonutSMP cherche. Les demandes font la queue.
const AUTOPAY_PARALLEL = 1;
let autopayRunning = 0;
const autopayQueue = [];

function queueAutopay(job) {
  return new Promise((resolve) => {
    autopayQueue.push({ job, resolve });
    drainAutopay();
  });
}

function drainAutopay() {
  if (autopayRunning >= AUTOPAY_PARALLEL) return;
  const next = autopayQueue.shift();
  if (!next) return;
  autopayRunning++;
  const done = (r) => { autopayRunning--; next.resolve(r); drainAutopay(); };
  next.job().then(done, (e) => done({ ok: false, reason: e.message }));
}

// Lance le worker de paiement dans un process separe et rend son verdict. Un
// crash de la connexion Minecraft ne peut donc pas emporter le bot Discord.
function runAutopay(uuid, player, amount) {
  return new Promise((resolve) => {
    const p = spawn(process.execPath, [AUTOPAY_WORKER, uuid, player, String(amount)],
      { cwd: BOT_DIR, windowsHide: true });
    let out = '';
    p.stdout.on('data', (d) => { out += d; });
    p.stderr.on('data', (d) => console.warn('[autopay]', String(d).trim().slice(0, 300)));
    p.on('error', (e) => resolve({ ok: false, reason: e.message }));
    p.on('close', (code) => {
      const line = out.trim().split('\n').filter(Boolean).pop();
      try { resolve(JSON.parse(line)); }
      catch { resolve({ ok: false, reason: `worker sans reponse (code ${code})` }); }
    });
  });
}

function autoDepositScreen(link, info) {
  const player = link.player;
  if (info.invested < INVESTOR_MIN) {
    return {
      embeds: [new EmbedBuilder()
        .setColor(COLOR)
        .setTitle('Auto deposit is an Investor perk')
        .setDescription(
          `You have invested **${money(info.invested)}** so far. Auto deposit unlocks at **${money(INVESTOR_MIN)}**.\n\n` +
          'Use `/invest <amount>` in game to back the house. Invested money never comes back, that is the point, and it is what buys this level of trust.')],
      components: [],
    };
  }

  if (autodeposit.isAuthorized(link.uuid)) {
    return {
      embeds: [new EmbedBuilder()
        .setColor(COLOR_OK)
        .setTitle('Auto deposit is ready')
        .setDescription(
          `Pick an amount and the casino runs \`/pay ${BANK_ACCOUNT}\` for you on DonutSMP. No need to log in.\n\n` +
          'Your password was never shared, only a Minecraft token. Revoke it here or from your Microsoft security settings at any time.')
        .addFields(
          { name: 'Per payment limit', value: money(AUTOPAY_MAX), inline: true },
          { name: 'Takes about', value: '30 to 60 seconds', inline: true })],
      components: [new ActionRowBuilder().addComponents(
        new ButtonBuilder().setCustomId('oc_auto_pay').setLabel('Deposit now').setStyle(ButtonStyle.Success),
        new ButtonBuilder().setCustomId('oc_auto_off').setLabel('Revoke access').setStyle(ButtonStyle.Danger))],
    };
  }

  // Le consentement dit les vrais risques. Un investisseur qui accepte doit
  // savoir que c'est SON compte qui est exposé, pas celui du casino.
  return {
    embeds: [new EmbedBuilder()
      .setColor(COLOR)
      .setTitle('Auto deposit (beta)')
      .setDescription(
        `Let the casino run \`/pay ${BANK_ACCOUNT}\` for you on DonutSMP, so a deposit takes one tap here instead of logging in.\n\n` +
        '**How it works.** You open a Microsoft page and type an 8 character code. ' +
        'You never give us a password, we only hold a Minecraft token, and you can revoke it here or from your Microsoft security settings whenever you want.')
      .addFields(
        { name: 'Read this before you accept', value:
          'Account sharing is against the Minecraft EULA, and DonutSMP can ban an account played from someone else\'s machine. ' +
          '**The account at risk is yours, not the casino\'s.** Only take this if you are fine with that.', inline: false },
        { name: 'Beta', value: `One payment at a time across the whole casino, ${money(AUTOPAY_MAX)} maximum per payment. The full explanation is in **#beta-test**.`, inline: false })],
    components: [new ActionRowBuilder().addComponents(
      new ButtonBuilder().setCustomId('oc_auto_go').setLabel('I understand, authorize').setStyle(ButtonStyle.Danger),
      new ButtonBuilder().setCustomId('oc_cancel').setLabel('Cancel').setStyle(ButtonStyle.Secondary))],
  };
}

// Le flux device code dure jusqu'a un quart d'heure : on rend la main tout de
// suite et on met a jour le message ephemere au fil des etapes. Sa duree de vie
// (15 min de jeton d'interaction) colle a celle du code Microsoft.
async function runAuthorization(interaction, link) {
  const key = link.player.toLowerCase();
  if (authFlows.get(key)) {
    return interaction.editReply({ content: 'An authorization is already running, finish that one first.', embeds: [], components: [] });
  }
  authFlows.set(key, true);

  // Le message ephemere meurt avec son jeton d'interaction, 15 minutes apres le
  // clic, et une autorisation Microsoft prend souvent plus longtemps que ca : le
  // 2026-08-16, l'autorisation a reussi mais la confirmation n'est jamais
  // arrivee, l'editReply echouait en silence. Le DM est donc le canal qui fait
  // foi, l'ephemere n'est qu'un bonus, et les deux echecs sont journalises.
  const tell = async (payload) => {
    try { await interaction.editReply(payload); }
    catch (e) { console.warn(`Autodeposit : ephemere non mis a jour (${e.message})`); }
  };
  const dm = async (payload) => {
    try {
      const user = await client.users.fetch(interaction.user.id);
      await user.send(payload);
      return true;
    } catch (e) {
      console.warn(`Autodeposit : DM impossible (${e.message})`);
      return false;
    }
  };

  autodeposit.authorize({
    player: link.player,
    uuid: link.uuid,
    expectedName: link.player,
    onCode: (res) => {
      console.log(`Autodeposit : code emis pour ${link.player}`);
      tell({
        embeds: [new EmbedBuilder()
          .setColor(COLOR)
          .setTitle('Authorize the casino')
          .setDescription(
            `Open ${res.verification_uri} and enter this code:\n` +
            '```\n' + res.user_code + '\n```\n' +
            `Sign in with the Microsoft account that owns **${link.player}**. Any other account is refused.`)
          .setFooter({ text: 'The code expires in about 15 minutes.' })],
        components: [],
      });
    },
  }).then(async (profile) => {
    authFlows.delete(key);
    console.log(`Autodeposit : ${link.player} autorise (${profile.id})`);
    const ok = new EmbedBuilder()
      .setColor(COLOR_OK)
      .setTitle('Auto deposit authorized')
      .setDescription(
        `The casino can now deposit for **${profile.name}**. Open **Deposit** then **Auto deposit** to send money in one tap.\n\n` +
        'Revoke it any time from that screen, or from your Microsoft security settings.');
    await dm({ embeds: [ok] });   // le canal qui fait foi, en premier
    await tell({ embeds: [ok], components: [] });
  }).catch(async (e) => {
    authFlows.delete(key);
    console.warn(`Autodeposit : echec pour ${link.player} : ${e.message}`);
    const why = e.mismatch
      ? `That Microsoft account owns **${e.mismatch}**, not **${link.player}**. Nothing was saved. Sign in with the right account.`
      : 'The authorization did not complete. Nothing was saved, you can try again.';
    const bad = new EmbedBuilder().setColor(COLOR_BAD).setTitle('Authorization failed').setDescription(why);
    await dm({ embeds: [bad] });
    await tell({ embeds: [bad], components: [] });
  });

  return interaction.editReply({ content: 'Starting authorization, hold on...', embeds: [], components: [] });
}

// ---------- liaison de compte ----------

// Recherche par SOUS-CHAINE avant creation. Le premier jet cherchait le nom
// exact, et comme Ryan avait deja ses roles GAMBLER et Investor prefixes d'une
// decoration posee a la main, le bot a cree deux doublons nus a cote. Un role
// porte des couleurs, une
// position, des permissions : en fabriquer un second pour le meme grade divise
// tout en deux sans que ca se voie.
//
// A egalite, on garde le role le PLUS HAUT dans la hierarchie : c'est celui que
// Ryan a place et decore, pas un doublon cree par le bot en bas de liste.
async function ensureRole(guild, name, color) {
  const pattern = new RegExp(name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'i');
  // fetch et pas cache : un cache incomplet ferait recreer un role qui existe
  const all = await guild.roles.fetch();
  const found = all
    .filter(r => !r.managed && pattern.test(r.name))
    .sort((a, b) => b.position - a.position)
    .first();
  if (found) return found;
  const role = await guild.roles.create({ name, color, reason: 'Outmind Casino' });
  console.log(`Role ${name} cree.`);
  return role;
}
const ensureVerifiedRole = (guild) => ensureRole(guild, VERIFIED_ROLE, 0x8cb4ff);

// ---------- miroir des grades Minecraft sur Discord ----------

// Le grade in-game vit dans LuckPerms, que le bot ne sait pas lire. Mais il
// decoule du total investi, qu'on connait deja par investors.json : on reproduit
// donc la meme regle ici. Les couleurs sont celles des prefixes en jeu, pour que
// les deux univers se ressemblent : violet du prefixe &5 ɢᴀᴍʙʟᴇʀ, rouge soleil
// du degrade ☀ Investor.
const GRADE_ROLES = {
  Gambler: { name: 'Gambler', color: 0xaa00aa },
  Investor: { name: 'Investor', color: 0xff3c00 },
};

async function syncGrades() {
  const links = Object.entries(state.links);
  if (!links.length) return;
  const guild = await client.guilds.fetch(GUILD_ID);
  const invested = (readJson('investors.json', {}).invested) || {};

  const roles = {};
  for (const g of Object.values(GRADE_ROLES)) roles[g.name] = await ensureRole(guild, g.name, g.color);

  for (const [discordId, link] of links) {
    const isInvestor = (invested[String(link.player).toLowerCase().replace(/^\./, '')] || 0) >= INVESTOR_MIN;
    const want = isInvestor ? 'Investor' : 'Gambler';
    const drop = isInvestor ? 'Gambler' : 'Investor';
    let member;
    try { member = await guild.members.fetch(discordId); }
    catch { continue; } // parti du serveur
    try {
      if (!member.roles.cache.has(roles[want].id)) {
        await member.roles.add(roles[want]);
        console.log(`Grade ${roles[want].name} donne a ${link.player} (${member.user.tag})`);
      }
      if (member.roles.cache.has(roles[drop].id)) {
        await member.roles.remove(roles[drop]);
        console.log(`Grade ${roles[drop].name} retire a ${link.player}`);
      }
    } catch (e) {
      // cas classique : le role du bot est sous celui qu'il tente de poser
      console.warn(`Grade impossible pour ${link.player} : ${e.message}`);
    }
  }
}

// Chemin unique de la verification, appele par la slash command et par le modal
// du panneau. Renvoie le texte a afficher.
async function applyCode(interaction, rawCode) {
  const code = String(rawCode).trim().toUpperCase();
  if (state.usedCodes.includes(code)) {
    return 'This code has already been used. Type `/verify` in game to get a fresh one.';
  }
  const codes = await fetchVerifyCodes();
  const entry = codes.find(c => c.code === code && c.expiresAt > Date.now());
  if (!entry) {
    return 'Invalid or expired code. Type `/verify` in game on the casino server to get one (valid 10 minutes).';
  }
  // un pseudo ne peut etre lie qu'a un seul compte Discord : sinon deux comptes
  // pourraient lancer des retraits concurrents sur le meme portefeuille
  const holder = discordIdOf(entry.player);
  if (holder && holder !== interaction.user.id) {
    delete state.links[holder];
    console.log(`Lien transfere : ${entry.player} quitte ${holder} pour ${interaction.user.id}`);
    // le token Minecraft de l'auto-depot appartient a l'ancien titulaire : il
    // ne doit jamais suivre le lien (un code /verify obtenu par ruse suffirait
    // a vider le portefeuille DonutSMP de la victime)
    try {
      if (entry.uuid && autodeposit.isAuthorized(entry.uuid)) { autodeposit.revoke(entry.uuid); console.log(`Auto-depot revoque pour ${entry.player} (lien transfere)`); }
    } catch (e) { console.warn('revocation auto-depot :', e.message); }
    client.users.fetch(holder)
      .then(u => u.send(`Your casino link to **${entry.player}** was just moved to another Discord account. If this was not you, contact the staff immediately.`))
      .catch(() => {});
  }
  state.links[interaction.user.id] = {
    player: entry.player,
    uuid: entry.uuid,
    discordTag: interaction.user.tag,
    linkedAt: Date.now(),
  };
  state.usedCodes.push(code);
  if (state.usedCodes.length > 200) state.usedCodes = state.usedCodes.slice(-100);
  saveState();
  try {
    const role = await ensureVerifiedRole(interaction.guild);
    await interaction.member.roles.add(role);
  } catch (e) { console.warn('Attribution du role impossible :', e.message); }
  // le grade suit immediatement, sans attendre le cycle de la minute
  syncGrades().catch(e => console.warn('Grades :', e.message));
  console.log(`Lien: ${interaction.user.tag} (${interaction.user.id}) <-> ${entry.player} (${entry.uuid})`);
  // un boost fait avant le /verify se paie maintenant, a l'instant du lien
  if (BOOST_OFFER && loadBoosts().pending[interaction.user.id]) {
    grantBoost(interaction.user.id, interaction.user.tag);
    return `Your Discord is now linked to **${entry.player}**, and your **${shortMoney(BOOST_REWARD)} server boost reward** is on its way. Welcome to the OutMind Casino!`;
  }
  return `Your Discord is now linked to **${entry.player}**. Welcome to the OutMind Casino!`;
}

const NOT_LINKED = 'Link your account first: type `/verify` in game, then hit **Link account** and paste your code.';

// ---------- client ----------

// GuildMessages sert uniquement a savoir qu'un vouch a ete poste dans
// #vouch-us. Il n'est PAS privilegie, donc rien a activer dans le portail
// developpeur. MessageContent, lui, l'est : sans lui message.content arrive
// vide et le controle de longueur du vouch ne peut pas s'appliquer (voir
// VOUCH_MIN_CHARS).
const client = new Client({
  // MessageContent est indispensable au salon admin IA : sans lui, le contenu
  // des messages n'arrive que si le bot est mentionne (vecu, penible). Il doit
  // aussi etre coche dans le portail developpeur (Privileged Gateway Intents).
  intents: [GatewayIntentBits.Guilds, GatewayIntentBits.GuildMembers, GatewayIntentBits.GuildMessages, GatewayIntentBits.MessageContent],
  // le texte des modeles part tel quel en content : seules les mentions de
  // membres passent, jamais @everyone/@here ni les roles
  allowedMentions: { parse: ['users'] },
});

client.once('clientReady', async () => {
  console.log(`Connecte en tant que ${client.user.tag}`);
  const rest = new REST({ version: '10' }).setToken(TOKEN);
  const commands = [
    new SlashCommandBuilder()
      .setName('verify')
      .setDescription('Link your Minecraft casino account')
      .addStringOption(o => o.setName('code').setDescription('The code given by /verify in game').setRequired(true)),
    new SlashCommandBuilder()
      .setName('chain')
      .setDescription('Double it or leave it: the community chain game')
      .addSubcommand(x => x.setName('start').setDescription('Open a chain with your bet')
        .addStringOption(o => o.setName('bet').setDescription('Opening bet, e.g. 10m, 500k, 2500000').setRequired(true)))
      .addSubcommand(x => x.setName('status').setDescription('Current chain state')),
    new SlashCommandBuilder()
      .setName('cashout')
      .setDescription('Cash out your casino balance to DonutSMP'),
    // Meme reserve que le salon : le rapport montre la marge de la maison et
    // les pertes nominatives, il n'a pas a etre tirable par un joueur.
    new SlashCommandBuilder()
      .setName('stats')
      .setDescription('Casino report for a day, today by default (staff only)')
      .setDefaultMemberPermissions(PermissionFlagsBits.ManageGuild)
      .addStringOption(o => o.setName('day').setDescription('Date as YYYY-MM-DD, or "yesterday"').setRequired(false)),
    new SlashCommandBuilder()
      .setName('vouch')
      .setDescription(`Rate the casino, a 5 star vouch pays ${shortMoney(VOUCH_BONUS)}`),
    new SlashCommandBuilder()
      .setName('admin')
      .setDescription('Admin console: vault, players, balances, payouts, blacklist (staff only)')
      .setDefaultMemberPermissions(PermissionFlagsBits.ManageGuild),
    new SlashCommandBuilder()
      .setName('panel')
      .setDescription('Post the cash out panel in this channel (staff only)')
      .setDefaultMemberPermissions(PermissionFlagsBits.ManageGuild),
    // La double barriere du /wipe : ManageGuild pour Discord, et l'allowlist
    // du module qui reverifie l'auteur et le salon avant de purger quoi que ce soit.
    new SlashCommandBuilder()
      .setName('wipe')
      .setDescription('Wipe the Outmind AI channel and reset the conversation (staff only)')
      .setDefaultMemberPermissions(PermissionFlagsBits.ManageGuild),
    // La caisse a distance : lister, ajouter, re-authentifier (device code
    // Microsoft) et basculer les comptes bank du bot mineflayer.
    // Administrator et pas ManageGuild : c'est la caisse elle-meme.
    new SlashCommandBuilder()
      .setName('bank')
      .setDescription('Manage the casino bank accounts (admin only)')
      .setDefaultMemberPermissions(PermissionFlagsBits.Administrator)
      .addSubcommand(x => x.setName('list').setDescription('List bank accounts and their credential state'))
      .addSubcommand(x => x.setName('add').setDescription('Add a bank account and authenticate it')
        .addStringOption(o => o.setName('username').setDescription('Minecraft name of the account').setRequired(true)))
      .addSubcommand(x => x.setName('auth').setDescription('Re-authenticate a bank account (device code)')
        .addStringOption(o => o.setName('username').setDescription('Account name, defaults to the active one').setRequired(false)))
      .addSubcommand(x => x.setName('use').setDescription('Switch the cashier bot to another bank account')
        .addStringOption(o => o.setName('username').setDescription('Account to activate').setRequired(true))),
    // Publique par choix de Ryan : le casino joue la transparence totale, les
    // soldes s'affichent comme les transactions dans #past-transaction. La
    // reponse reste ephemere pour ne pas polluer les salons.
    new SlashCommandBuilder()
      .setName('balance')
      .setDescription('Vault balances of every player, or one player')
      .addStringOption(o => o.setName('player').setDescription('One player, full list if omitted').setRequired(false)),
  ];
  await rest.put(Routes.applicationGuildCommands(APP_ID, GUILD_ID), { body: commands.map(c => c.toJSON()) });
  console.log(`Commandes enregistrees sur le serveur : ${commands.map(c => '/' + c.toJSON().name).join(', ')}.`);
  startWatchers();
  // Offre boost : l'event attrape le boost en direct, le balayage rattrape
  // ceux poses pendant un redemarrage du bot (meme logique que le /vouch).
  if (BOOST_OFFER) {
    const sweepBoosts = async () => {
      try {
        const guild = await aiGuild();
        if (!guild) return;
        const members = await guild.members.fetch();
        for (const m of members.values()) {
          if (!m.premiumSince || m.user.bot) continue;
          const st = grantBoost(m.id, m.user.tag);
          if (st === 'pending') {
            const b = loadBoosts();
            if (!b.pending[m.id + ':dm']) {
              b.pending[m.id + ':dm'] = Date.now(); saveBoosts(b);
              m.send(`Thanks for boosting the server! Your **${shortMoney(BOOST_REWARD)}** reward is reserved: link your account with \`/verify\` in game then /verify here to claim it.`).catch(() => {});
            }
          }
        }
      } catch (e) { console.warn('Sweep boosts :', e.message); }
    };
    setTimeout(sweepBoosts, 20 * 1000);
    setInterval(sweepBoosts, 10 * 60 * 1000);
  }
  // fetchPluginState publie investors.json, que le bridge et le bot mineflayer
  // lisent pour connaitre le grade d'un joueur. Il faut donc l'appeler
  // periodiquement, et pas seulement quand quelqu'un clique sur un bouton.
  const cycle = () => {
    // l'ordre compte : fetchPluginState rafraichit investors.json, dont
    // syncGrades a besoin pour decider des roles
    fetchPluginState()
      .then(() => syncGrades())
      .catch(e => console.warn('state.yml / grades :', e.message));
    ensureVault().catch(e => console.warn('Vault :', e.message));
    // le panneau suit ce cycle et plus le sien : il doit griser le bouton de
    // retrait des que la banque tombe, pas cinq minutes plus tard
    ensurePanel().catch(e => console.warn('Panneau :', e.message));
  };
  cycle();
  setInterval(cycle, VAULT_REFRESH_MS);
  // Un tick toutes les 5 minutes suffit : la fenetre visee est une heure de la
  // journee, pas une minute precise. C'est aussi ce qui rattrape la machine
  // rallumee a midi.
  const stat = () => statsTick().catch(e => {
    if (e.message !== 'channel absent') console.warn('Rapport quotidien :', e.message);
  });
  stat();
  setInterval(stat, STATS_TICK_MS);
  // Balayage des paroles ouvertes des le demarrage : si le bot est tombe
  // pendant une fenetre, l'overwrite est toujours pose cote Discord.
  const sweep = () => sweepVouchWindows().catch(e => console.warn('Vouch, balayage :', e.message));
  sweep();
  setInterval(sweep, VOUCH_SWEEP_MS);
  const showcase = () => ensurePitch().catch(e => console.warn('Vitrine :', e.message));
  showcase();
  setInterval(showcase, PITCH_REFRESH_MS);
  // Salon admin IA : l'agent OpenClaw du VPS. Token lu par l'ecosystem pm2
  // depuis /root/.openclaw/openclaw.json, absent sur le PC = module inactif.
  adminAI.init({ client, token: process.env.OPENCLAW_TOKEN || null });
  const aiGuild = () => client.guilds.fetch(GUILD_ID).catch(() => null);
  const aiAlerts = async () => adminAI.tickAlerts(await aiGuild());
  aiGuild().then(g => { if (g) return adminAI.findOrCreateChannel(g); }).catch(e => console.warn('Admin AI, salon :', e.message));
  setInterval(() => aiAlerts().catch(e => console.warn('Admin AI, alertes :', e.message)), 15000);
  // la plume de l'agent : agent-outbox.jsonl -> messages riches du bot
  agentPost.init({ client, guildId: GUILD_ID });
  setInterval(() => agentPost.tick().catch(e => console.warn('Agent post :', e.message)), 5000);
  // Outmind face aux joueurs : appel modele nu (jamais l'agent a shell),
  // contexte du seul joueur qui parle, action cashout validee par le code
  chain.init({
    client, guildId: GUILD_ID,
    linkOf, withdrawableFor,
    queueOrder: adminLib.queueOrder,
    hasLock: (player) => { const l = cashoutLocks.get(String(player).toLowerCase()); return !!(l && l > Date.now()); },
    lockPlayer: (player) => cashoutLocks.set(String(player).toLowerCase(), Date.now() + ORDER_LOCK_MS),
    resolvePlayer: adminLib.resolvePlayer,
  });
  // 10 s apres le ready : le cache des salons n'est pas garanti a l'instant
  // meme du ready (GUILD_CREATE arrive apres), le tick sert de second filet
  setTimeout(() => chain.refreshBoard().catch(e => console.warn('Chain, board :', e.message)), 10000);
  setInterval(() => chain.tick().catch(e => console.warn('Chain, tick :', e.message)), 60000);
  publicAI.init({
    client, guildId: GUILD_ID,
    apiKey: process.env.ZAI_API_KEY || null,
    deps: {
      linkOf, withdrawableFor, cashoutCore, transferCore, autopayCore,
      houseVault: () => casinoSnapshot(),
      // check investor leger pour le mode mention (pas de fetch du yml serveur)
      isInvestor: (player) => (((readJson('investors.json', {}) || {}).invested || {})[String(player || '').toLowerCase().replace(/^\./, '')] || 0) >= INVESTOR_MIN,
    },
  });
  // accueil pose au demarrage, wipe du salon toutes les 15 minutes (annonce
  // dans l'accueil lui-meme). Pas de wipe immediat au boot : un redeploiement
  // ne doit pas couper une conversation en cours.
  publicAI.ensureWelcome().catch(e => console.warn('Public AI, accueil :', e.message));
  setInterval(() => publicAI.sweep().catch(e => console.warn('Public AI, wipe :', e.message)), publicAI.WIPE_MS);
});

client.on('messageCreate', (message) => {
  onVouchMessage(message).catch(e => console.warn('Vouch, message :', e.stack || e.message));
  adminAI.onMessage(message).catch(e => console.warn('Admin AI, message :', e.stack || e.message));
  publicAI.onMessage(message).catch(e => console.warn('Public AI, message :', e.stack || e.message));
});

client.on('interactionCreate', async (interaction) => {
  try {
    // le `await` n'est PAS decoratif : sans lui la promesse s'echappe du try,
    // le catch ne voit rien, et la moindre erreur asynchrone remonte en
    // 'error' du Client et tue le process. Vecu le 2026-08-16 avec une
    // constante oubliee : chaque clic sur My balance faisait tomber le bot.
    if (interaction.isChatInputCommand()) return await handleCommand(interaction);
    // boutons poses par l'agent Outmind (prefixe oc_agent:) : traces dans
    // agent-clicks.jsonl pour lui, avant le routage normal des boutons
    if (interaction.isButton() && interaction.customId.startsWith('oc_agent:')) return await agentPost.onButton(interaction);
  // jeu chain : Double it or leave it (module chain.js). UN board dans le
  // salon, edite sur place ; toute reponse aux joueurs est ephemere.
  if (interaction.isButton() && interaction.customId === 'oc_chain:open') {
    const modal = new ModalBuilder().setCustomId('oc_chain:bet').setTitle('Open a chain')
      .addComponents(new ActionRowBuilder().addComponents(
        new TextInputBuilder().setCustomId('bet').setLabel('Your opening bet (e.g. 10m, 500k)')
          .setStyle(TextInputStyle.Short).setRequired(true).setMinLength(2).setMaxLength(10)));
    return interaction.showModal(modal).catch(() => {});
  }
  if (interaction.isModalSubmit() && interaction.customId === 'oc_chain:bet') {
    const fake = { user: interaction.user, options: { bet: interaction.fields.getTextInputValue('bet') } };
    await interaction.deferReply({ flags: MessageFlags.Ephemeral });
    await interaction.editReply(await chain.askStart(fake));
    return;
  }
  // confirmation ephemere : Confirm execute le tirage et l'ecran disparait,
  // Cancel le fait juste disparaitre. deferUpdate + deleteReply = supprimer
  // l'ephemere hote du bouton, la seule facon cote API.
  if (interaction.isButton() && interaction.customId.startsWith('oc_chain:go:')) {
    await interaction.deferUpdate();
    const r = await chain.confirm(interaction, interaction.customId.split(':')[2]);
    await interaction.deleteReply().catch(() => {});
    if (r && r.content) await interaction.followUp({ content: r.content, flags: MessageFlags.Ephemeral }).catch(() => {});
    return;
  }
  if (interaction.isButton() && interaction.customId.startsWith('oc_chain:no:')) {
    await interaction.deferUpdate();
    chain.cancel(interaction.customId.split(':')[2], interaction.user.id);
    return interaction.deleteReply().catch(() => {});
  }
  if (interaction.isButton() && interaction.customId === 'oc_chain:double') {
    await interaction.deferReply({ flags: MessageFlags.Ephemeral });
    await interaction.editReply(await chain.askDouble(interaction));
    return;
  }
    // confirmations d'action du guichet ask-outmind (prefixe oc_ask:)
    if (interaction.isButton() && interaction.customId.startsWith('oc_ask:')) return await publicAI.onButton(interaction);
    if (interaction.isButton()) return await handleButton(interaction);
    if (interaction.isModalSubmit()) return await handleModal(interaction);
    if (interaction.isStringSelectMenu() && interaction.customId === 'oc_vouch_stars') {
      return await handleVouchStars(interaction);
    }
  } catch (e) {
    console.error('Erreur interaction :', e.stack || e.message);
    const msg = 'Something went wrong on our side, try again in a minute.';
    try {
      if (interaction.deferred || interaction.replied) await interaction.editReply({ content: msg, embeds: [], components: [] });
      else await interaction.reply({ content: msg, flags: MessageFlags.Ephemeral });
    } catch {}
  }
});

async function handleCommand(interaction) {
  if (interaction.commandName === 'verify') {
    await interaction.deferReply({ flags: MessageFlags.Ephemeral });
    await interaction.editReply(await applyCode(interaction, interaction.options.getString('code')));
    return;
  }
  if (interaction.commandName === 'cashout') {
    await interaction.deferReply({ flags: MessageFlags.Ephemeral });
    await showCashout(interaction);
    return;
  }
  // Rapport a la demande. Ephemere : le rapport public sort tout seul chaque
  // matin, celui-ci sert a regarder un jour precis sans encombrer le salon.
  // Le rendu prend quelques centaines de millisecondes, d'ou le defer.
  if (interaction.commandName === 'stats') {
    if (!isAdmin(interaction)) return interaction.reply({ content: 'Staff only.', flags: MessageFlags.Ephemeral });
    await interaction.deferReply({ flags: MessageFlags.Ephemeral });
    const raw = (interaction.options.getString('day') || '').trim().toLowerCase();
    let day = stats.todayKey();
    if (raw === 'yesterday' || raw === 'hier') day = stats.shiftDay(day, -1);
    else if (/^\d{4}-\d{2}-\d{2}$/.test(raw)) day = raw;
    else if (raw) { await interaction.editReply('Use `YYYY-MM-DD` or `yesterday`.'); return; }
    await interaction.editReply(statsPayload(stats.report(day)));
    return;
  }
  if (interaction.commandName === 'chain') {
    await interaction.deferReply({ flags: MessageFlags.Ephemeral });
    const sub = interaction.options.getSubcommand();
    if (sub === 'start') {
      await interaction.editReply(await chain.askStart(interaction));
      return;
    }
    if (sub === 'status') {
      const r = await chain.status(interaction);
      await interaction.editReply({ content: r.text || undefined, embeds: r.embed ? [r.embed] : [] });
      return;
    }
  }
  if (interaction.commandName === 'vouch') {
    await interaction.deferReply({ flags: MessageFlags.Ephemeral });
    const link = linkOf(interaction.user.id);
    if (!link) return interaction.editReply(NOT_LINKED);
    return interaction.editReply(vouchIntro(link));
  }
  // Console admin. Discord filtre deja par ManageGuild a l'affichage de la
  // commande, mais la permission par defaut se surcharge cote serveur : on
  // reverifie, sinon un salon mal configure suffirait a ouvrir la caisse.
  if (interaction.commandName === 'wipe') {
    return adminAI.wipe(interaction);
  }

  if (interaction.commandName === 'admin') {
    if (!isAdmin(interaction)) {
      return interaction.reply({ content: 'Staff only.', flags: MessageFlags.Ephemeral });
    }
    await interaction.deferReply({ flags: MessageFlags.Ephemeral });
    await interaction.editReply(adminPanel());
    console.log(`Console admin ouverte par ${interaction.user.tag}`);
    return;
  }

  // La caisse a distance. Meme double barriere que /admin : Discord filtre
  // par la permission par defaut, et isAdmin reverifie cote bot.
  if (interaction.commandName === 'bank') {
    if (!isAdmin(interaction)) {
      return interaction.reply({ content: 'Staff only.', flags: MessageFlags.Ephemeral });
    }
    if (!AUTODEPOSIT_ON) return interaction.reply({ content: AUTODEPOSIT_PAUSED, flags: MessageFlags.Ephemeral });
    return handleBank(interaction);
  }

  // /balance est ouverte a tous (choix de transparence), reponse ephemere
  if (interaction.commandName === 'balance') {
    return handleBalance(interaction);
  }
  // Le panneau se pose tout seul dans #cashout : /panel ne sert qu'a le
  // deplacer dans un autre salon, et le message poste devient LE panneau suivi.
  if (interaction.commandName === 'panel') {
    if (!isAdmin(interaction)) return interaction.reply({ content: 'Staff only.', flags: MessageFlags.Ephemeral });
    const snap = casinoSnapshot();
    const msg = await interaction.channel.send(panelMessage(snap.treasury, snap.botOnline));
    state.panelMessageId = msg.id;
    panelSig = `${snap.treasury}|${snap.botOnline}`;
    channelCache.set(PANEL_CHANNELS[0], interaction.channel);
    saveState();
    await interaction.reply({ content: 'Panel posted.', flags: MessageFlags.Ephemeral });
    console.log(`Panneau deplace dans #${interaction.channel.name} par ${interaction.user.tag}`);
  }
}

// ---- /balance : les soldes vault, ouverte a tous ----
// La source est le miroir du bridge (bridge-state.json), la meme que la
// vitrine #vault : c'est le solde en jeu tel que les joueurs le voient.
async function handleBalance(interaction) {
  await interaction.deferReply({ flags: MessageFlags.Ephemeral });
  const snap = casinoSnapshot();
  const query = (interaction.options.getString('player') || '').trim();
  if (query) {
    const key = Object.keys(snap.mirrored).find(n => n.toLowerCase() === query.toLowerCase());
    if (!key) {
      return interaction.editReply({
        embeds: [new EmbedBuilder().setColor(COLOR_BAD).setTitle(query)
          .setDescription('No vault balance on record for this player.')],
      });
    }
    const invested = ((readJson('investors.json', {}) || {}).invested || {})[key.toLowerCase().replace(/^\./, '')] || 0;
    const embed = new EmbedBuilder()
      .setColor(COLOR)
      .setTitle(key)
      .setDescription(`# ${shortMoney(Math.floor(snap.mirrored[key]))}
-# VAULT BALANCE`)
      .setThumbnail(`https://mc-heads.net/avatar/${encodeURIComponent(key)}/100`)
      .addFields(
        { name: 'Rank', value: `**${invested >= INVESTOR_MIN ? 'Investor' : 'Gambler'}**`, inline: true },
        { name: 'Invested', value: `**${invested ? shortMoney(invested) : 'nothing yet'}**`, inline: true },
        { name: 'Status', value: `**${snap.blacklist.includes(key.toLowerCase()) ? 'Blacklisted' : snap.onlinePlayers.includes(key.toLowerCase()) ? 'Online' : 'Offline'}**`, inline: true },
      )
      .setFooter({ text: `${CASINO_HOST} · live from the vault` });
    return interaction.editReply({ embeds: [embed] });
  }
  const rows = Object.entries(snap.mirrored)
    .filter(([, v]) => v !== 0)
    .sort((a, b) => b[1] - a[1]);
  const owed = rows.reduce((t, [, v]) => t + (v > 0 ? v : 0), 0);
  const lines = rows.map(([n, v], i) => {
    const marks = [];
    if (snap.blacklist.includes(n.toLowerCase())) marks.push('BL');
    if (LB_EXCLUDE.has(n.toLowerCase())) marks.push('staff');
    return `\`#${String(i + 1).padStart(2)}\` **${n}** · ${shortMoney(Math.floor(v))}${marks.length ? ' (' + marks.join(', ') + ')' : ''}`;
  });
  // 4096 max pour une description d'embed : on tronque proprement plutot que
  // de laisser Discord rejeter tout le message
  let body = '';
  let shown = 0;
  for (const l of lines) {
    if (body.length + l.length + 1 > 3900) break;
    body += l + '\n';
    shown++;
  }
  if (shown < lines.length) body += `_and ${lines.length - shown} more_`;
  const embed = new EmbedBuilder()
    .setColor(COLOR)
    .setTitle('Vault balances')
    .setDescription(body || '_No balance on record._')
    .setFooter({ text: `${rows.length} players · total owed ${shortMoney(owed)} · treasury ${shortMoney(snap.treasury)}` });
  return interaction.editReply({ embeds: [embed] });
}

// ---- /bank : la caisse a distance ----
// Toutes les reponses sont ephemeres : codes d'authentification et etat de la
// caisse ne regardent que l'admin qui a tape la commande.
async function handleBank(interaction) {
  const sub = interaction.options.getSubcommand();
  await interaction.deferReply({ flags: MessageFlags.Ephemeral });
  try {
    if (sub === 'list') {
      const rows = bank.list();
      const lines = rows.map(a => {
        const flag = a.active ? ' **(ACTIVE)**' : '';
        const auth = a.authed
          ? 'credentials OK' + (a.lastAuthAt ? ', last auth ' + new Date(a.lastAuthAt).toISOString().slice(0, 10) : '')
          : 'NO credentials, run /bank auth';
        return `**${a.name}**${flag} : ${auth}`;
      });
      return interaction.editReply({ content: lines.join('\n') || 'No account registered.' });
    }
    const username = interaction.options.getString('username') || bank.currentEnvUsername();
    if (!username) return interaction.editReply({ content: 'No username given and no active account found.' });
    if (sub === 'use') {
      bank.use(username);
      console.log(`Caisse basculee sur ${username} par ${interaction.user.tag}`);
      return interaction.editReply({ content: `Cashier switched to **${username}**, the bot is restarting (offline about 20 s).
The previous account keeps its own DonutSMP balance, move the funds manually if needed.` });
    }
    // add et auth partagent le meme flux : add enregistre juste le compte en plus
    console.log(`Auth caisse ${username} lancee par ${interaction.user.tag}`);
    const res = await bank.startAuth(username, (code) => {
      interaction.editReply({ content: `Authenticating **${username}**.
Go to **${code.verification_uri}** and enter:
# ${code.user_code}
Then approve with the Microsoft account that owns ${username}. Waiting (up to 15 min)...` }).catch(() => {});
    });
    console.log(`Auth caisse reussie : ${res.name}`);
    return interaction.followUp({ content: `**${res.name}** authenticated, credentials stored.${sub === 'add' ? ' Use /bank use to make it the active cashier.' : ''}`, flags: MessageFlags.Ephemeral });
  } catch (e) {
    console.warn(`/bank ${sub} :`, e.message);
    return interaction.followUp({ content: `Bank: ${e.message}`, flags: MessageFlags.Ephemeral })
      .catch(() => interaction.editReply({ content: `Bank: ${e.message}` }).catch(() => {}));
  }
}

async function handleButton(interaction) {
  const id = interaction.customId;

  // ---- console admin ----
  // Le message est ephemere donc seul son destinataire le voit, mais la
  // permission est reverifiee a chaque clic : un bouton reste cliquable apres
  // qu'un role a ete retire.
  if (id.startsWith('oca_')) {
    if (!isAdmin(interaction)) {
      return interaction.reply({ content: 'Staff only.', flags: MessageFlags.Ephemeral });
    }
    if (id === 'oca_refresh') return interaction.update(adminPanel());
    if (id === 'oca_go') {
      await interaction.deferUpdate();
      return adminExecute(interaction);
    }
    // les modals ne supportent pas deferReply, on les ouvre directement
    if (id === 'oca_look') {
      const modal = new ModalBuilder().setCustomId('oca_m_look').setTitle('Look up a player');
      modal.addComponents(new ActionRowBuilder().addComponents(new TextInputBuilder()
        .setCustomId('player').setLabel('Minecraft name').setStyle(TextInputStyle.Short).setMaxLength(20).setRequired(true)));
      return interaction.showModal(modal);
    }
    if (id === 'oca_bl') {
      const modal = new ModalBuilder().setCustomId('oca_m_bl').setTitle('Blacklist toggle');
      modal.addComponents(
        new ActionRowBuilder().addComponents(new TextInputBuilder()
          .setCustomId('player').setLabel('Minecraft name').setStyle(TextInputStyle.Short).setMaxLength(20).setRequired(true)),
        new ActionRowBuilder().addComponents(new TextInputBuilder()
          .setCustomId('reason').setLabel('Reason (kept in the audit log)').setStyle(TextInputStyle.Short).setMaxLength(120).setRequired(true)));
      return interaction.showModal(modal);
    }
    if (id === 'oca_credit' || id === 'oca_debit' || id === 'oca_pay') {
      return interaction.showModal(adminModal(id.slice(4)));
    }
    return;
  }

  // les modals ne supportent pas deferReply : on les ouvre directement
  if (id === 'oc_link') {
    const modal = new ModalBuilder().setCustomId('oc_m_link').setTitle('Link your account');
    modal.addComponents(new ActionRowBuilder().addComponents(
      new TextInputBuilder()
        .setCustomId('code').setLabel('Your 6 character code from /verify')
        .setStyle(TextInputStyle.Short).setMinLength(6).setMaxLength(6).setRequired(true),
    ));
    return interaction.showModal(modal);
  }
  if (id === 'oc_custom') {
    if (!linkOf(interaction.user.id)) {
      return interaction.reply({ content: NOT_LINKED, flags: MessageFlags.Ephemeral });
    }
    const modal = new ModalBuilder().setCustomId('oc_m_amt').setTitle('Cash out amount');
    modal.addComponents(new ActionRowBuilder().addComponents(
      new TextInputBuilder()
        .setCustomId('amount').setLabel('Amount (300k, 1.5m, 2000000)')
        .setStyle(TextInputStyle.Short).setMaxLength(20).setRequired(true),
    ));
    return interaction.showModal(modal);
  }

  if (id === 'oc_cancel') {
    return interaction.update({ content: 'Cancelled.', embeds: [], components: [] });
  }

  if (id === 'oc_dep_amt') {
    const modal = new ModalBuilder().setCustomId('oc_m_dep').setTitle('Deposit amount');
    modal.addComponents(new ActionRowBuilder().addComponents(
      new TextInputBuilder()
        .setCustomId('amount').setLabel('Amount (300k, 1.5m, 2000000)')
        .setStyle(TextInputStyle.Short).setMaxLength(20).setRequired(true),
    ));
    return interaction.showModal(modal);
  }

  if (id === 'oc_dep') {
    await interaction.deferReply({ flags: MessageFlags.Ephemeral });
    const link = linkOf(interaction.user.id);
    const info = link ? await withdrawableFor(link.player) : null;
    return interaction.editReply(depositScreen(info, null));
  }

  if (!AUTODEPOSIT_ON && (id === 'oc_auto_pay' || id.startsWith('oc_auto_do_') || id === 'oc_auto' || id === 'oc_auto_go' || id === 'oc_auto_off')) {
    return interaction.reply({ content: AUTODEPOSIT_PAUSED, flags: MessageFlags.Ephemeral });
  }
  if (id === 'oc_auto_pay') {
    if (!linkOf(interaction.user.id)) {
      return interaction.reply({ content: NOT_LINKED, flags: MessageFlags.Ephemeral });
    }
    const modal = new ModalBuilder().setCustomId('oc_m_auto').setTitle('Auto deposit amount');
    modal.addComponents(new ActionRowBuilder().addComponents(
      new TextInputBuilder()
        .setCustomId('amount').setLabel(`Amount (max ${shortMoney(AUTOPAY_MAX)})`)
        .setStyle(TextInputStyle.Short).setMaxLength(20).setRequired(true),
    ));
    return interaction.showModal(modal);
  }

  if (id.startsWith('oc_auto_do_')) {
    await interaction.deferUpdate();
    return doAutopay(interaction, Math.floor(Number(id.slice('oc_auto_do_'.length))));
  }

  if (id === 'oc_auto' || id === 'oc_auto_go' || id === 'oc_auto_off') {
    await interaction.deferReply({ flags: MessageFlags.Ephemeral });
    const link = linkOf(interaction.user.id);
    if (!link) return interaction.editReply(NOT_LINKED);
    const info = await withdrawableFor(link.player);

    if (id === 'oc_auto_off') {
      autodeposit.revoke(link.uuid);
      console.log(`Autodeposit : acces revoque pour ${link.player}`);
      return interaction.editReply({
        content: 'Auto deposit access revoked. Nothing of your account is kept. You can also revoke the device from your Microsoft security settings.',
        embeds: [], components: [],
      });
    }
    // le seuil investisseur est reverifie ICI aussi : l'ecran a pu etre ouvert
    // avant un desinvestissement, et surtout un customId se bricole
    if (info.invested < INVESTOR_MIN) return interaction.editReply(autoDepositScreen(link, info));
    if (id === 'oc_auto_go') return runAuthorization(interaction, link);
    return interaction.editReply(autoDepositScreen(link, info));
  }

  if (id === 'oc_cash') {
    await interaction.deferReply({ flags: MessageFlags.Ephemeral });
    return showCashout(interaction);
  }

  if (id === 'oc_bal') {
    await interaction.deferReply({ flags: MessageFlags.Ephemeral });
    const link = linkOf(interaction.user.id);
    if (!link) return interaction.editReply(NOT_LINKED);
    const info = await withdrawableFor(link.player);
    return interaction.editReply({ embeds: [balanceEmbed(link.player, info)] });
  }

  if (id.startsWith('oc_pick_')) {
    await interaction.deferUpdate();
    const link = linkOf(interaction.user.id);
    if (!link) return interaction.editReply({ content: NOT_LINKED, embeds: [], components: [] });
    const info = await withdrawableFor(link.player);
    const stop = refusal(link.player, info);
    if (stop) return interaction.editReply({ content: stop, embeds: [], components: [] });
    const arg = id.slice('oc_pick_'.length);
    const amount = arg === 'max' ? info.cashoutMax : Number(arg);
    if (!(amount >= 1) || amount > info.cashoutMax) {
      return interaction.editReply({
        content: `You can cash out at most ${money(info.withdrawable)} right now.`,
        embeds: [], components: [],
      });
    }
    return interaction.editReply({ content: '', ...confirmScreen(link.player, amount, info) });
  }

  if (id.startsWith('oc_go_')) {
    await interaction.deferUpdate();
    return doCashout(interaction, Number(id.slice('oc_go_'.length)));
  }
}

async function handleModal(interaction) {
  if (interaction.customId.startsWith('oca_m_')) {
    if (!isAdmin(interaction)) {
      return interaction.reply({ content: 'Staff only.', flags: MessageFlags.Ephemeral });
    }
    await interaction.deferReply({ flags: MessageFlags.Ephemeral });
    const kind = interaction.customId.slice(6);
    if (kind === 'look') return interaction.editReply(await playerDossier(interaction.fields.getTextInputValue('player')));
    if (kind === 'bl') return adminConfirmBlacklist(interaction);
    return adminConfirm(interaction, kind);
  }
  if (interaction.customId === 'oc_m_auto') {
    await interaction.deferReply({ flags: MessageFlags.Ephemeral });
    if (!AUTODEPOSIT_ON) return interaction.editReply(AUTODEPOSIT_PAUSED);
    const link = linkOf(interaction.user.id);
    if (!link) return interaction.editReply(NOT_LINKED);
    const amount = parseAmount(interaction.fields.getTextInputValue('amount'));
    if (amount < 1) return interaction.editReply('Invalid amount. Try `300k`, `1.5m` or `2000000`.');
    if (amount > AUTOPAY_MAX) {
      return interaction.editReply(`Auto deposit is capped at ${money(AUTOPAY_MAX)} per payment while in beta.`);
    }
    if (!autodeposit.isAuthorized(link.uuid)) {
      return interaction.editReply('Auto deposit is not authorized on your account any more. Open it again from the Deposit screen.');
    }
    const embed = new EmbedBuilder()
      .setColor(COLOR)
      .setTitle('Confirm your deposit')
      .setDescription(
        `The casino will connect as **${link.player}** on ${DONUT_HOST} and run \`/pay ${BANK_ACCOUNT} ${amount}\`.\n\n` +
        'Make sure you are **not logged in on DonutSMP right now**, the server refuses two sessions of the same account.');
    return interaction.editReply({
      embeds: [embed],
      components: [new ActionRowBuilder().addComponents(
        new ButtonBuilder().setCustomId(`oc_auto_do_${amount}`).setLabel(`Deposit ${shortMoney(amount)}`).setStyle(ButtonStyle.Success),
        new ButtonBuilder().setCustomId('oc_cancel').setLabel('Cancel').setStyle(ButtonStyle.Secondary))],
    });
  }
  if (interaction.customId === 'oc_m_dep') {
    await interaction.deferReply({ flags: MessageFlags.Ephemeral });
    const amount = parseAmount(interaction.fields.getTextInputValue('amount'));
    if (amount < 1) return interaction.editReply('Invalid amount. Try `300k`, `1.5m` or `2000000`.');
    const link = linkOf(interaction.user.id);
    const info = link ? await withdrawableFor(link.player) : null;
    return interaction.editReply(depositScreen(info, amount));
  }
  if (interaction.customId === 'oc_m_link') {
    await interaction.deferReply({ flags: MessageFlags.Ephemeral });
    await interaction.editReply(await applyCode(interaction, interaction.fields.getTextInputValue('code')));
    return;
  }
  if (interaction.customId === 'oc_m_amt') {
    await interaction.deferReply({ flags: MessageFlags.Ephemeral });
    const link = linkOf(interaction.user.id);
    if (!link) return interaction.editReply(NOT_LINKED);
    const amount = parseAmount(interaction.fields.getTextInputValue('amount'));
    if (amount < 1) return interaction.editReply('Invalid amount. Try `300k`, `1.5m` or `2000000`.');
    const info = await withdrawableFor(link.player);
    const stop = refusal(link.player, info);
    if (stop) return interaction.editReply(stop);
    if (amount > info.cashoutMax) {
      return interaction.editReply(`You can cash out at most ${money(info.cashoutMax)} right now.`);
    }
    return interaction.editReply(confirmScreen(link.player, amount, info));
  }
}

// ---------- ecran de retrait et execution ----------

async function showCashout(interaction) {
  const link = linkOf(interaction.user.id);
  if (!link) return interaction.editReply(NOT_LINKED);
  const info = await withdrawableFor(link.player);
  const stop = refusal(link.player, info);
  if (stop) return interaction.editReply({ embeds: [balanceEmbed(link.player, info)], content: stop, components: [] });
  return interaction.editReply({
    embeds: [balanceEmbed(link.player, info)],
    components: amountRows(info),
  });
}

// Tout est revalide ICI, au dernier moment : entre l'affichage du menu et le
// clic sur Confirm, le joueur a pu miser, perdre, ou lancer un retrait en jeu.
// Le noyau, sans interaction : utilise par le flux boutons ci-dessous ET par
// le pont public ask-outmind (public-ai.js). Retourne { ok, player } ou
// { ok: false, text } avec la raison affichable. Toutes les barrieres du
// circuit standard s'appliquent, quelle que soit la porte d'entree.
async function cashoutCore(discordId, amount, tag) {
  const link = linkOf(discordId);
  if (!link) return { ok: false, text: NOT_LINKED };
  const player = link.player;
  const key = player.toLowerCase();

  // verrou pose de facon synchrone AVANT le premier await : deux clics rapides
  // passaient tous les deux le controle avant que le premier ne verrouille
  const held = cashoutLocks.get(key);
  if (held && held > Date.now()) return { ok: false, text: 'You already have a cash out going through. Give it a minute.' };
  cashoutLocks.set(key, Date.now() + LOCK_MS);

  const info = await withdrawableFor(player);
  const stop = refusal(player, info, true);
  if (stop) { cashoutLocks.delete(key); return { ok: false, text: stop }; }
  if (!(amount >= 1) || amount > info.cashoutMax) {
    cashoutLocks.delete(key);
    return { ok: false, text: `Your balance changed. You can cash out at most ${money(info.cashoutMax)} now.` };
  }

  // verrou pose AVANT l'appel : le miroir des soldes ne se met a jour qu'au
  // prochain tick du bridge, sans ca un double clic passerait deux fois
  cashoutLocks.set(key, Date.now() + LOCK_MS);
  const sentAt = Date.now();
  try {
    await sendCommandApi(`outmind cashout ${player} ${Math.floor(amount)}`);
  } catch (e) {
    cashoutLocks.delete(key);
    console.error(`Cashout ${player} ${amount} : ${e.message}`);
    return { ok: false, text: 'The casino server did not answer. Nothing was taken from your balance, try again in a minute.' };
  }

  // Accuse de reception applicatif (2026-08-19) : le 204 du panel ne garantit
  // PAS l'execution de la commande console (vecu : cashout a player 20M avale
  // entre le panel et la console MC, aucune trace cote serveur, zero erreur).
  // La preuve d'execution est la ligne que le bridge ecrit dans la file payout
  // APRES que le plugin a debite le vault. Pas de ligne = rien n'a ete debite.
  const ack = await waitForPayout(player, sentAt - 5000, 75000);
  if (!ack) {
    cashoutLocks.delete(key);
    console.error(`Cashout ${player} ${amount} : commande acceptee par le panel mais aucun accuse (ni paye ni refuse) en 75 s`);
    return { ok: false, text: 'The cashout order got lost before reaching the casino server: nothing was taken from your balance. Try again in a minute.' };
  }
  if (ack.status === 'refused') {
    cashoutLocks.delete(key);
    const most = `$${Math.floor(ack.allowed || 0).toLocaleString('en-US')}`;
    const texts = {
      over_allowed: `The game server says you can cash out at most ${most} right now. Your in-game balance moves in real time while you play, so stop the spins or ask a smaller amount.`,
      bot_offline: 'Cashouts are closed right now: the payout bot is offline on DonutSMP. Try again in a few minutes.',
      nothing_allowed: 'There is nothing you can cash out right now (the 500K welcome reserve never leaves the casino).',
      nothing_asked: 'Nothing to cash out with that amount.',
      invalid_amount: 'The game server could not read that amount.',
      withdraw_failed: 'The withdrawal failed on the game server, try again in a minute.',
    };
    console.log(`Cashout Discord refuse : ${tag} -> ${player} ${amount} (${ack.reason}, allowed ${ack.allowed})`);
    return { ok: false, text: `${texts[ack.reason] || 'The game server refused this cashout.'} Nothing was taken from your balance.` };
  }

  console.log(`Cashout Discord : ${tag} -> ${player} ${amount}`);
  return { ok: true, player };
}

// attend l'accuse du cashout : la ligne payee dans donut-payouts.jsonl OU le
// refus du plugin dans cashout-refusals.jsonl (les deux ecrits par le bridge).
// Match par joueur + horodatage, PAS par montant : le bridge peut plafonner et
// payer moins que demande. Poll 3 s, latence normale ~20 s, budget 75 s sous
// le LOCK_MS de 90 s pour que le verrou couvre toute l'attente.
async function waitForPayout(player, notBefore, budgetMs) {
  const files = [
    [path.join(BOT_DIR, 'donut-payouts.jsonl'), 'paid'],
    [path.join(BOT_DIR, 'cashout-refusals.jsonl'), 'refused'],
  ];
  const who = player.toLowerCase();
  const deadline = Date.now() + budgetMs;
  while (Date.now() < deadline) {
    for (const [file, status] of files) {
      let lines = [];
      try { lines = fs.readFileSync(file, 'utf8').split('\n').filter(l => l.trim()).slice(-30); } catch {}
      for (const l of lines) {
        try {
          const e = JSON.parse(l);
          if (String(e.player || '').toLowerCase() === who && (e.at || 0) >= notBefore) return { status, ...e };
        } catch {}
      }
    }
    await new Promise(r => setTimeout(r, 3000));
  }
  return null;
}

// Transfert de balance joueur -> joueur, demande en langage naturel dans
// #ask-outmind. Le pont public appelle ce noyau, jamais le grand livre.
// Plafonne au RETIRABLE, pas a la balance : sinon la reserve de bienvenue de
// 500K se blanchit par une mule (transfert vers un alt, cashout de l'alt).
// L'execution est un ordre `transfer` atomique cote bridge (conservation
// stricte), la cible est resolue en nom canonique (adminLib.resolvePlayer)
// pour ne jamais creer une seconde entree de casse differente.
async function transferCore(discordId, toRaw, amount, tag) {
  const link = linkOf(discordId);
  if (!link) return { ok: false, text: NOT_LINKED };
  const sender = link.player;

  const info = await withdrawableFor(sender);
  if (info.blacklisted) return { ok: false, text: 'Your account is not allowed to move money. Contact staff.' };
  const lock = cashoutLocks.get(sender.toLowerCase());
  if (lock && lock > Date.now()) return { ok: false, text: 'You have a cash out going through. Give it a minute, then transfer.' };
  if (!(amount >= 1)) return { ok: false, text: 'Amount must be at least $1.' };
  if (amount > info.withdrawable) {
    return { ok: false, text: `You can transfer at most ${money(info.withdrawable)} (your balance minus the ${money(WELCOME_BONUS)} welcome reserve, which never leaves your account).` };
  }
  // les transferts comptent dans le plafond journalier du grade : sinon un
  // joueur contourne son quota en repartissant sur des comptes secondaires
  const day = new Date().toLocaleDateString('en-CA', { timeZone: 'Europe/Paris' });
  if (!state.transfers || state.transfers.day !== day) state.transfers = { day, players: {} };
  const usedT = state.transfers.players[sender.toLowerCase()] || 0;
  const capT = info.daily && info.daily.personalMax ? info.daily.personalMax : PLAYER_MAX_GAMBLER;
  if (amount > capT - usedT) {
    return { ok: false, text: `Transfers count against your daily withdrawal limit (${money(capT)} per day). You can still transfer ${money(Math.max(0, capT - usedT))} today.` };
  }

  const target = adminLib.resolvePlayer(toRaw);
  if (!target) return { ok: false, text: `I do not know any player named **${String(toRaw).slice(0, 30)}** at this casino. They need to have played or deposited here at least once.` };
  if (target.toLowerCase() === sender.toLowerCase()) return { ok: false, text: 'You cannot transfer to yourself.' };
  const snap = casinoSnapshot();
  if (snap.blacklist.includes(target.toLowerCase())) return { ok: false, text: 'That account cannot receive money.' };

  adminLib.queueOrder({
    kind: 'transfer', player: sender, to: target, amount: Math.floor(amount),
    reason: 'player transfer via ask-outmind', by: tag, byId: discordId,
  });
  state.transfers.players[sender.toLowerCase()] = usedT + Math.floor(amount);
  saveState();
  // l'ordre part au prochain tick du bridge : aucun cashout sur le meme solde d'ici la
  cashoutLocks.set(sender.toLowerCase(), Date.now() + ORDER_LOCK_MS);
  console.log(`Transfer ask-outmind : ${tag} : ${sender} -> ${target} ${amount}`);
  return { ok: true, player: sender, to: target };
}

async function doCashout(interaction, amount) {
  const r = await cashoutCore(interaction.user.id, amount, interaction.user.tag);
  if (!r.ok) return interaction.editReply({ content: r.text, embeds: [], components: [] });
  const embed = new EmbedBuilder()
    .setColor(COLOR_OK)
    .setTitle('Cash out requested')
    .setDescription(
      `**${money(amount)}** is on its way to **${r.player}** on DonutSMP, paid by OutmindCompany.\n\n` +
      'You will get a DM here as soon as it is paid. Anything above the bank balance comes back to your in-game balance.'
    );
  return interaction.editReply({ content: '', embeds: [embed], components: [] });
}

// ---------- depot automatique : le paiement ----------

// Le noyau, sans interaction : utilise par le flux boutons ET par le pont
// public ask-outmind. Tout est revalide ici, au dernier moment, exactement
// comme pour le cashout : l'autorisation a pu etre revoquee entre temps.
// onProgress est appele juste avant le paiement (30 a 60 s), pour informer.
async function autopayCore(discordId, amount, tag, onProgress) {
  if (!AUTODEPOSIT_ON) return { ok: false, text: AUTODEPOSIT_PAUSED };
  const link = linkOf(discordId);
  if (!link) return { ok: false, text: NOT_LINKED };
  const key = link.player.toLowerCase();

  if (autopayLocks.get(key)) {
    return { ok: false, text: 'A deposit is already running for your account.' };
  }
  if (!(amount >= 1) || amount > AUTOPAY_MAX) {
    return { ok: false, text: `Amount must be between $1 and ${money(AUTOPAY_MAX)}.` };
  }
  if (!autodeposit.isAuthorized(link.uuid)) {
    return { ok: false, text: 'Auto deposit is not authorized on your account. Open **Deposit** in #cashout, then **Auto deposit**, and authorize first.' };
  }
  // Verrou capital : un paiement recu pendant que le bot banque est hors ligne
  // n'est pas vu en direct, il ressort a la reconciliation comme depot ORPHELIN
  // a attribuer a la main. Mieux vaut refuser le depart.
  if (!casinoSnapshot().botOnline) {
    return { ok: false, text: 'Deposits are closed right now: the bank bot is offline on DonutSMP. Try again in a few minutes.' };
  }

  autopayLocks.set(key, true);
  try {
    if (onProgress) {
      await onProgress({ waiting: autopayRunning >= AUTOPAY_PARALLEL, player: link.player });
    }
    const res = await queueAutopay(() => runAutopay(link.uuid, link.player, amount));
    console.log(`Autopay ${link.player} ${amount} -> ${JSON.stringify(res)} (${tag})`);

    if (res.ok) {
      return {
        ok: true, player: link.player,
        text: `**${money(amount)}** paid to ${BANK_ACCOUNT} from your DonutSMP account. Your casino balance is credited within a minute, and you will get a DM.`,
      };
    }
    // messages distincts : « ca n'a pas marche » sans raison est ce qui fait
    // perdre le plus de temps a tout le monde
    const why = res.reason === 'reauth'
      ? 'Your authorization expired. Open Deposit, then Auto deposit, and authorize again.'
      : res.reason === 'kick'
        ? `DonutSMP refused the connection: ${res.line || 'no reason given'}`
        : res.reason === 'refus du serveur'
          ? `DonutSMP refused the payment: ${res.line || 'not enough money?'}`
          : `The payment did not go through (${res.reason}). Nothing was taken from you, you can try again.`;
    return { ok: false, text: why };
  } finally {
    // le finally repare aussi un vieux defaut : un throw de queueAutopay
    // laissait le verrou du joueur pose pour toujours
    autopayLocks.delete(key);
  }
}

async function doAutopay(interaction, amount) {
  const r = await autopayCore(interaction.user.id, amount, interaction.user.tag, async ({ waiting, player }) => {
    await interaction.editReply({
      embeds: [new EmbedBuilder().setColor(COLOR).setTitle(waiting ? 'Queued...' : 'Depositing...')
        .setDescription(waiting
          ? `Another deposit is going through. Yours starts right after, ${money(amount)} as **${player}**.`
          : `Connecting as **${player}** and paying ${money(amount)}. This takes 30 to 60 seconds.`)],
      components: [],
    });
  });
  return interaction.editReply({
    embeds: [new EmbedBuilder()
      .setColor(r.ok ? COLOR_OK : COLOR_BAD)
      .setTitle(r.ok ? 'Deposit sent' : 'Deposit failed')
      .setDescription(r.text)],
    components: [],
  });
}

// ---------- suivi des journaux du casino ----------

function readLines(file) {
  try { return fs.readFileSync(path.join(BOT_DIR, file), 'utf8').split('\n').filter(l => l.trim()); }
  catch { return null; }
}

// Suit un journal .jsonl a la trace. handler recoit le paquet de nouvelles
// lignes d'un coup (utile pour grouper les rafales) et doit throw s'il n'a pas
// pu les traiter : l'offset n'avance alors pas et le paquet est rejoue au tick
// suivant, plutot que perdu.
async function tail(offsetKey, file, handler) {
  const lines = readLines(file);
  if (lines == null) return;
  // fichier tronque ou remplace : on se recale sans rejouer
  if (lines.length < state[offsetKey]) { state[offsetKey] = lines.length; saveState(); return; }
  if (lines.length === state[offsetKey]) return;

  const fresh = [];
  for (const line of lines.slice(state[offsetKey])) {
    try { fresh.push(JSON.parse(line)); } catch { /* ligne partielle en cours d'ecriture */ }
  }
  await handler(fresh);
  state[offsetKey] = lines.length;
  saveState();
}

// payout-results.jsonl est ecrit par le bot mineflayer quand le /pay DonutSMP
// est confirme (ou a echoue) : on previent l'auteur du retrait en DM, comme le
// plugin le previent en jeu.
async function onPayouts(rows) {
  for (const o of rows) {
    cashoutLocks.delete(String(o.player || '').toLowerCase());
    const discordId = discordIdOf(o.player);
    if (!discordId) continue;
    const paid = o.status === 'PAID';
    const embed = new EmbedBuilder()
      .setColor(paid ? COLOR_OK : COLOR_BAD)
      .setTitle(paid ? 'Cash out paid' : 'Cash out failed')
      .setDescription(paid
        ? `**${money(o.amount)}** has been paid to **${o.player}** on DonutSMP by OutmindCompany.`
        : `Your cash out of **${money(o.amount)}** could not be paid. The money is back on your in-game balance.`);
    try {
      const user = await client.users.fetch(discordId);
      await user.send({ embeds: [embed] });
      console.log(`DM ${o.status} envoye a ${o.player} (${discordId})`);
    } catch (e) {
      // DM ferme : le joueur est deja prevenu en jeu par le plugin
      console.warn(`DM impossible pour ${o.player} : ${e.message}`);
    }
  }
}

// ---------- journal public des mouvements ----------

const channelCache = new Map();
const channelWarned = new Set();
async function findChannel(names) {
  const key = names[0];
  if (channelCache.has(key)) return channelCache.get(key);
  const guild = await client.guilds.fetch(GUILD_ID);
  const channels = await guild.channels.fetch();
  const found = channels.find(c => c && names.includes(c.name) && c.isTextBased());
  if (found) { channelCache.set(key, found); channelWarned.delete(key); }
  else if (!channelWarned.has(key)) {
    console.warn(`Channel #${key} introuvable.`);
    channelWarned.add(key);
  }
  return found || null;
}
const txChannel = () => findChannel(TX_CHANNELS);

const txLine = (o) => {
  const when = Math.floor(new Date(o.at).getTime() / 1000);
  if (o.type === 'vouch-bonus') return `<t:${when}:t> **${o.player}** got the vouch bonus \`+${money(o.amount)}\``;
  if (o.type === 'boost-bonus') return `<t:${when}:t> **${o.player}** got the server boost reward \`+${money(o.amount)}\``;
  if (o.type === 'transfer') return `<t:${when}:t> **${o.player}** sent \`${money(o.amount)}\` to **${o.to}**`;
  const sign = o.type === 'depot' ? '+' : '-';
  const what = o.type === 'depot' ? 'deposited' : 'cashed out';
  return `<t:${when}:t> **${o.player}** ${what} \`${sign}${money(o.amount)}\``;
};

// transactions.jsonl est le grand livre de la banque : depot = paiement recu
// sur DonutSMP, retrait = cashout effectivement paye. Les mises de casino n'y
// sont pas, elles vivent dans casino-deltas.jsonl, et n'ont rien a faire dans
// un journal de banque.
async function onTransactions(rows) {
  // depot/retrait viennent du bot DonutSMP ; vouch-bonus et transfer sont
  // ecrits par le bridge. Tous publics, tous dans #past-transaction.
  const moves = rows.filter(o => ['depot', 'retrait', 'vouch-bonus', 'boost-bonus', 'transfer'].includes(o.type));
  if (!moves.length) return;
  const channel = await txChannel();
  if (!channel) throw new Error('channel absent'); // offset non avance, rejeu au prochain tick

  // rattrapage apres un arret long : un seul message plutot qu'une rafale
  if (moves.length > TX_BURST_MAX) {
    const embed = new EmbedBuilder()
      .setColor(COLOR)
      .setTitle(`${moves.length} transactions`)
      .setDescription(moves.map(txLine).join('\n').slice(0, 4000));
    await channel.send({ embeds: [embed] });
    console.log(`Journal : ${moves.length} transactions groupees`);
    return;
  }

  for (const o of moves) {
    let embed;
    if (o.type === 'vouch-bonus') {
      embed = new EmbedBuilder()
        .setColor(COLOR)
        .setAuthor({ name: o.player, iconURL: `https://mc-heads.net/avatar/${encodeURIComponent(o.player)}/64` })
        .setTitle('Vouch bonus')
        .setDescription(`**${o.player}** rated us five stars and got **${money(o.amount)}** on their casino balance.`)
        .addFields({ name: 'Casino balance', value: money(o.balance || 0), inline: true })
        .setTimestamp(new Date(o.at));
    } else if (o.type === 'boost-bonus') {
      embed = new EmbedBuilder()
        .setColor(0xF47FFF)
        .setAuthor({ name: o.player, iconURL: `https://mc-heads.net/avatar/${encodeURIComponent(o.player)}/64` })
        .setTitle('Server Boost Bonus')
        .setDescription(`**${o.player}** boosted the Discord and got **${money(o.amount)}** on their casino balance. 1 boost = ${money(BOOST_REWARD)}, limited offer.`)
        .addFields({ name: 'Casino balance', value: money(o.balance || 0), inline: true })
        .setTimestamp(new Date(o.at));
    } else if (o.type === 'transfer') {
      embed = new EmbedBuilder()
        .setColor(COLOR)
        .setAuthor({ name: o.player, iconURL: `https://mc-heads.net/avatar/${encodeURIComponent(o.player)}/64` })
        .setTitle('Transfer')
        .setDescription(`**${o.player}** sent **${money(o.amount)}** to **${o.to}**.`)
        .setTimestamp(new Date(o.at));
    } else {
      const deposit = o.type === 'depot';
      embed = new EmbedBuilder()
        .setColor(deposit ? COLOR_OK : COLOR)
        .setAuthor({ name: o.player, iconURL: `https://mc-heads.net/avatar/${encodeURIComponent(o.player)}/64` })
        .setTitle(deposit ? 'Deposit' : 'Cash out')
        .setDescription(deposit
          ? `**${o.player}** deposited **${money(o.amount)}** from DonutSMP.`
          : `**${money(o.amount)}** paid out to **${o.player}** on DonutSMP.`)
        .addFields({ name: 'Casino balance', value: money(o.balance || 0), inline: true })
        .setTimestamp(new Date(o.at));
      if (o.note) embed.addFields({ name: 'Note', value: String(o.note).slice(0, 200), inline: false });
    }
    const payload = { embeds: [embed] };
    if (o.type === 'depot' || o.type === 'retrait') donutPay(embed, payload);
    await channel.send(payload);
    console.log(`Journal : ${o.type} ${o.player} ${o.amount}${o.to ? ` -> ${o.to}` : ''}`);
  }
  await notifyDeposits(moves);
}

// Confirmation en DM a l'arrivee d'un depot. C'est la vraie valeur du depot
// cote Discord : en jeu le MP du bot n'arrive souvent pas, le reglage Donut par
// defaut etant "friends only". Envoye APRES le journal, pas avant : si le
// journal echoue, le paquet entier est rejoue et un DM parti trop tot ferait
// doublon. Les retraits ont deja leur DM par onPayouts.
async function notifyDeposits(moves) {
  for (const o of moves.filter(m => m.type === 'depot')) {
    const discordId = discordIdOf(o.player);
    if (!discordId) continue;
    const embed = new EmbedBuilder()
      .setColor(COLOR_OK)
      .setTitle('Deposit received')
      .setDescription(`**${money(o.amount)}** landed on your casino balance. Good luck at the tables.`)
      .addFields({ name: 'Casino balance', value: money(o.balance || 0), inline: true });
    try {
      const user = await client.users.fetch(discordId);
      await user.send({ embeds: [embed] });
      console.log(`DM depot envoye a ${o.player} (${discordId})`);
    } catch (e) {
      console.warn(`DM depot impossible pour ${o.player} : ${e.message}`);
    }
  }
}

// ---------- panneau pose et tenu a jour tout seul ----------

// Le panneau vit dans #cashout sans que personne n'ait a taper quoi que ce
// soit. On reutilise TOUJOURS le meme message (id dans links.json, avec un
// repli sur la recherche dans l'historique du salon si l'id est perdu), sinon
// chaque redemarrage du bot empilerait un panneau de plus.
// La signature porte la caisse ET l'etat de la banque : sans le second, la
// bascule en ligne / hors ligne ne declencherait aucune reedition et le bouton
// resterait vert. Ce panneau est donc rafraichi sur le cycle d'une minute, pas
// toutes les cinq : le bot banque est declare mort au bout de deux minutes sans
// battement, un panneau cinq minutes en retard afficherait le contraire de la
// verite pendant tout ce temps.
let panelSig = null;
// force la mise a jour du panneau au prochain boot : remplace le logo une
// fois, puis reprend le cycle normal de signature
let panelForce = true;
const FORCE_SIG = 'b1ed0b0f'; // logo v3 (dimension), force l'edit une fois
async function ensurePanel() {
  const channel = await findChannel(PANEL_CHANNELS);
  if (!channel) return;
  const snap = casinoSnapshot();
  const sig = `${snap.treasury}|${snap.botOnline}`;
  const payload = () => panelMessage(snap.treasury, snap.botOnline);

  if (state.panelMessageId) {
    try {
      const msg = await channel.messages.fetch(state.panelMessageId);
      if (sig !== panelSig || panelForce) { await msg.edit(payload()); panelSig = sig; panelForce = false; }
      return;
    } catch { state.panelMessageId = null; } // message supprime a la main
  }

  const recent = await channel.messages.fetch({ limit: 50 });
  const mine = recent.find(m => m.author.id === client.user.id
    && m.embeds[0] && String(m.embeds[0].title || '').includes('OUTMIND CASINO'));
  const msg = mine ? (await mine.edit(payload())) : (await channel.send(payload()));
  state.panelMessageId = msg.id;
  panelSig = sig;
  saveState();
  console.log(`Panneau ${mine ? 'repris' : 'pose'} dans #${channel.name} (${msg.id})`);
}

// ---------- vitrine du vault ----------

// Tout vient des fichiers locaux du casino, donc aucun appel reseau : la caisse,
// le battement de coeur du bot banque, et les soldes du miroir tenu par le
// bridge. Le classement suit la meme regle que l'hologramme du spawn, les
// blacklistes en sont exclus.
// AUCUN bloc de code ici, et c'est le point important : un bloc de code a une
// chasse fixe et ne se replie pas. Sur Discord mobile la zone utile fait une
// vingtaine de colonnes, donc un panneau ASCII de 34 casse chaque ligne en deux
// et tout l'alignement s'effondre (vecu le 2026-08-16, captures a l'appui).
// Les champs d'embed, eux, se reagencent seuls : trois de front sur ordinateur,
// empiles sur telephone. On les laisse faire la mise en page.
// rang en code inline : ca donne une puce nette et de largeur constante sans
// recourir a un emoji, que Ryan ne veut pas dans ce qu'on produit
const rank = (i) => `\`#${i + 1}\``;

function vaultMessage() {
  const snap = casinoSnapshot();
  const owed = Object.values(snap.balances).reduce((s, v) => s + (v > 0 ? v : 0), 0);
  const top = Object.entries(snap.mirrored)
    .filter(([name, bal]) => bal > 0 && !snap.blacklist.includes(name.toLowerCase()) && !LB_EXCLUDE.has(name.toLowerCase()))
    .sort((a, b) => b[1] - a[1])
    .slice(0, VAULT_TOP);

  // une ligne par joueur, qui reste lisible meme repliee sur un ecran etroit.
  // Le classement n'affiche que les soldes positifs : promettre « Top 10 » quand
  // quatre joueurs seulement ont de l'argent donne l'impression d'un bug.
  const board = top.length
    ? top.map(([name, bal], i) => `${rank(i)}  **${name}**  ·  ${shortMoney(Math.floor(bal))}`).join('\n')
    : '_Nobody on the board yet._';

  // total des retraits payes depuis minuit, heure de Paris : la preuve vivante
  // que la caisse paie vraiment, plus parlante qu'un compteur de joueurs
  let paidToday = 0;
  try {
    const midnight = new Date(new Date().toLocaleString('en-US', { timeZone: 'Europe/Paris' }));
    midnight.setHours(0, 0, 0, 0);
    for (const line of readLines('transactions.jsonl') || []) {
      let o; try { o = JSON.parse(line); } catch { continue; }
      if (o.type === 'retrait' && new Date(o.at) >= midnight) paidToday += o.amount;
    }
  } catch { /* le champ affichera 0, ce n'est pas bloquant */ }

  const embed = new EmbedBuilder()
    .setColor(snap.botOnline ? COLOR : COLOR_BAD)
    .setTitle('⛁  ᴛʜᴇ  ᴏᴜᴛᴍɪɴᴅ  ᴠᴀᴜʟᴛ  ⛁')
    .setDescription(
      `# ${shortMoney(snap.treasury)}\n-# VAULT FORTUNE  ·  BACKED 1:1 ON DONUTSMP\n\n` +
      `Come play at **${CASINO_HOST}**`)
    .addFields(
      // AUCUN bloc de code dans ces tuiles : un bloc prend toute la largeur et
      // casse la mise cote a cote sur telephone (vecu). En texte simple,
      // Discord aligne bien les champs inline sur une ligne.
      { name: 'Players online', value: `**${snap.onlinePlayers.length}**`, inline: true },
      { name: 'Bank', value: snap.botOnline ? '**Online**' : '**Offline**', inline: true },
      { name: 'Backed', value: owed > 0 ? `**${(snap.treasury / owed).toFixed(1)}x**` : '**no debt**', inline: true },
      { name: 'Paid out today', value: `**${shortMoney(paidToday)}**`, inline: true },
      { name: 'Top players', value: board, inline: false },
    )
    .setFooter({ text: `${CASINO_HOST}  ·  updates every minute` });
  if (top.length) embed.setThumbnail(`https://mc-heads.net/avatar/${encodeURIComponent(top[0][0])}/100`);
  return { embeds: [embed] };
}

let vaultSignature = null;
async function ensureVault() {
  const channel = await findChannel(VAULT_CHANNELS);
  if (!channel) return;
  const payload = vaultMessage();
  // n'editer que si le contenu a bouge : sinon c'est une requete API par minute
  // pour rien, et l'horodatage seul ferait clignoter le message
  const sig = payload.embeds[0].data.description;
  if (state.vaultMessageId && sig === vaultSignature) return;

  if (state.vaultMessageId) {
    try {
      const msg = await channel.messages.fetch(state.vaultMessageId);
      await msg.edit(payload);
      vaultSignature = sig;
      return;
    } catch { state.vaultMessageId = null; }
  }

  const recent = await channel.messages.fetch({ limit: 50 });
  const mine = recent.find(m => m.author.id === client.user.id
    && m.embeds[0] && String(m.embeds[0].title || '').includes('THE VAULT'));
  const msg = mine ? (await mine.edit(payload)) : (await channel.send(payload));
  state.vaultMessageId = msg.id;
  vaultSignature = sig;
  saveState();
  console.log(`Vitrine du vault ${mine ? 'reprise' : 'posee'} dans #${channel.name} (${msg.id})`);
}

// ---------- rapport quotidien en image ----------

// L'image porte tout le detail, l'embed ne redit que ce qui doit rester
// lisible en notification et dans la recherche Discord. Les chiffres viennent
// du meme calcul que le rendu, jamais recalcules ici : deux formules pour un
// meme nombre finissent toujours par diverger.
function statsPayload(r) {
  const d = r.day;
  const file = `outmind-${d.day}.png`;
  const edge = d.wagered ? (d.profit / d.wagered) * 100 : 0;
  const embed = new EmbedBuilder()
    .setColor(d.profit >= 0 ? COLOR_OK : COLOR_BAD)
    .setTitle(`Daily report ${String.fromCharCode(183)} ${stats.prettyDay(d.day)}`)
    .setDescription(d.rounds
      ? `**${d.players}** players, **${d.rounds.toLocaleString('en-US')}** rounds, **${stats.shortMoney(d.wagered)}** wagered.`
      : 'No games were played on this day.')
    .addFields(
      { name: 'House profit', value: (d.profit > 0 ? '+' : '') + stats.shortMoney(d.profit), inline: true },
      { name: 'Edge', value: d.wagered ? edge.toFixed(1) + '%' : '-', inline: true },
      { name: 'Deposits / Cash outs', value: `${stats.shortMoney(d.deposits)} / ${stats.shortMoney(d.cashouts)}`, inline: true },
    )
    .setImage(`attachment://${file}`)
    .setFooter({ text: 'Vault ' + stats.shortMoney(r.treasury) });
  return { embeds: [embed], files: [new AttachmentBuilder(r.png, { name: file })] };
}

async function postStats(dayKey) {
  const channel = await findChannel(STATS_CHANNELS);
  if (!channel) throw new Error('channel absent'); // lastStatsDay non avance, reessai au prochain tick
  const r = stats.report(dayKey);
  await channel.send(statsPayload(r));
  console.log(`Rapport du ${dayKey} poste dans #${channel.name} (profit ${Math.round(r.day.profit)})`);
}

// Le rattrapage ne remonte volontairement pas plus loin que la veille. Apres
// une semaine machine eteinte, publier sept rapports d'un coup noierait le
// salon pour rien : les journees manquees restent consultables avec /stats.
async function statsTick() {
  const yesterday = stats.shiftDay(stats.todayKey(), -1);
  // Premier demarrage : on pose le point de depart sans rien publier. Le
  // module arrive en cours de route, et sortir d'anciens rapports dans un
  // salon que personne n'a encore vu n'apporte rien. /stats les rend a la
  // demande de toute facon.
  if (state.lastStatsDay == null) { state.lastStatsDay = yesterday; saveState(); return; }
  const hour = Number(new Date().toLocaleString('en-US', { timeZone: 'Europe/Paris', hour: '2-digit', hour12: false }));
  if (hour < STATS_HOUR) return;
  // comparaison de chaines volontaire : les cles sont en AAAA-MM-JJ, donc
  // l'ordre lexical est l'ordre chronologique
  if (state.lastStatsDay >= yesterday) return;
  await postStats(yesterday);
  state.lastStatsDay = yesterday;
  saveState();
}

// ---------- avis des joueurs (/vouch) ----------

// Le parcours : /vouch ouvre un menu d'etoiles. En dessous de 5 l'avis est
// enregistre comme retour prive et rien d'autre ne se passe. A 5 etoiles le bot
// ouvre la parole dans #vouch-us pour 15 minutes, et c'est le message
// REELLEMENT poste qui declenche le bonus. Payer sur le clic plutot que sur le
// message laisserait prendre 2M sans jamais rien ecrire.

const vouchChannel = () => findChannel(VOUCH_CHANNELS);
const starBar = (n) => '★'.repeat(n) + '☆'.repeat(5 - n);
const vouchKey = (player) => String(player).toLowerCase();

function hasVouched(player) { return !!state.vouches[vouchKey(player)]; }

function vouchIntro(link) {
  const done = state.vouches[vouchKey(link.player)];
  if (done) {
    return {
      embeds: [new EmbedBuilder().setColor(COLOR_OK).setTitle('You already vouched')
        .setDescription(`Thanks again. **${link.player}** rated the casino ${starBar(done.stars)} and the ${money(VOUCH_BONUS)} bonus was paid <t:${Math.floor(done.at / 1000)}:R>.`)],
      components: [],
    };
  }
  const embed = new EmbedBuilder()
    .setColor(COLOR)
    .setTitle('Rate the Outmind Casino')
    .setDescription(
      `How was it, **${link.player}**?\n\n` +
      `A **${starBar(5)}** rating unlocks writing in **#${VOUCH_CHANNELS[0]}** for 15 minutes, and once your vouch is posted ` +
      `**${money(VOUCH_BONUS)}** lands on your casino balance.\n\n` +
      'Anything lower is kept as private feedback for the staff. One paid vouch per account.');
  const menu = new StringSelectMenuBuilder()
    .setCustomId('oc_vouch_stars')
    .setPlaceholder('Pick your rating')
    .addOptions([5, 4, 3, 2, 1].map(n => new StringSelectMenuOptionBuilder()
      .setLabel(`${starBar(n)}  ${n} star${n > 1 ? 's' : ''}`)
      .setValue(String(n))
      .setDescription(n === 5 ? `Unlocks the vouch channel and ${shortMoney(VOUCH_BONUS)}` : 'Private feedback, no bonus')));
  return { embeds: [embed], components: [new ActionRowBuilder().addComponents(menu)] };
}

// Ouvre la parole par un overwrite de MEMBRE sur le salon. Pas un role : un
// role temporaire distribue a la chaine laisserait des traces si le bot meurt
// au mauvais moment, alors qu'un overwrite se lit et se nettoie salon par salon.
async function openVouchWindow(interaction, link, stars) {
  const channel = await vouchChannel();
  if (!channel) throw new Error('channel absent');
  const until = Date.now() + VOUCH_WINDOW_MS;
  await channel.permissionOverwrites.edit(interaction.user.id, { SendMessages: true },
    { reason: `vouch 5 etoiles de ${link.player}` });
  state.vouchWindows[interaction.user.id] = { player: link.player, stars, until };
  saveState();
  console.log(`Vouch : parole ouverte a ${link.player} (${interaction.user.tag}) jusqu'a ${new Date(until).toISOString()}`);
  return { channel, until };
}

async function closeVouchWindow(discordId, why) {
  const w = state.vouchWindows[discordId];
  delete state.vouchWindows[discordId];
  saveState();
  try {
    const channel = await vouchChannel();
    if (channel) await channel.permissionOverwrites.delete(discordId, why);
  } catch (e) { console.warn('Vouch, fermeture de la parole :', e.message); }
  if (w) console.log(`Vouch : parole fermee pour ${w.player} (${why})`);
}

async function handleVouchStars(interaction) {
  await interaction.deferUpdate();
  const link = linkOf(interaction.user.id);
  if (!link) return interaction.editReply({ content: NOT_LINKED, embeds: [], components: [] });
  if (hasVouched(link.player)) return interaction.editReply(vouchIntro(link));

  const stars = Number(interaction.values[0]);
  if (!Number.isInteger(stars) || stars < 1 || stars > 5) return interaction.editReply('Pick a rating between 1 and 5.');

  if (stars < 5) {
    // On ne consomme PAS le droit au vouch : quelqu'un qui note 3 honnetement
    // doit pouvoir revenir. Il n'y a rien a exploiter, seul le 5 paie.
    state.ratings.push({ at: Date.now(), player: link.player, discordId: interaction.user.id, stars });
    saveState();
    await postRating(link.player, stars, interaction.user);
    await ensureVouchTopic().catch(e => console.warn('Vouch, description :', e.message));
    return interaction.editReply({
      embeds: [new EmbedBuilder().setColor(COLOR).setTitle(`Thanks, ${starBar(stars)} noted`)
        .setDescription('Your rating went straight to the staff, nothing is posted publicly. Open a ticket if something needs fixing, and run `/vouch` again whenever you change your mind.')],
      components: [],
    });
  }

  let opened;
  try { opened = await openVouchWindow(interaction, link, stars); }
  catch (e) {
    console.warn('Vouch, ouverture :', e.message);
    return interaction.editReply({
      embeds: [new EmbedBuilder().setColor(COLOR_BAD).setTitle('Could not open the vouch channel')
        .setDescription('The staff has been told. Try again in a few minutes.')],
      components: [],
    });
  }

  return interaction.editReply({
    embeds: [new EmbedBuilder().setColor(COLOR_OK).setTitle(`${starBar(5)} You can post your vouch`)
      .setDescription(
        `Head to <#${opened.channel.id}> and write your review there. You have until <t:${Math.floor(opened.until / 1000)}:t>.\n\n` +
        `As soon as it is posted, **${money(VOUCH_BONUS)}** goes on the casino balance of **${link.player}**.`)
      .setFooter({ text: `Write at least ${VOUCH_MIN_CHARS} characters, a single word does not count.` })],
    components: [],
  });
}

// Le paiement se declenche ICI, sur le message reel. Sans l'intent
// MessageContent (privilegie, a activer dans le portail developpeur), content
// arrive vide : on ne peut alors pas verifier la longueur, et on accepte plutot
// que de bloquer un joueur pour une raison qu'il ne comprendrait pas.
async function onVouchMessage(message) {
  if (message.author.bot || !message.guild) return;
  const channel = await vouchChannel();
  if (!channel || message.channelId !== channel.id) return;

  const w = state.vouchWindows[message.author.id];
  if (!w) return;
  if (Date.now() > w.until) return closeVouchWindow(message.author.id, 'fenetre expiree');
  if (hasVouched(w.player)) return closeVouchWindow(message.author.id, 'deja paye');

  const text = String(message.content || '');
  const contentVisible = text.length > 0;
  if (contentVisible && text.trim().length < VOUCH_MIN_CHARS) {
    // le DM seul etait un trou noir : DMs fermes (reglage par defaut de
    // beaucoup de joueurs) = aucun retour, le joueur croit son vouch valide
    // et attend un bonus qui ne vient pas (vecu par a player le 2026-08-18).
    // On repond DANS le salon, visible a coup sur, et on trace en console.
    const short = `Your vouch is a bit short (${text.trim().length}/${VOUCH_MIN_CHARS} characters). Add a little more detail and post again, the ${money(VOUCH_BONUS)} bonus lands right after.`;
    console.log(`Vouch : message trop court de ${w.player} (${text.trim().length} car.), fenetre laissee ouverte`);
    try { await message.reply(short); }
    catch { try { await message.author.send(short); } catch {} }
    return; // fenetre laissee ouverte, il peut recommencer
  }

  const player = adminLib.resolvePlayer(w.player) || w.player;
  state.vouches[vouchKey(w.player)] = {
    at: Date.now(), stars: w.stars, discordId: message.author.id,
    messageId: message.id, amount: VOUCH_BONUS,
  };
  saveState();
  await closeVouchWindow(message.author.id, 'vouch poste');

  adminLib.queueOrder({
    kind: 'credit', player, amount: VOUCH_BONUS,
    reason: '5 star vouch bonus', by: `vouch:${message.author.tag}`, byId: message.author.id,
  });
  console.log(`Vouch : ${player} a poste, ${VOUCH_BONUS} credites (message ${message.id})`);

  await giveVoucherRole(message.member);
  await markVouchValidated(message);

  try {
    await message.author.send({
      embeds: [new EmbedBuilder().setColor(COLOR_OK).setTitle('Vouch bonus paid')
        .setDescription(`Thanks for the ${starBar(5)}. **${money(VOUCH_BONUS)}** is on the casino balance of **${player}**, it shows up in game within a few seconds.`)],
    });
  } catch {}

  await postVouchAudit(player, message, contentVisible ? text : null);
  await ensureVouchTopic().catch(e => console.warn('Vouch, description :', e.message));
}

// ---------- note globale affichee dans la description du salon ----------

// Une note par JOUEUR, la plus recente l'emporte. state.ratings est une liste
// append-only : quelqu'un qui relance /vouch trois fois y laisse trois lignes,
// les compter toutes ferait peser son avis trois fois dans la moyenne.
//
// Les notes basses comptent, alors qu'elles restent privees cote message. Sans
// elles la moyenne vaudrait 5,0 par construction, puisque seuls les 5 etoiles
// sont payes : une note globale qui ne peut pas bouger n'informe personne.
function vouchScore() {
  const per = new Map();
  for (const r of state.ratings || []) {
    const k = vouchKey(r.player), prev = per.get(k);
    if (!prev || r.at >= prev.at) per.set(k, { at: r.at, stars: r.stars });
  }
  for (const [k, v] of Object.entries(state.vouches || {})) {
    const prev = per.get(k);
    if (!prev || v.at >= prev.at) per.set(k, { at: v.at, stars: v.stars });
  }
  const stars = [...per.values()].map(v => v.stars);
  const count = stars.length;
  return { count, avg: count ? stars.reduce((a, b) => a + b, 0) / count : 0 };
}

function vouchTopic() {
  const { count, avg } = vouchScore();
  const tail = `/vouch to add yours, ${starBar(5)} pays ${shortMoney(VOUCH_BONUS)}`;
  if (!count) return `Outmind Casino reviews. ${tail}`;
  return `${starBar(Math.round(avg))}  ${avg.toFixed(1)} out of 5, ${count} review${count > 1 ? 's' : ''}  |  ${tail}`;
}

// La description d'un salon est limitee a 2 modifications par 10 minutes, et
// Discord repond 429 au-dela. On n'ecrit donc que si le texte change VRAIMENT,
// et jamais plus d'une fois par TOPIC_MIN_GAP_MS : le reste attend le balayage
// suivant. Le texte applique est memorise pour survivre a un redemarrage.
const TOPIC_MIN_GAP_MS = 6 * 60 * 1000;
let lastTopicAt = 0;

async function ensureVouchTopic() {
  const want = vouchTopic();
  if (state.vouchTopic === want) return;
  if (Date.now() - lastTopicAt < TOPIC_MIN_GAP_MS) return; // rejoue au balayage suivant
  const channel = await vouchChannel();
  if (!channel) return;
  lastTopicAt = Date.now();
  await channel.setTopic(want, 'note globale des avis');
  state.vouchTopic = want;
  saveState();
  console.log(`Vouch : description mise a jour, "${want}"`);
}

// Coche verte posee par le bot sur le vouch valide (U+2705). C'est le seul
// caractere hors ASCII du fichier : s'il ressort un jour en points
// d'interrogation, c'est l'encodage du fichier qui a saute, pas Discord.
// Le bot a bien ADD_REACTIONS dans #vouch-us, c'est dans son overwrite membre.
const VOUCH_TICK = '✅';

// Purement visuel, et donc jamais bloquant : le bonus est deja credite quand on
// arrive ici. Une reaction qui echoue (permission retiree, message efface) ne
// doit pas faire echouer le tour ni relancer quoi que ce soit.
async function markVouchValidated(message) {
  try { await message.react(VOUCH_TICK); }
  catch (e) { console.warn('Vouch, reaction :', e.message); }
}

// Le role VOUCHER existait deja sur le serveur avant ce systeme, pose a la main
// par Ryan. On le reutilise comme badge plutot que d'en creer un doublon, et on
// le cherche par sous-chaine : sa decoration peut changer, pas son nom.
async function giveVoucherRole(member) {
  if (!member) return;
  try {
    const role = member.guild.roles.cache.find(r => /voucher/i.test(r.name));
    if (!role || member.roles.cache.has(role.id)) return;
    await member.roles.add(role, 'vouch 5 etoiles');
    console.log(`Vouch : role ${role.name} donne a ${member.user.tag}`);
  } catch (e) { console.warn('Vouch, role :', e.message); }
}

async function postVouchAudit(player, message, text) {
  try {
    const ch = await findChannel(STATS_CHANNELS);
    if (!ch) return;
    const embed = new EmbedBuilder()
      .setColor(COLOR_OK)
      .setAuthor({ name: player, iconURL: `https://mc-heads.net/avatar/${encodeURIComponent(player)}/64` })
      .setTitle('Vouch bonus paid')
      .setDescription(`${starBar(5)} by <@${message.author.id}>, ${money(VOUCH_BONUS)} credited to **${player}**.\n[Jump to the vouch](${message.url})`)
      .setTimestamp(new Date());
    if (text) embed.addFields({ name: 'What they wrote', value: text.slice(0, 1000) });
    else embed.addFields({ name: 'Note', value: 'Content not readable, the MessageContent intent is off. Length was not checked.' });
    await ch.send({ embeds: [embed] });
  } catch (e) { console.warn('Audit vouch :', e.message); }
}

async function postRating(player, stars, user) {
  try {
    const ch = await findChannel(STATS_CHANNELS);
    if (!ch) return;
    await ch.send({ embeds: [new EmbedBuilder().setColor(stars <= 2 ? COLOR_BAD : COLOR)
      .setAuthor({ name: player, iconURL: `https://mc-heads.net/avatar/${encodeURIComponent(player)}/64` })
      .setTitle(`Private rating ${starBar(stars)}`)
      .setDescription(`<@${user.id}> rated the casino **${stars}/5**. Nothing was posted publicly and no bonus was paid.`)
      .setTimestamp(new Date())] });
  } catch (e) { console.warn('Audit note :', e.message); }
}

// Une fenetre non utilisee doit se refermer meme si le bot a redemarre entre
// temps, sinon l'overwrite reste et le joueur garde la parole pour toujours.
async function sweepVouchWindows() {
  const now = Date.now();
  // RATTRAPAGE avant fermeture : un vouch poste pendant que le bot etait
  // mort ou bloque n'a jamais ete vu par onVouchMessage (vecu deux fois le
  // 2026-08-18 : menu expire pendant un blocage, puis validation manuelle
  // obligatoire pour a player). Pour chaque fenetre encore connue, on relit
  // les messages du salon depuis l'ouverture et on traite ce qu'on a rate.
  const windows = Object.entries(state.vouchWindows);
  if (windows.length) {
    const channel = await vouchChannel().catch(() => null);
    if (channel) {
      const recent = await channel.messages.fetch({ limit: 50 }).catch(() => null);
      if (recent) {
        for (const [id, w] of windows) {
          const openedAt = w.until - VOUCH_WINDOW_MS;
          const candidate = recent.filter(m => m.author.id === id && m.createdTimestamp >= openedAt)
            .sort((a, b) => a.createdTimestamp - b.createdTimestamp)
            .find(m => String(m.content || '').trim().length >= VOUCH_MIN_CHARS);
          if (candidate) {
            console.log(`Vouch : rattrapage d'un message manque de ${w.player} (${candidate.id})`);
            await onVouchMessage(candidate).catch(e => console.warn('Vouch, rattrapage :', e.message));
          }
        }
      }
    }
  }
  for (const [id, w] of Object.entries(state.vouchWindows)) {
    if (now > w.until) {
      await closeVouchWindow(id, 'fenetre expiree');
      // fini l'expiration muette : le joueur sait pourquoi rien n'est arrive
      // et comment recommencer. DM best-effort, il ne peut plus ecrire au salon.
      try {
        const user = await client.users.fetch(id);
        await user.send(`Your 15 minute vouch window closed without a valid vouch (${VOUCH_MIN_CHARS} characters minimum). Run \`/vouch\` again whenever you want, the ${money(VOUCH_BONUS)} bonus is still waiting.`);
      } catch {}
    }
  }
  // c'est aussi ici que la description rattrape les notes qu'elle n'a pas pu
  // ecrire tout de suite a cause de la limite de frequence
  await ensureVouchTopic();
}

// ---------- salons de presentation ----------

// Les chiffres cites dans la vitrine sortent tous d'ici, une seule fois, et
// pitch.js ne recoit que des chaines deja formatees : il n'existe ainsi qu'une
// implementation du formatage monetaire dans le projet.
function pitchLive() {
  const o = adminLib.overview();
  const tx = adminLib.readLines('transactions.jsonl');
  const cashouts = tx.filter(t => t.type === 'retrait');
  const { count, avg } = vouchScore();
  return {
    casinoHost: CASINO_HOST,
    donutHost: DONUT_HOST,
    bankAccount: BANK_ACCOUNT,
    treasury: money(o.treasury),
    owed: money(o.owed),
    coverage: o.coverage === Infinity ? 'no debt yet' : o.coverage.toFixed(2) + 'x',
    paidOut: money(cashouts.reduce((s, t) => s + t.amount, 0)),
    cashoutCount: String(cashouts.length),
    ratingLine: count
      ? `${starBar(Math.round(avg))} **${avg.toFixed(1)} out of 5** over ${count} review${count > 1 ? 's' : ''}, in **#vouch-us**.`
      : 'No review yet. Run `/vouch` and be the first.',
    welcomeBonus: money(WELCOME_BONUS),
    gamblerMax: shortMoney(PLAYER_MAX_GAMBLER),
    investorMax: shortMoney(PLAYER_MAX_INVESTOR),
    investorMin: money(INVESTOR_MIN),
    dailyMax: isFinite(DAILY_MAX) ? shortMoney(DAILY_MAX) : 'no fixed cap',
    houseCapText: isFinite(DAILY_MAX)
      ? `the lower of **${shortMoney(DAILY_MAX)}** and **${Math.round(DAILY_VAULT_PCT * 100)}% of the vault**`
      : `**${Math.round(DAILY_VAULT_PCT * 100)}% of the vault**`,
    vaultPct: Math.round(DAILY_VAULT_PCT * 100) + '%',
    autopayMax: money(AUTOPAY_MAX),
    autodepositOn: AUTODEPOSIT_ON,
  };
}

// pitch.js decrit ses boutons en donnees pures pour ne pas dependre de
// discord.js. Les identifiants pointent vers des handlers qui existent deja :
// la vitrine est une porte d'entree de plus, pas un second circuit.
const PITCH_STYLES = {
  primary: ButtonStyle.Primary, success: ButtonStyle.Success,
  danger: ButtonStyle.Danger, secondary: ButtonStyle.Secondary,
};
function pitchComponents(doc) {
  if (!doc.buttons || !doc.buttons.length) return [];
  return [new ActionRowBuilder().addComponents(doc.buttons.map(b => new ButtonBuilder()
    .setCustomId(b.id).setLabel(b.label).setStyle(PITCH_STYLES[b.style] || ButtonStyle.Secondary)))];
}

// Pose chaque document une fois puis le reedite. La signature evite une
// requete toutes les 10 minutes pour rien : seuls les documents dont un chiffre
// a bouge sont reecrits.
async function ensurePitch() {
  const live = pitchLive();
  for (const doc of pitch.documents(live)) {
    const channel = await findChannel(doc.channels);
    if (!channel) continue;
    const sig = JSON.stringify([doc.embed, doc.buttons || null]);
    const saved = state.pitch[doc.key];
    if (saved && saved.sig === sig && saved.channelId === channel.id) continue;

    const payload = { embeds: [doc.embed], components: pitchComponents(doc) };
    if (saved && saved.messageId && saved.channelId === channel.id) {
      try {
        const msg = await channel.messages.fetch(saved.messageId);
        await msg.edit(payload);
        state.pitch[doc.key] = { messageId: msg.id, channelId: channel.id, sig };
        saveState();
        console.log(`Vitrine "${doc.key}" mise a jour dans #${channel.name}`);
        continue;
      } catch { /* message supprime a la main, on repart plus bas */ }
    }
    // reprise d'un message deja poste avant qu'on perde son id, sinon on
    // empilerait un doublon a chaque suppression de links.json
    const recent = await channel.messages.fetch({ limit: 50 });
    const mine = recent.find(m => m.author.id === client.user.id
      && m.embeds[0] && m.embeds[0].title === doc.embed.title);
    const msg = mine ? await mine.edit(payload) : await channel.send(payload);
    state.pitch[doc.key] = { messageId: msg.id, channelId: channel.id, sig };
    saveState();
    console.log(`Vitrine "${doc.key}" ${mine ? 'reprise' : 'posee'} dans #${channel.name} (${msg.id})`);
  }
}

// ---------- console d'administration ----------

// Toute action d'argent est en DEUX temps (formulaire puis confirmation) et
// laisse une trace publique dans #bank-console avec le nom de qui l'a faite.
// Un panneau qui deplace de l'argent sans journal, c'est une porte de derriere,
// et on en a deja paye une (les 90M partis par les MP du bot).
const pendingAdmin = new Map(); // userId -> action en attente de confirmation

// Anti double clic sur Pay on DonutSMP : le 2026-08-17, deux confirmations a
// 63 secondes d'ecart ont envoye 42M pour un cashout de 21M. Meme joueur, meme
// montant, moins de 2 minutes : refus sec. Le bridge porte la meme barriere de
// son cote (state.lastAdminPays), celle-ci n'est que la premiere ligne.
const PAY_DEDUP_MS = 2 * 60 * 1000;
const lastAdminPays = new Map(); // "joueur|montant" -> timestamp

function isAdmin(interaction) {
  return !!interaction.memberPermissions && interaction.memberPermissions.has(PermissionFlagsBits.ManageGuild);
}

// Etat de la journee cote maison, sans passer par un joueur precis.
function houseDaily() {
  const today = new Date().toLocaleDateString('en-CA', { timeZone: 'Europe/Paris' });
  const d = readJson('daily-cap.json', {});
  const fresh = d.day === today ? d : { paid: 0, players: {} };
  const treasury = casinoSnapshot().treasury;
  const cap = Math.min(DAILY_MAX, treasury * DAILY_VAULT_PCT);
  return {
    cap, paid: fresh.paid || 0, left: Math.max(0, cap - (fresh.paid || 0)), players: fresh.players || {},
    // sorties manuelles de la console : hors plafond, mais l'argent sort quand
    // meme de la caisse, donc affichees a cote pour ne pas les perdre de vue
    manualPaid: fresh.manualPaid || 0,
  };
}

function adminPanel() {
  const o = adminLib.overview();
  const h = houseDaily();
  const cov = o.coverage === Infinity ? 'no debt' : (o.coverage).toFixed(2) + 'x';
  const online = o.onlinePlayers.length ? o.onlinePlayers.join(', ') : 'nobody';

  const embed = new EmbedBuilder()
    .setColor(COLOR)
    .setTitle('Admin console')
    .setDescription(`Casino **${CASINO_HOST}**, bank **${BANK_ACCOUNT}** on ${DONUT_HOST}.`)
    .addFields(
      { name: 'Vault', value: money(o.treasury), inline: true },
      { name: 'Owed to players', value: money(o.owed), inline: true },
      { name: 'Coverage', value: cov, inline: true },
      { name: 'Daily cap (players)', value: `${money(h.paid)} out of ${money(h.cap)}\n${money(h.left)} left`
        + (h.manualPaid ? `\nplus ${money(h.manualPaid)} paid by hand` : ''), inline: true },
      { name: 'Bank bot', value: o.botOnline ? 'online' : 'OFFLINE', inline: true },
      { name: 'Lifetime profit', value: money(o.profit) + `\n${o.rounds.toLocaleString('en-US')} rounds`, inline: true },
      { name: `On ${CASINO_HOST} (${o.onlinePlayers.length})`, value: online.slice(0, 1000), inline: false },
    )
    .setFooter({ text: o.blacklist.length
      ? `${o.blacklist.length} blacklisted: ${o.blacklist.join(', ')}`.slice(0, 2000)
      : 'nobody blacklisted' })
    .setTimestamp(new Date());

  if (o.pendingOrders) {
    embed.addFields({ name: 'Pending orders', value: `${o.pendingOrders} waiting for the bridge. If this does not drop, the bridge is down.`, inline: false });
  }

  return {
    embeds: [embed],
    components: [
      new ActionRowBuilder().addComponents(
        new ButtonBuilder().setCustomId('oca_refresh').setLabel('Refresh').setStyle(ButtonStyle.Secondary),
        new ButtonBuilder().setCustomId('oca_look').setLabel('Look up player').setStyle(ButtonStyle.Primary),
        new ButtonBuilder().setCustomId('oca_bl').setLabel('Blacklist').setStyle(ButtonStyle.Danger)),
      new ActionRowBuilder().addComponents(
        new ButtonBuilder().setCustomId('oca_credit').setLabel('Credit balance').setStyle(ButtonStyle.Success),
        new ButtonBuilder().setCustomId('oca_debit').setLabel('Debit balance').setStyle(ButtonStyle.Secondary),
        new ButtonBuilder().setCustomId('oca_pay').setLabel('Pay on DonutSMP').setStyle(ButtonStyle.Danger)),
    ],
  };
}

const ago = (ms) => ms ? `<t:${Math.floor(ms / 1000)}:R>` : 'never';

// Le dossier complet d'un joueur : identite, argent, quotas, historique. C'est
// la reponse a « il dit qu'il a pas ete paye » sans avoir a ouvrir un fichier.
async function playerDossier(name) {
  const player = adminLib.resolvePlayer(name);
  if (!player) {
    return { embeds: [new EmbedBuilder().setColor(COLOR_BAD).setTitle('Unknown player')
      .setDescription(`No casino record for \`${String(name).slice(0, 40)}\`. The name must match a player the casino already knows.`)] };
  }
  const info = await withdrawableFor(player);
  const h = adminLib.history(player);
  const discordId = discordIdOf(player);
  const d = info.daily;

  const embed = new EmbedBuilder()
    .setColor(info.blacklisted ? COLOR_BAD : COLOR)
    .setAuthor({ name: player, iconURL: `https://mc-heads.net/avatar/${encodeURIComponent(player)}/64` })
    .setTitle(`${d.grade}${info.blacklisted ? ' (BLACKLISTED)' : ''}`)
    .setDescription([
      discordId ? `Linked to <@${discordId}>` : 'Not linked to any Discord account',
      info.playerOnline ? `Online on ${CASINO_HOST} right now` : 'Not on the casino server',
      info.invested ? `Invested ${money(info.invested)} into the house` : 'Never invested',
    ].join('\n'))
    .addFields(
      { name: 'In game', value: money(info.inGame), inline: true },
      { name: 'Bank ledger', value: money(info.bank), inline: true },
      { name: 'Withdrawable', value: money(info.withdrawable) + (info.reserve ? `\nreserve ${money(info.reserve)}` : ''), inline: true },
      { name: 'Daily left', value: `${money(d.left)} of ${money(d.personalMax)}` + (d.blockedByHouse ? '\ncapped by the house' : ''), inline: true },
      { name: 'Deposited', value: `${money(h.deposits.total)}\n${h.deposits.count} times`, inline: true },
      { name: 'Cashed out', value: `${money(h.cashouts.total)}\n${h.cashouts.count} times`, inline: true },
      { name: 'Net to the house', value: (h.net >= 0 ? '+' : '') + money(h.net), inline: true },
      { name: 'Gambling', value: h.game.rounds
        ? `${h.game.rounds.toLocaleString('en-US')} rounds over ${h.game.days} day(s)\nwagered ${shortMoney(h.game.wagered)}, net ${(h.game.net >= 0 ? '+' : '') + shortMoney(h.game.net)}`
        : 'never played', inline: true },
      { name: 'Best / worst round', value: h.game.rounds
        ? `+${shortMoney(h.game.bestWin)} / ${shortMoney(h.game.worstLoss)}` : '-', inline: true },
    );

  if (h.game.lastAt) embed.addFields({ name: 'Last round', value: ago(h.game.lastAt), inline: false });
  if (h.recent.length) {
    embed.addFields({ name: 'Last movements', value: h.recent.map(o =>
      `${ago(o.ms)} ${o.type === 'depot' ? 'deposit' : 'cash out'} \`${o.type === 'depot' ? '+' : '-'}${money(o.amount)}\``).join('\n') });
  }
  return { embeds: [embed] };
}

function adminModal(kind) {
  const titles = {
    credit: ['oca_m_credit', 'Credit a casino balance', 'Amount to add'],
    debit: ['oca_m_debit', 'Debit a casino balance', 'Amount to remove'],
    pay: ['oca_m_pay', 'Pay on DonutSMP', 'Amount to send'],
  };
  const [id, title, amountLabel] = titles[kind];
  const modal = new ModalBuilder().setCustomId(id).setTitle(title);
  modal.addComponents(
    new ActionRowBuilder().addComponents(new TextInputBuilder()
      .setCustomId('player').setLabel('Minecraft name').setStyle(TextInputStyle.Short).setMaxLength(20).setRequired(true)),
    new ActionRowBuilder().addComponents(new TextInputBuilder()
      .setCustomId('amount').setLabel(amountLabel + ' (300k, 1.5m, 2000000)').setStyle(TextInputStyle.Short).setMaxLength(20).setRequired(true)),
    new ActionRowBuilder().addComponents(new TextInputBuilder()
      .setCustomId('reason').setLabel('Reason (kept in the audit log)').setStyle(TextInputStyle.Short).setMaxLength(120).setRequired(true)),
  );
  return modal;
}

// Ce que l'action va reellement faire, en clair, avant de la confirmer. Un
// panneau qui dit juste « credit 5M ? » laisse l'operateur deviner si l'argent
// sort de la caisse ou du solde en jeu.
const ADMIN_EFFECT = {
  credit: (p, a) => `**${p}** gets **${money(a)}** added to their casino balance. It appears in game within about 10 seconds. Nothing leaves the vault until they cash out.`,
  debit: (p, a) => `**${money(a)}** is removed from the casino balance of **${p}**, floored at zero. It disappears in game within about 10 seconds.`,
  pay: (p, a) => `The bank bot sends **${money(a)}** to **${p}** on ${DONUT_HOST}, right now. Real money leaves the vault, and it counts against today's cap without being blocked by it.`,
};

async function adminConfirm(interaction, kind) {
  const raw = interaction.fields.getTextInputValue('player');
  const player = adminLib.resolvePlayer(raw);
  const amount = parseAmount(interaction.fields.getTextInputValue('amount'));
  // un champ d'embed vide fait rejeter tout le message par l'API : le motif est
  // obligatoire cote modal, mais rien n'empeche d'y taper trois espaces
  const reason = interaction.fields.getTextInputValue('reason').trim() || 'no reason given';

  if (!player) return interaction.editReply(`Unknown player \`${raw.slice(0, 40)}\`. The casino has no record under that name.`);
  if (amount < 1) return interaction.editReply('Invalid amount. Try `300k`, `1.5m` or `2000000`.');

  const info = await withdrawableFor(player);
  const warnings = [];
  if (kind === 'debit' && amount > info.bank) warnings.push(`Their ledger only holds ${money(info.bank)}, the rest cannot be taken.`);
  if (kind === 'pay') {
    if (!info.botOnline) warnings.push('The bank bot is OFFLINE. The order will sit in the queue until it comes back.');
    if (amount > info.treasury) warnings.push(`The vault only holds ${money(info.treasury)}. The payment will fail in game.`);
    const last = lastAdminPays.get(`${player.toLowerCase()}|${amount}`);
    if (last && Date.now() - last < PAY_DEDUP_MS) {
      warnings.push(`You already paid **exactly this** ${Math.round((Date.now() - last) / 1000)}s ago. A second confirm will be refused for ${Math.ceil((PAY_DEDUP_MS - (Date.now() - last)) / 60000)} more minute(s).`);
    }
  }

  pendingAdmin.set(interaction.user.id, { kind, player, amount, reason });

  const embed = new EmbedBuilder()
    .setColor(kind === 'pay' ? COLOR_BAD : COLOR)
    .setTitle(`Confirm: ${kind} ${shortMoney(amount)}`)
    .setDescription(ADMIN_EFFECT[kind](player, amount))
    .addFields({ name: 'Reason', value: reason });
  if (warnings.length) embed.addFields({ name: 'Careful', value: warnings.join('\n') });

  return interaction.editReply({
    embeds: [embed],
    components: [new ActionRowBuilder().addComponents(
      new ButtonBuilder().setCustomId('oca_go').setLabel('Confirm').setStyle(ButtonStyle.Danger),
      new ButtonBuilder().setCustomId('oc_cancel').setLabel('Cancel').setStyle(ButtonStyle.Secondary))],
  });
}

// La blacklist n'est pas un mouvement d'argent, elle ne passe donc pas par la
// file du bridge : ecriture directe dans blacklist.json, exactement comme le
// fait deja panel.js. Le bridge la relit a chaque tick et se charge du retrait
// de whitelist et du kick.
async function adminConfirmBlacklist(interaction) {
  const raw = interaction.fields.getTextInputValue('player');
  const reason = interaction.fields.getTextInputValue('reason').trim() || 'no reason given';
  const player = adminLib.resolvePlayer(raw) || raw.trim();
  const current = (adminLib.readJson('blacklist.json', []) || []).map(n => String(n).toLowerCase());
  const already = current.includes(player.toLowerCase());
  const add = !already;

  pendingAdmin.set(interaction.user.id, { kind: 'blacklist', player, add, reason });

  const embed = new EmbedBuilder()
    .setColor(add ? COLOR_BAD : COLOR_OK)
    .setTitle(add ? `Blacklist ${player}` : `Un-blacklist ${player}`)
    .setDescription(add
      ? `**${player}** gets removed from the whitelist and kicked from ${CASINO_HOST} within 10 seconds. The bank bot stops answering them, and their deposits are still credited but silently.`
      : `**${player}** goes back on the whitelist and can play again.`)
    .addFields({ name: 'Reason', value: reason });
  if (add && !adminLib.resolvePlayer(raw)) {
    embed.addFields({ name: 'Careful', value: 'The casino has no record under that name. Blacklisting still works, but check the spelling.' });
  }

  return interaction.editReply({
    embeds: [embed],
    components: [new ActionRowBuilder().addComponents(
      new ButtonBuilder().setCustomId('oca_go').setLabel('Confirm').setStyle(ButtonStyle.Danger),
      new ButtonBuilder().setCustomId('oc_cancel').setLabel('Cancel').setStyle(ButtonStyle.Secondary))],
  });
}

async function adminExecute(interaction) {
  const act = pendingAdmin.get(interaction.user.id);
  if (!act) return interaction.editReply({ content: 'That confirmation expired, start again from /admin.', embeds: [], components: [] });
  pendingAdmin.delete(interaction.user.id);
  const who = { by: interaction.user.tag, byId: interaction.user.id };

  if (act.kind === 'blacklist') {
    const list = adminLib.setBlacklist(act.player, act.add);
    adminLib.audit({ at: Date.now(), kind: 'blacklist', player: act.player, add: act.add, reason: act.reason, ...who });
    console.log(`Admin blacklist ${act.add ? '+' : '-'}${act.player} par ${interaction.user.tag} : ${act.reason}`);
    await postAudit({ ...act, note: act.add
      ? `**${act.player}** is now blacklisted from the casino.`
      : `**${act.player}** is no longer blacklisted.` }, interaction.user);
    return interaction.editReply({
      embeds: [new EmbedBuilder().setColor(COLOR_OK)
        .setTitle(act.add ? `${act.player} blacklisted` : `${act.player} un-blacklisted`)
        .setDescription(`${list.length} player(s) on the list. The bridge applies it within 10 seconds.`)],
      components: [],
    });
  }

  if (act.kind === 'pay') {
    const key = `${act.player.toLowerCase()}|${act.amount}`;
    const last = lastAdminPays.get(key);
    if (last && Date.now() - last < PAY_DEDUP_MS) {
      return interaction.editReply({
        embeds: [new EmbedBuilder().setColor(COLOR_BAD).setTitle('Refused: duplicate payment')
          .setDescription(`You paid **${money(act.amount)}** to **${act.player}** ${Math.round((Date.now() - last) / 1000)}s ago. `
            + 'Same player, same amount, under 2 minutes: that is a double click, not an intention. '
            + 'Wait it out, or change the amount by a dollar if you really mean to pay twice.')],
        components: [],
      });
    }
    lastAdminPays.set(key, Date.now());
  }

  adminLib.queueOrder({ kind: act.kind, player: act.player, amount: act.amount, reason: act.reason, ...who });
  console.log(`Admin ${act.kind} ${act.player} ${act.amount} par ${interaction.user.tag} : ${act.reason}`);

  const verb = { credit: 'Credited', debit: 'Debited', pay: 'Paid on DonutSMP' }[act.kind];
  await postAudit(act, interaction.user);
  return interaction.editReply({
    embeds: [new EmbedBuilder().setColor(COLOR_OK).setTitle(`${verb} ${shortMoney(act.amount)}`)
      .setDescription(`Order queued for **${act.player}**. The bridge picks it up within 10 seconds.`)
      .addFields({ name: 'Reason', value: act.reason })],
    components: [],
  });
}

// La trace publique. Elle part dans le meme salon que le rapport quotidien, qui
// est deja prive et reserve a l'equipe.
async function postAudit(act, user) {
  try {
    const channel = await findChannel(STATS_CHANNELS);
    if (!channel) return;
    const titles = { credit: 'Balance credited', debit: 'Balance debited', pay: 'Manual payout', blacklist: 'Blacklist changed' };
    const embed = new EmbedBuilder()
      .setColor(act.kind === 'credit' ? COLOR_OK : COLOR_BAD)
      .setAuthor({ name: act.player, iconURL: `https://mc-heads.net/avatar/${encodeURIComponent(act.player)}/64` })
      .setTitle(titles[act.kind] || act.kind)
      .setDescription(act.amount ? `**${money(act.amount)}** ${act.kind === 'pay' ? `sent to **${act.player}** on ${DONUT_HOST}` : `on the casino balance of **${act.player}**`}` : act.note || '')
      .addFields(
        { name: 'By', value: `<@${user.id}>`, inline: true },
        { name: 'Reason', value: act.reason || '-', inline: true })
      .setTimestamp(new Date());
    await channel.send({ embeds: [embed] });
  } catch (e) { console.warn('Audit admin :', e.message); }
}

// Au premier demarrage on part de la fin des fichiers, sinon tout l'historique
// serait rejoue d'un coup.
function startWatchers() {
  for (const [key, file] of [['payoutOffset', 'payout-results.jsonl'], ['txOffset', 'transactions.jsonl']]) {
    if (state[key] == null) { state[key] = (readLines(file) || []).length; saveState(); }
  }
  // VERROU de reentrance : un tick lent (rate limit Discord apres un wipe,
  // rafale d'embeds) ne doit pas laisser le suivant entrer avec le MEME
  // offset : chaque tick concurrent repostait alors les memes embeds. Vecu le
  // 2026-08-18, un vouch-bonus poste 4 fois dans #past-transaction.
  let polling = false;
  setInterval(async () => {
    if (polling) return;
    polling = true;
    try {
      try { await tail('payoutOffset', 'payout-results.jsonl', onPayouts); }
      catch (e) { console.warn('Suivi des paiements :', e.message); }
      try { await tail('txOffset', 'transactions.jsonl', onTransactions); }
      catch (e) { if (e.message !== 'channel absent') console.warn('Journal des transactions :', e.message); }
    } finally { polling = false; }
  }, POLL_MS);
}

// ---------- restart differe : l'agent ne redemarre jamais le bot lui-meme ----------
// Lecon du 2026-08-18 : l'agent Outmind a fait "pm2 restart discord-bot" en plein
// tour pour recharger le code qu'il venait d'editer, tuant le pont #outmind-ai qui
// portait sa propre reponse (3 reponses perdues dans la matinee). Sa consigne
// SOUL.md est desormais de toucher restart-bot.flag a la place : le bot attend la
// fin du tour admin en cours (busy ne retombe qu'apres l'envoi de la reponse),
// puis se redemarre seul. Barriere dans le code, pas dans le jugement du modele.
const RESTART_FLAG = path.join(__dirname, '..', 'restart-bot.flag');
setInterval(() => {
  if (!fs.existsSync(RESTART_FLAG)) return;
  if (adminAI.isBusy && adminAI.isBusy()) return;
  try { fs.unlinkSync(RESTART_FLAG); } catch (e) { return; }
  console.log('restart-bot.flag detecte, redemarrage dans 3 s');
  // re-verifier busy au dernier moment : un tour agent qui demarre pendant les
  // 3 s de grace serait tue en plein vol (vecu le 2026-08-18 a 16h45)
  setTimeout(() => {
    if (adminAI.isBusy && adminAI.isBusy()) {
      fs.writeFileSync(RESTART_FLAG, '');
      console.log('restart repousse, un tour agent vient de demarrer');
      return;
    }
    require('child_process').exec('pm2 restart discord-bot', () => {});
  }, 3000);
}, 15000);

// Dernier filet : discord.js remonte les rejets non geres en 'error' du Client,
// et un Client sans ecouteur 'error' fait tomber le process. On journalise et
// on continue : le panneau, le journal et les DM valent mieux vivants.
client.on('error', (e) => console.error('Erreur client Discord :', e.stack || e.message));
process.on('unhandledRejection', (e) => console.error('Rejet non gere :', (e && e.stack) || e));

client.login(TOKEN);
