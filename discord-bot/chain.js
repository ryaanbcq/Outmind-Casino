// ============================================================
//  chain.js : "Double it or leave it", jeu communautaire Discord
//
//  Regles (corrigees le 2026-08-19, alignees sur l'enonce de Ryan) :
//   - Un joueur ouvre la chaine avec une mise M (bouton du board ->
//     modal, ou /chain start). Confirmation ephemere, puis sa mise
//     est debitee et il tire : DOUBLE_CHANCE de doubler. Gagne : 2M
//     credites, chaine finie. Perd : la chaine reste ouverte, mise
//     suivante 2M.
//   - Chaque "Double it" : confirmation ephemere, le joueur mise la
//     mise courante, tire, gagne 2x (fin) ou perd (mise doublee).
//   - Mise suivante au-dessus de MAX_BET (50M) : la maison garde le
//     pot. Chaine sans preneur pendant CHAIN_TTL_MS (6h) : pareil.
//
//  UX (Ryan, 2026-08-19, 2e refonte) : UN board permanent, edite sur
//  place. Chaque clic ouvre une CONFIRMATION EPHEMERE qui disparait
//  des que le joueur a choisi. Chaque tirage joue poste un embed
//  public court ("X put up $S and lost, next stake $2S") portant le
//  bouton Double it ; l'event precedent perd son bouton (un seul
//  bouton actif dans le fil, plus celui du board). A CHAQUE FIN de
//  chaine (victoire, plafond, expiration), TOUS les events sont
//  supprimes : il ne reste que le board, qui porte le denouement en
//  "Last chain". Le gagnant recoit son resultat en ephemere.
//
//  MATHS, publiees sur le board : p = 0.45 par tirage, EV joueur
//  = (2p-1) x mise = -10% de chaque mise. Le "pot garde" n'est pas
//  un gain en plus, c'est la somme des mises deja perdues.
//  CHAIN_DOUBLE_CHANCE dans l'env pour changer p (0.485 -> edge 3%).
//
//  DOCTRINE : les probabilites vivent dans CE code (crypto.randomInt,
//  pas Math.random), pas dans un modele. L'argent passe UNIQUEMENT
//  par admin-orders.jsonl (debit/credit signes chain:<tag>).
// ============================================================
const { EmbedBuilder, ActionRowBuilder, ButtonBuilder, ButtonStyle } = require('discord.js');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const CHAIN_CHANNEL = process.env.CHAIN_CHANNEL || 'leave-it-or-double-it';
const CHAIN_FILE = path.join(__dirname, '..', 'chain-state.json');
const DOUBLE_CHANCE = Math.min(0.6, Math.max(0.05, Number(process.env.CHAIN_DOUBLE_CHANCE || 0.45)));
const MIN_BET = 100000;               // 100k
const MAX_BET = 50000000;             // 50M : au-dela, la maison garde
const MAX_START = 25000000;           // ouverture plafonnee
const CHAIN_TTL_MS = Number(process.env.CHAIN_TTL_MS || 6 * 3600 * 1000);
const PENDING_TTL_MS = 60000;         // confirmation ephemere : 60 s pour se decider
const PCT = Math.round(DOUBLE_CHANCE * 100);

let deps = null; // { client, guildId, linkOf, withdrawableFor, queueOrder, resolvePlayer }
let playing = false;                  // verrou anti-course : un tirage a la fois
const pendings = new Map();           // token -> {userId, tag, kind:'start'|'double', amount, at}

function load() {
  try { return JSON.parse(fs.readFileSync(CHAIN_FILE, 'utf8')); } catch {}
  return { chain: null, history: [], board: null, eventIds: [] };
}
function save(s) { fs.writeFileSync(CHAIN_FILE, JSON.stringify(s, null, 2)); }
function fmt(n) { return '$' + Math.floor(n).toLocaleString('en-US'); }
// tirage au crypto, pas Math.random : auditable et non biaisable par l'etat du process
function roll() { return crypto.randomInt(100000) < Math.round(DOUBLE_CHANCE * 100000); }
function findChan() {
  if (!deps || !deps.client) return null;
  return deps.client.channels.cache.find(c => c.name === CHAIN_CHANNEL && typeof c.isTextBased === 'function' && c.isTextBased()) || null;
}

