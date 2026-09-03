// Panneau d'administration : agregation en lecture seule des journaux du
// casino, et file d'ordres pour le bridge.
//
// Rien ici n'ecrit dans balances.json ni dans bank-state.json. Les mouvements
// d'argent partent dans admin-orders.jsonl, que le bridge consomme a son tick :
// le grand livre garde ainsi un seul ecrivain a la fois. La seule exception est
// blacklist.json, que panel.js ecrit deja directement de la meme facon, et qui
// ne porte pas d'argent.

const fs = require('fs');
const path = require('path');

const BOT_DIR = process.env.BOT_DIR || path.join(__dirname, '..', 'mineflayer-bot');
const ORDERS_FILE = path.join(BOT_DIR, 'admin-orders.jsonl');
const BLACKLIST_FILE = path.join(BOT_DIR, 'blacklist.json');
const AUDIT_FILE = path.join(__dirname, 'admin-audit.jsonl');

function readJson(file, fallback) {
  try { return JSON.parse(fs.readFileSync(path.join(BOT_DIR, file), 'utf8').replace(/^\uFEFF/, '')); }
  catch { return fallback; }
}

function readLines(file) {
  try {
    return fs.readFileSync(path.join(BOT_DIR, file), 'utf8')
      .split('\n').filter(Boolean)
      .map(l => { try { return JSON.parse(l); } catch { return null; } })
      .filter(Boolean);
  } catch { return []; }
}

// ---------- identite ----------

// Le bridge fait un acces par cle exacte dans balances.json. Envoyer
// « sentiazz » quand la cle est « a player » ne leverait aucune erreur, ca
// creerait une SECONDE entree pour le meme joueur et l'argent partirait dans le
// vide. Toute action passe donc par cette resolution, et echoue si le pseudo
// est inconnu du casino.
function resolvePlayer(input) {
  const key = String(input || '').trim().toLowerCase();
  if (!key) return null;
  const sources = [
    Object.keys(readJson('balances.json', {})),
    Object.keys((readJson('bridge-state.json', {}).mirrored) || {}),
    readJson('bridge-state.json', {}).whitelisted || [],
  ];
  for (const list of sources) {
    const found = list.find(n => String(n).toLowerCase() === key);
    if (found) return found;
  }
  return null;
}

// ---------- dossier d'un joueur ----------

function history(player) {
  const key = String(player).toLowerCase();
  const mine = (o) => String(o.player || '').toLowerCase() === key;

  const tx = readLines('transactions.jsonl').filter(mine).map(o => ({ ...o, ms: new Date(o.at).getTime() }));
  const deltas = readLines('casino-deltas.jsonl').filter(mine);
  const payouts = readLines('payout-results.jsonl').filter(mine);

  const deposits = tx.filter(o => o.type === 'depot');
  const cashouts = tx.filter(o => o.type === 'retrait');
  const sum = (a, f) => a.reduce((s, o) => s + (f ? f(o) : o.amount), 0);

  let wagered = 0, won = 0, bestWin = 0, worstLoss = 0;
  const days = new Set();
  for (const d of deltas) {
    if (d.delta < 0) { wagered += -d.delta; worstLoss = Math.min(worstLoss, d.delta); }
    else { won += d.delta; bestWin = Math.max(bestWin, d.delta); }
    days.add(new Date(d.at).toLocaleDateString('en-CA', { timeZone: 'Europe/Paris' }));
  }

  return {
    deposits: { count: deposits.length, total: sum(deposits) },
    cashouts: { count: cashouts.length, total: sum(cashouts) },
    // positif = le joueur a mis plus qu'il n'a sorti, donc la maison est en
    // avance sur lui. C'est le seul chiffre qui dit si un joueur coute ou rapporte.
    net: sum(deposits) - sum(cashouts),
    game: {
      rounds: deltas.length,
      wagered, won,
      net: won - wagered,       // du point de vue du joueur
      bestWin, worstLoss,
      days: days.size,
      firstAt: deltas.length ? deltas[0].at : null,
      lastAt: deltas.length ? deltas[deltas.length - 1].at : null,
    },
    payouts: { count: payouts.length, paid: sum(payouts.filter(p => p.status === 'PAID')) },
    recent: [...tx].sort((a, b) => b.ms - a.ms).slice(0, 5),
  };
}

// ---------- etat general ----------

function overview() {
  const bridge = readJson('bridge-state.json', {});
  const balances = readJson('balances.json', {});
  const botStatus = readJson('bot-status.json', { inGame: false, at: 0 });
  const online = readJson('online.json', { players: [], at: 0 });
  const treasury = (readJson('bank-state.json', {}).treasury) || 0;
  const owed = Object.values(balances).reduce((s, v) => s + v, 0);
  const stats = readJson('casino-stats.json', {});
  return {
    treasury, owed,
    // taux de couverture : ce que la caisse peut rembourser sur ce qu'elle doit.
    // Sous 1, le casino ne tient pas si tout le monde retire le meme jour.
    coverage: owed > 0 ? treasury / owed : Infinity,
    profit: stats.profit || 0,
    rounds: stats.count || 0,
    botOnline: !!botStatus.inGame && (Date.now() - (botStatus.at || 0) < 2 * 60 * 1000),
    botAt: botStatus.at || 0,
    onlinePlayers: online.players || [],
    onlineAt: online.at || 0,
    whitelisted: bridge.whitelisted || [],
    blacklist: readJson('blacklist.json', []) || [],
    pendingOrders: pendingOrders(),
  };
}

// Ordres deposes mais pas encore avales par le bridge. Sert a dire « c'est
// parti mais pas encore applique » au lieu de laisser croire a un echec quand
// le bridge est arrete.
function pendingOrders() {
  const written = readLines('admin-orders.jsonl').length;
  const consumed = (readJson('bridge-state.json', {}).adminOffset) || 0;
  return Math.max(0, written - consumed);
}

// ---------- ecritures ----------

// Une ligne par ordre, terminee par un retour a la ligne : le bridge compte les
// lignes completes et ignore une derniere ligne partielle, donc un appendFile
// interrompu ne peut pas lui faire lire un ordre tronque.
function queueOrder(order) {
  const row = { at: Date.now(), ...order };
  fs.appendFileSync(ORDERS_FILE, JSON.stringify(row) + '\n');
  audit(row);
  return row;
}

// Journal d'audit cote Discord, separe de la file : la file est consommee et
// tronquable, l'audit ne l'est pas. Toute action d'argent y laisse qui l'a
// faite.
function audit(row) {
  try { fs.appendFileSync(AUDIT_FILE, JSON.stringify(row) + '\n'); }
  catch (e) { console.warn('audit admin :', e.message); }
}

function setBlacklist(player, add) {
  let list = [];
  try { list = JSON.parse(fs.readFileSync(BLACKLIST_FILE, 'utf8').replace(/^\uFEFF/, '')); } catch {}
  if (!Array.isArray(list)) list = [];
  const key = String(player).toLowerCase();
  const without = list.filter(n => String(n).toLowerCase() !== key);
  const next = add ? [...without, player] : without;
  fs.writeFileSync(BLACKLIST_FILE, JSON.stringify(next, null, 2));
  return next;
}

module.exports = { resolvePlayer, history, overview, queueOrder, audit, setBlacklist, readJson, readLines };
