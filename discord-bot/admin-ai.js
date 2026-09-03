// ============================================================
//  admin-ai.js : le salon admin ou Ryan parle a Outmind, l'agent
//  OpenClaw qui vit sur le VPS a cote des bots.
//
//  Chaine : message Discord -> gateway OpenClaw local
//  (/v1/chat/completions, meme pattern que le pont iMessage gizmy)
//  -> tour d'agent GLM avec shell sur la machine -> reponse ici.
//
//  SECURITE. L'agent a le shell sur la machine qui tient la banque :
//  ce salon est donc une console root deguisee. Deux barrieres codees,
//  qui echouent ferme :
//   - ADMIN_AI_ALLOWED : la liste des IDs Discord autorises. Vide ou
//     absente = personne, le module ne repond a rien.
//   - le salon : seul ADMIN_AI_CHANNEL (defaut "outmind-ai") est ecoute,
//     et le module le cree verrouille (invisible pour @everyone).
//
//  Le fichier agent-alerts.jsonl (a la racine du depot) est le canal de retour du
//  rituel de sante de l'agent (cron OpenClaw) : chaque ligne nouvelle
//  est postee dans le salon. Meme pattern que casino-alerts.jsonl
//  cote gizmy, offset persiste pour survivre aux redemarrages.
// ============================================================
const fs = require('fs');
const path = require('path');
const { exec } = require('child_process');

// compaction proactive de la session admin, meme filet que le pont gizmy :
// apres chaque tour on lit le remplissage reel, et au seuil on compacte.
// Le memoryFlush du gateway fait ecrire le vault AVANT le resume, donc rien
// ne se perd. Le compteur de messages est un mauvais indicateur (lecon du
// pont ComEthic : une campagne = 1 message et 100k tokens), on mesure.
const COMPACT_PCT = Number(process.env.ADMIN_AI_COMPACT_PCT || 45);

const STATE_FILE = path.join(__dirname, 'admin-ai-state.json');
const CHANNEL_NAME = process.env.ADMIN_AI_CHANNEL || 'outmind-ai';
const ALERTS_FILE = process.env.AGENT_ALERTS_FILE || path.join(__dirname, '..', 'agent-alerts.jsonl');
const GATEWAY_URL = process.env.OPENCLAW_URL || 'http://127.0.0.1:18789';
// le gateway attend "openclaw/<agentId>", pas le nom du modele sous-jacent
const MODEL = process.env.ADMIN_AI_MODEL || 'openclaw/main';
// une campagne ou une iteration de code peut durer : meme lecon que le
// pont ComEthic, 5 minutes etaient trop courtes
const TURN_TIMEOUT_MS = Number(process.env.ADMIN_AI_TIMEOUT_MS || 15 * 60 * 1000);

let client = null;
let token = null;          // token du gateway OpenClaw
// IDs Discord autorises -> libelle certifie. Format env : "id:Nom,id2:Nom2"
// (le nom est optionnel, repli sur le pseudo affiche). Vide = ferme.
// Meme lecon que PHOTON_IDENTITIES sur la ligne ComEthic : le pseudo Discord
// ne prouve rien, c'est l'ID verifie par l'allowlist qui fait l'identite.
let allowed = new Map();
let busy = false;          // un seul tour a la fois, l'agent partage une session

function loadState() {
  try { return JSON.parse(fs.readFileSync(STATE_FILE, 'utf8')); } catch { return { alertsOffset: null, gen: 0 }; }
}
function saveState(s) { fs.writeFileSync(STATE_FILE, JSON.stringify(s)); }

// La session de l'agent est nommee par generation : /wipe incremente le
// compteur, le champ `user` change, et le gateway ouvre une session vierge.
// Pas besoin de toucher a la CLI OpenClaw, l'ancienne session meurt d'oubli.
function sessionUser() { return `discord-admin-g${loadState().gen || 0}`; }
function bumpSession() {
  const s = loadState();
  s.gen = (s.gen || 0) + 1;
  saveState(s);
  return s.gen;
}