// parse "10m", "10M", "500k", "2500000" : entier en dollars ou null.
function parseBet(raw) {
  const m = String(raw || '').trim().toLowerCase().replace(/,/g, '').match(/^(\d+(?:\.\d+)?)([kmb])?$/);
  if (!m) return null;
  const n = parseFloat(m[1]);
  if (!isFinite(n)) return null;
  const mult = { k: 1e3, m: 1e6, b: 1e9 }[m[2] || ''] || 1;
  const v = Math.floor(n * mult);
  return v >= 1 ? v : null;
}

function potOf(chain) { return chain.players.reduce((a, p) => a + p.bet, 0); }

// chaine sans preneur trop longtemps : la maison ramasse. Retourne true si
// l'etat a change (l'appelant doit save, nettoyer les events, refresh).
function expireIfDue(s) {
  if (!s.chain) return false;
  const last = s.chain.lastMoveAt || s.chain.startedAt || 0;
  if (Date.now() - last < CHAIN_TTL_MS) return false;
  s.history.push({ endedAt: Date.now(), winner: 'house', how: 'expired', steps: s.chain.step, pot: potOf(s.chain) });
  s.chain = null;
  return true;
}

function lastLine(s) {
  const h = s.history[s.history.length - 1];
  if (!h) return null;
  const when = '<t:' + Math.floor(h.endedAt / 1000) + ':R>';
  if (h.winner === 'house') return 'The house kept ' + fmt(h.pot) + (h.how === 'expired' ? ' (chain expired) ' : ' (limit reached) ') + when;
  return '**' + h.winner + '** doubled ' + fmt(h.won || h.pot) + ' ' + when;
}

// ---------- le board : l'unique message permanent du salon ----------
function boardPayload(s) {
  const embed = new EmbedBuilder().setColor(0xa18cd1).setTitle('Double it or leave it')
    .setFooter({ text: 'Outmind Casino | one board, your rolls stay private until you play' });
  const row = new ActionRowBuilder();
  if (s.chain) {
    const expires = Math.floor(((s.chain.lastMoveAt || s.chain.startedAt) + CHAIN_TTL_MS) / 1000);
    embed.setDescription('A chain is live. Put up **' + fmt(s.chain.stake) + '**: ' + PCT + '% to walk away with **' + fmt(s.chain.stake * 2) + '**, paid instantly. Lose and the next stake doubles.')
      .addFields(
        { name: 'Current stake', value: fmt(s.chain.stake), inline: true },
        { name: 'Pot so far', value: fmt(potOf(s.chain)), inline: true },
        { name: 'Chain', value: s.chain.players.map(p => p.player + ' ' + fmt(p.bet)).join(' -> ').slice(0, 1000), inline: false },
        { name: 'Rules', value: PCT + '% to double, published rule. Above ' + fmt(MAX_BET) + ' the house keeps the pot. No taker before <t:' + expires + ':R>: the house keeps it too.', inline: false },
      );
    row.addComponents(new ButtonBuilder().setCustomId('oc_chain:double').setLabel('Double it (' + fmt(s.chain.stake) + ')').setStyle(ButtonStyle.Danger));
  } else {
    embed.setDescription('No chain running. Open one: bet ' + fmt(MIN_BET) + ' to ' + fmt(MAX_START) + ', you roll instantly with ' + PCT + '% to double your bet. Lose and the next player must put up double, and so on. Above ' + fmt(MAX_BET) + ' the house keeps the whole pot.');
    const ll = lastLine(s);
    if (ll) embed.addFields({ name: 'Last chain', value: ll, inline: false });
    embed.addFields({ name: 'House edge', value: PCT + '% to double means the house keeps ' + (100 - 2 * PCT) + '% of every stake on average. Published, no hidden tricks.', inline: false });
    row.addComponents(new ButtonBuilder().setCustomId('oc_chain:open').setLabel('Open a chain').setStyle(ButtonStyle.Success));
  }
  return { embeds: [embed], components: [row] };
}

async function refreshBoard() {
  const s = load();
  if (expireIfDue(s)) { await clearEvents(s); save(s); }
  const chan = findChan();
  if (!chan) return;
  const payload = boardPayload(s);
  if (s.board && s.board.messageId) {
    const msg = await chan.messages.fetch(s.board.messageId).catch(() => null);
    if (msg) { await msg.edit(payload).catch(() => {}); return; }
  }
  // premier passage (ou board perdu) : salon remis a neuf, best effort
  // (bulkDelete ignore ce qui a plus de 14 jours)
  try { const old = await chan.messages.fetch({ limit: 50 }); await chan.bulkDelete(old, true); } catch {}
  const sent = await chan.send(payload).catch(() => null);
  if (sent) { const s2 = load(); s2.board = { channelId: chan.id, messageId: sent.id }; save(s2); }
}

// sweep periodique : n'edite le board que si une chaine vient d'expirer
async function tick() {
  const s = load();
  if (expireIfDue(s)) { await clearEvents(s); save(s); await refreshBoard(); return; }
  if (!s.board) await refreshBoard();   // filet du boot (cache des salons vide au ready)
}

// ---------- events publics : un embed par tirage joue, purge en fin ----------
// L'event precedent perd son bouton : un seul Double it actif dans le fil.
async function postEvent(s, color, text, stake) {
  const chan = findChan();
  if (!chan) return;
  s.eventIds = s.eventIds || [];
  const prev = s.eventIds[s.eventIds.length - 1];
  if (prev) {
    const m = await chan.messages.fetch(prev).catch(() => null);
    if (m) await m.edit({ components: [] }).catch(() => {});
  }
  const embed = new EmbedBuilder().setColor(color).setDescription(text)
    .setFooter({ text: PCT + '% to double | Outmind Casino' });
  const row = new ActionRowBuilder().addComponents(
    new ButtonBuilder().setCustomId('oc_chain:double').setLabel('Double it (' + fmt(stake) + ')').setStyle(ButtonStyle.Danger));
  const sent = await chan.send({ embeds: [embed], components: [row] }).catch(() => null);
  if (sent) s.eventIds.push(sent.id);
}

// fin de chaine : tous les events disparaissent, il ne reste que le board
async function clearEvents(s) {
  const chan = findChan();
  if (!chan || !s.eventIds || !s.eventIds.length) return;
  for (const id of s.eventIds.splice(0)) await chan.messages.delete(id).catch(() => {});
}

// ---------- phase 1 : demandes, AUCUN argent ne bouge ici ----------
function makePending(userId, tag, kind, amount) {
  for (const [t, p] of pendings) {
    if (p.userId === userId || Date.now() - p.at > PENDING_TTL_MS) pendings.delete(t);
  }
  const token = crypto.randomBytes(6).toString('hex');
  pendings.set(token, { userId, tag, kind, amount, at: Date.now() });
  return token;
}

function confirmPayload(kind, amount, token) {
  const embed = new EmbedBuilder().setColor(0xa18cd1)
    .setTitle(kind === 'start' ? 'Open the chain?' : 'Take the chain?')
    .setDescription('You are about to put up **' + fmt(amount) + '**: ' + PCT + '% to double it into **' + fmt(amount * 2) + '**, paid instantly. Lose and the next stake is **' + fmt(amount * 2) + '**.')
    .setFooter({ text: 'Only you see this. Nothing is taken until you confirm.' });
  const row = new ActionRowBuilder().addComponents(
    new ButtonBuilder().setCustomId('oc_chain:go:' + token).setLabel('Confirm (' + fmt(amount) + ')').setStyle(ButtonStyle.Success),
    new ButtonBuilder().setCustomId('oc_chain:no:' + token).setLabel('Cancel').setStyle(ButtonStyle.Secondary),
  );
  return { embeds: [embed], components: [row] };
}