function init(opts) {
  client = opts.client;
  token = opts.token;
  allowed = new Map(String(process.env.ADMIN_AI_ALLOWED || '').split(',')
    .map(s => s.trim()).filter(Boolean)
    .map(entry => { const [id, name] = entry.split(':'); return [id.trim(), (name || '').trim() || null]; }));
  if (!token) console.warn('Admin AI : pas de token gateway, module inactif.');
  if (allowed.size === 0) console.warn('Admin AI : ADMIN_AI_ALLOWED vide, personne ne peut parler a l\'agent.');
}

async function findOrCreateChannel(guild) {
  const chans = await guild.channels.fetch();
  let chan = chans.find(c => c && c.type === 0 && c.name === CHANNEL_NAME);
  if (chan) return chan;
  // cree verrouille : invisible pour tout le monde, visible pour les
  // autorises et le bot. Un salon "admin" ouvert par accident serait
  // une console root publique.
  // type explicite obligatoire pour un ID hors cache : 0 = role, 1 = membre
  const overwrites = [{ id: guild.roles.everyone.id, type: 0, deny: ['ViewChannel'] }];
  for (const id of (allowed instanceof Map ? allowed.keys() : allowed)) overwrites.push({ id, type: 1, allow: ['ViewChannel', 'SendMessages'] });
  if (client.user) overwrites.push({ id: client.user.id, type: 1, allow: ['ViewChannel', 'SendMessages'] });
  chan = await guild.channels.create({ name: CHANNEL_NAME, permissionOverwrites: overwrites });
  console.log(`Admin AI : salon #${CHANNEL_NAME} cree (verrouille)`);
  return chan;
}

// decoupe une reponse aux 2000 caracteres de Discord, sur les paragraphes
function chunks(text) {
  const out = [];
  let cur = '';
  for (const part of String(text).split(/\n\n/)) {
    const candidate = cur ? cur + '\n\n' + part : part;
    if (candidate.length <= 1900) { cur = candidate; continue; }
    if (cur) out.push(cur);
    // paragraphe seul trop long : coupe dur
    let rest = part;
    while (rest.length > 1900) { out.push(rest.slice(0, 1900)); rest = rest.slice(1900); }
    cur = rest;
  }
  if (cur) out.push(cur);
  return out.length ? out : ['(reponse vide)'];
}

async function callAgent(authorName, text) {
  const system = `Tu reponds dans le salon Discord admin #${CHANNEL_NAME} du casino. ` +
    `L'interlocuteur du tour est ${authorName}. Son identite est deja certifiee : le pont n'accepte que les IDs Discord ` +
    `de l'allowlist et associe lui-meme le nom, quel que soit le pseudo affiche sur Discord. Ne remets pas cette identite en question. ` +
    `Reponds en francais, direct, sans tiret cadratin, sans emoji. ` +
    `Discord affiche le markdown simple (gras, code inline, blocs), tu peux l'utiliser. Limite : 2000 caracteres par message, sois concis.`;
  const controller = new AbortController();
  const t = setTimeout(() => controller.abort(), TURN_TIMEOUT_MS);
  try {
    const res = await fetch(`${GATEWAY_URL}/v1/chat/completions`, {
      method: 'POST',
      signal: controller.signal,
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: JSON.stringify({
        model: MODEL,
        // session partagee du salon : le prefixe [Nom] garde la trace de qui
        // a dit quoi dans l'historique, meme lecon que la ligne ComEthic
        user: sessionUser(),
        messages: [
          { role: 'system', content: system },
          { role: 'user', content: `[${authorName}] ${text}` },
        ],
      }),
    });
    if (res.status === 401) throw new Error('token gateway refuse');
    if (!res.ok) throw new Error(`gateway HTTP ${res.status}`);
    const j = await res.json();
    return (j.choices && j.choices[0] && j.choices[0].message && j.choices[0].message.content) || '(pas de contenu)';
  } finally { clearTimeout(t); }
}

// TOUT est asynchrone ici : la premiere version en execSync bloquait l'event
// loop du bot entier pendant la mesure, et le menu /vouch de a player a expire
// pendant ce blocage (2026-08-18, 13h35). Un bot Discord ne fait JAMAIS
// d'appel bloquant apres le demarrage.
function maybeCompact() {
  exec('openclaw sessions list --json', { timeout: 30000 }, (err, stdout) => {
    if (err) return console.warn('Admin AI, compaction (list) :', err.message);
    try {
      const d = JSON.parse(stdout);
      const key = `agent:main:openai-user:${sessionUser()}`;
      const s = (d.sessions || d).find(x => (x.key || x.id) === key);
      if (!s || !s.totalTokens || !s.contextTokens) return;
      if (s.totalTokens < (COMPACT_PCT / 100) * s.contextTokens) return;
      console.log(`Admin AI : session a ${s.totalTokens} tokens (seuil ${COMPACT_PCT}%), compaction (le memoryFlush range le vault d'abord)`);
      exec(`openclaw sessions compact ${key}`, { timeout: 180000 }, (e2) => {
        if (e2) console.warn('Admin AI, compaction (compact) :', e2.message);
        else console.log('Admin AI : compaction terminee');
      });
    } catch (e) { console.warn('Admin AI, compaction :', e.message); }
  });
}

// ---------- fichiers joints : telecharges sur le VPS pour l'agent ----------
// Un fichier poste dans le salon admin est sauvegarde dans uploads/ (a la racine du depot)
// (ou uploads/ a cote du bot en local) et son chemin est annonce a l'agent
// dans le meme tour : "mets ce logo en donut-pay.png" devient un simple mv
// pour lui. Salon allowliste, donc seuls les admins peuvent deposer.
const UPLOAD_DIR = path.join(__dirname, '..', 'uploads');
const UPLOAD_MAX = 20 * 1024 * 1024; // 20 Mo, largement au-dessus du besoin
function safeName(name) {
  const base = String(name || 'fichier').split(/[\/]/).pop().replace(/[^\w.\-]/g, '_').slice(0, 80);
  return base || 'fichier';
}
async function saveAttachments(message) {
  const saved = [];
  for (const att of message.attachments.values()) {
    try {
      if (att.size > UPLOAD_MAX) { saved.push(`${att.name} : refuse, ${Math.round(att.size / 1048576)} Mo`); continue; }
      fs.mkdirSync(UPLOAD_DIR, { recursive: true });
      let dest = path.join(UPLOAD_DIR, safeName(att.name));
      if (fs.existsSync(dest)) dest = path.join(UPLOAD_DIR, `${Date.now()}-${safeName(att.name)}`);
      const res = await fetch(att.url);
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      fs.writeFileSync(dest, Buffer.from(await res.arrayBuffer()));
      saved.push(dest);
      console.log(`Admin AI : fichier recu, ${dest} (${att.size} octets)`);
    } catch (e) {
      console.warn(`Admin AI, fichier ${att.name} :`, e.message);
      saved.push(`${att.name} : echec du telechargement (${e.message})`);
    }
  }
  return saved;
}