async function askStart(interaction) {
  const link = deps.linkOf(interaction.user.id);
  if (!link) return { content: 'Link your account first: `/verify` in game on prestigiasmp.net, then `/verify` here.' };
  const raw = interaction.options.getString ? interaction.options.getString('bet') : interaction.options.bet;
  const amount = parseBet(raw);
  if (!amount) return { content: 'I could not read that bet. Try `10m`, `500k` or a full number.' };
  if (amount < MIN_BET) return { content: 'Minimum bet: ' + fmt(MIN_BET) + '.' };
  if (amount > MAX_START) return { content: 'Opening bet is capped at ' + fmt(MAX_START) + '.' };

  const s = load();
  expireIfDue(s);
  if (s.chain) return { content: 'A chain is already live at **' + fmt(s.chain.stake) + '**. Hit **Double it** to take it.' };

  const info = await deps.withdrawableFor(link.player);
  if (info.blacklisted) return { content: 'Your account is not allowed to play.' };
  if (amount > info.withdrawable) return { content: 'You can bet at most ' + fmt(info.withdrawable) + ' (balance minus the welcome reserve).' };

  return confirmPayload('start', amount, makePending(interaction.user.id, interaction.user.tag, 'start', amount));
}

async function askDouble(interaction) {
  const link = deps.linkOf(interaction.user.id);
  if (!link) return { content: 'Link your account first (`/verify` in game, then here).' };
  const s = load();
  if (expireIfDue(s)) {
    await clearEvents(s); save(s); await refreshBoard();
    return { content: 'That chain just expired, the house kept the pot. Open a new one from the board.' };
  }
  if (!s.chain) return { content: 'No chain running. Open one from the board.' };

  const stake = s.chain.stake;
  const info = await deps.withdrawableFor(link.player);
  if (info.blacklisted) return { content: 'Your account is not allowed to play.' };
  if (stake > info.withdrawable) return { content: 'The stake is **' + fmt(stake) + '** but you can only put up ' + fmt(info.withdrawable) + '.' };

  return confirmPayload('double', stake, makePending(interaction.user.id, interaction.user.tag, 'double', stake));
}

function cancel(token, userId) {
  const p = pendings.get(token);
  if (p && p.userId === userId) pendings.delete(token);
}

// ---------- phase 2 : confirmation, debit, tirage ----------
// Retourne null (rien a dire, les events publics parlent) ou { content }
// a montrer en ephemere (erreur, ou resultat perso du gagnant).
async function confirm(interaction, token) {
  if (playing) return { content: 'Someone is rolling right now, try again in a second.' };
  playing = true;
  try { return await confirmLocked(interaction, token); } finally { playing = false; }
}