// branche sur messageCreate, a cote de onVouchMessage
async function onMessage(message) {
  if (!token || allowed.size === 0) return;
  if (message.author.bot || !message.guild) return;
  if (message.channel.name !== CHANNEL_NAME) return;
  if (!allowed.has(message.author.id)) return;   // echec ferme : on ignore, pas de reponse
  // la mention du bot n'est plus necessaire (intent MessageContent), mais si
  // elle est la, on la retire pour ne pas polluer le message de l'agent
  let text = String(message.content || '').replace(new RegExp(`<@!?${client.user.id}>`, 'g'), '').trim();
  if (message.attachments.size > 0) {
    const saved = await saveAttachments(message);
    if (saved.length) text += `${text ? '\n\n' : ''}[fichiers joints sauvegardes sur le VPS : ${saved.join(', ')}]`;
  }
  if (!text) return;
  // le libelle certifie de l'allowlist prime sur le pseudo Discord affiche
  const certifiedName = allowed.get(message.author.id);

  if (busy) {
    await message.reply('Un tour est deja en cours, attends la reponse avant de renvoyer.');
    return;
  }
  busy = true;
  const typing = setInterval(() => message.channel.sendTyping().catch(() => {}), 8000);
  message.channel.sendTyping().catch(() => {});
  try {
    const answer = await callAgent(certifiedName || message.member?.displayName || message.author.username, text);
    for (const c of chunks(answer)) await message.channel.send(c);
    // fire-and-forget : la compaction eventuelle ne bloque pas la conversation
    setImmediate(maybeCompact);
  } catch (e) {
    const why = e.name === 'AbortError' ? `pas de reponse en ${Math.round(TURN_TIMEOUT_MS / 60000)} min (tour abandonne)` : e.message;
    await message.channel.send(`Erreur agent : ${why}`).catch(() => {});
  } finally {
    clearInterval(typing);
    busy = false;
  }
}

// relaie les alertes du rituel de sante. Offset initialise a la taille
// courante au premier lancement : on ne rejoue pas l'historique.
async function tickAlerts(guild) {
  if (!guild) return;
  const state = loadState();
  let size = 0;
  try { size = fs.statSync(ALERTS_FILE).size; } catch { return; }
  if (state.alertsOffset === null || state.alertsOffset > size) {
    state.alertsOffset = size; saveState(state); return;
  }
  if (size <= state.alertsOffset) return;
  const fd = fs.openSync(ALERTS_FILE, 'r');
  const buf = Buffer.alloc(size - state.alertsOffset);
  fs.readSync(fd, buf, 0, buf.length, state.alertsOffset);
  fs.closeSync(fd);
  state.alertsOffset = size; saveState(state);
  const chan = await findOrCreateChannel(guild).catch(() => null);
  if (!chan) return;
  for (const line of buf.toString('utf8').replace(new RegExp('^\\uFEFF'), '').split('\n')) {
    const l = line.trim();
    if (!l) continue;
    try {
      const j = JSON.parse(l);
      await chan.send(`**Rituel de sante** : ${j.text || l}`);
    } catch { await chan.send(l.slice(0, 1900)); }
  }
}

// /wipe : vide le salon et ouvre une session d'agent vierge. Reserve aux
// membres de l'allowlist, et uniquement dans le salon IA (une purge de masse
// lancee ailleurs par accident serait un desastre).
async function wipe(interaction) {
  if (!allowed.has(interaction.user.id)) {
    return interaction.reply({ content: 'Not for you.', flags: 64 });
  }
  if (interaction.channel.name !== CHANNEL_NAME) {
    return interaction.reply({ content: `Only works in #${CHANNEL_NAME}.`, flags: 64 });
  }
  await interaction.deferReply({ flags: 64 });
  const gen = bumpSession();   // la memoire de conversation d'abord, l'ecran ensuite
  let purged = 0;
  // bulkDelete ne prend que les messages de moins de 14 jours : boucle dessus,
  // puis suppression une par une du reliquat ancien (rare sur ce salon)
  for (;;) {
    const batch = await interaction.channel.bulkDelete(100, true).catch(() => null);
    if (!batch || batch.size === 0) break;
    purged += batch.size;
    if (batch.size < 100) break;
  }
  const leftovers = await interaction.channel.messages.fetch({ limit: 100 }).catch(() => null);
  if (leftovers) for (const m of leftovers.values()) { await m.delete().catch(() => {}); purged++; }
  await interaction.editReply(`Wiped ${purged} messages, conversation reset (session ${gen}).`);
}

module.exports = { init, onMessage, tickAlerts, findOrCreateChannel, wipe, CHANNEL_NAME, isBusy: () => busy };