async function confirmLocked(interaction, token) {
  const p = pendings.get(token);
  if (!p || Date.now() - p.at > PENDING_TTL_MS) { pendings.delete(token); return { content: 'This confirmation expired, start again from the board.' }; }
  if (p.userId !== interaction.user.id) return { content: 'Not your confirmation.' };
  pendings.delete(token);

  const link = deps.linkOf(p.userId);
  if (!link) return { content: 'Link your account first (`/verify` in game, then here).' };

  const s = load();
  if (expireIfDue(s)) {
    await clearEvents(s); save(s); await refreshBoard();
    return { content: 'That chain just expired, the house kept the pot. Open a new one from the board.' };
  }
  // l'etat a pu bouger entre la demande et le clic : on revalide tout
  if (p.kind === 'start') {
    if (s.chain) return { content: 'A chain opened in the meantime. Hit Double it on the board instead.' };
  } else {
    if (!s.chain) return { content: 'The chain just ended, check the board.' };
    if (s.chain.stake !== p.amount) return { content: 'The chain moved while you decided: the stake is now ' + fmt(s.chain.stake) + '. Check the board.' };
  }
  const info = await deps.withdrawableFor(link.player);
  if (info.blacklisted) return { content: 'Your account is not allowed to play.' };
  if (p.amount > info.withdrawable) return { content: 'You can only put up ' + fmt(info.withdrawable) + ' right now.' };
  if (deps.hasLock && deps.hasLock(link.player)) return { content: 'You have a cash out or transfer going through. Wait a minute, then try again.' };

  if (deps.lockPlayer) deps.lockPlayer(link.player); // le debit part au prochain tick du bridge
  deps.queueOrder({
    kind: 'debit', player: link.player, amount: p.amount,
    reason: p.kind === 'start' ? 'chain game: opening bet' : 'chain game: double attempt',
    by: 'chain:' + p.tag, byId: p.userId,
  });
  const won = roll();
  const player = link.player;

  if (p.kind === 'start') {
    if (won) {
      const payout = p.amount * 2;
      deps.queueOrder({ kind: 'credit', player, amount: payout, reason: 'chain game: doubled, winner', by: 'chain:' + p.tag, byId: p.userId });
      s.history.push({ endedAt: Date.now(), winner: player, won: payout, steps: 1, pot: p.amount });
      await clearEvents(s); save(s); await refreshBoard();
      return { content: 'DOUBLED. You opened with ' + fmt(p.amount) + ' and it paid instantly: **' + fmt(payout) + '** credited. The chain is already over.' };
    }
    s.chain = { stake: p.amount * 2, players: [{ player, bet: p.amount }], startedAt: Date.now(), lastMoveAt: Date.now(), step: 1 };
    await postEvent(s, 0xe05c5c, '**' + player + '** opened with **' + fmt(p.amount) + '** and lost the roll. Next stake: **' + fmt(s.chain.stake) + '**.', s.chain.stake);
    save(s); await refreshBoard();
    return null;
  }

  // double
  s.chain.players.push({ player, bet: p.amount });
  s.chain.step += 1;
  s.chain.lastMoveAt = Date.now();
  const pot = potOf(s.chain);

  if (won) {
    const payout = p.amount * 2;
    deps.queueOrder({ kind: 'credit', player, amount: payout, reason: 'chain game: doubled, winner', by: 'chain:' + p.tag, byId: p.userId });
    s.history.push({ endedAt: Date.now(), winner: player, won: payout, steps: s.chain.step, pot });
    s.chain = null;
    await clearEvents(s); save(s); await refreshBoard();
    return { content: 'DOUBLED. You put up ' + fmt(p.amount) + ' and walked away with **' + fmt(payout) + '**, credited. Pot burned for the others: ' + fmt(Math.max(0, pot - payout)) + '.' };
  }

  if (p.amount * 2 > MAX_BET) {
    s.history.push({ endedAt: Date.now(), winner: 'house', how: 'limit', steps: s.chain.step, pot });
    s.chain = null;
    await clearEvents(s); save(s); await refreshBoard();
    return { content: 'You put up ' + fmt(p.amount) + ' and lost. The next stake would break the ' + fmt(MAX_BET) + ' limit: the chain is over, the house keeps the pot (' + fmt(pot) + ').' };
  }

  s.chain.stake = p.amount * 2;
  await postEvent(s, 0xe05c5c, '**' + player + '** put up **' + fmt(p.amount) + '** and lost the roll. Next stake: **' + fmt(s.chain.stake) + '**.', s.chain.stake);
  save(s); await refreshBoard();
  return null;
}

async function status() {
  const s = load();
  expireIfDue(s);
  if (!s.chain) {
    const ll = lastLine(s);
    return { text: ll ? 'No chain running. Last one: ' + ll.replace(/\*\*/g, '') : 'No chain running yet.' };
  }
  return { text: 'Chain live: next stake ' + fmt(s.chain.stake) + ', pot ' + fmt(potOf(s.chain)) + ', ' + s.chain.players.length + ' player(s) in.' };
}

module.exports = {
  init(d) { deps = d; },
  CHAIN_CHANNEL,
  askStart, askDouble, confirm, cancel, status, parseBet, refreshBoard, tick,
};
