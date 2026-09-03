// ============================================================
//  agent-post.js : la plume du bot pour l'agent Outmind du VPS.
//
//  L'agent n'a pas de token Discord et n'en aura jamais : il ecrit des
//  lignes JSON dans agent-outbox.jsonl, et c'est le bot (Outmind APP)
//  qui les poste. Meme pattern outbox que admin-orders.jsonl pour les
//  paiements et agent-alerts.jsonl pour le rituel de sante : l'agent
//  produit, le code execute, la frontiere reste dans le code.
//
//  Une ligne = un message :
//  {
//    "channel": "outmind-ai",            // nom du salon, defaut outmind-ai
//    "delete": "last",                   // optionnel : supprime le dernier
//                                          // message du BOT dans ce salon
//    "content": "texte au dessus",       // optionnel
//    "embeds": [{                        // optionnel, max 10
//      "title": "...", "description": "...",
//      "color": "A18CD1",                // hex ou nombre, defaut violet maison
//      "fields": [{"name":"...", "value":"...", "inline":false}],
//      "footer": "...", "thumbnail": "url", "image": "url",
//      "timestamp": true
//    }],
//    "buttons": [{                       // optionnel, max 25 (5 lignes de 5)
//      "label": "...", "style": "primary|secondary|success|danger|link",
//      "url": "https://...",             // style link
//      "id": "mon-action"                // sinon : customId oc_agent:mon-action
//    }]
//  }
//
//  Les clics sur les boutons de l'agent partent dans agent-clicks.jsonl,
//  que l'agent lit quand il veut savoir qui a repondu quoi.
// ============================================================
const fs = require('fs');
const path = require('path');
const { EmbedBuilder, ActionRowBuilder, ButtonBuilder, ButtonStyle, MessageFlags } = require('discord.js');

const OUTBOX = process.env.AGENT_OUTBOX_FILE || path.join(__dirname, '..', 'agent-outbox.jsonl');
const CLICKS = process.env.AGENT_CLICKS_FILE || path.join(__dirname, '..', 'agent-clicks.jsonl');
const STATE_FILE = path.join(__dirname, 'agent-post-state.json');
const DEFAULT_CHANNEL = 'outmind-ai';
const BRAND_COLOR = 0xa18cd1;

// Dossiers ou l'agent a le droit de puiser une image a joindre, et rien
// d'autre. BARRIERE : sans cette allowlist, un chemin glisse dans thumbnail
// (par l'agent ou par une injection via le digest du guichet) pourrait faire
// poster un .env en public. On n'autorise QUE des images, et QUE depuis ces
// dossiers, jamais un chemin absolu arbitraire ni de remontee '..'.
const ASSET_DIRS = [
  path.join(__dirname, '..', 'uploads'),
  path.join(__dirname, 'assets'),
  __dirname,
];
const IMG_EXT = new Set(['.png', '.jpg', '.jpeg', '.gif', '.webp']);
// Le logo de marque a UNE seule forme voulue : vignette integree au cadre
// (compact, coin haut droit), jamais un grand bloc image separe sous le texte.
// Peu importe si l'agent l'ecrit en image ou en thumbnail, le code le ramene
// toujours en thumbnail. Choix de Ryan : une carte, pas un message + une image.
const BRAND_LOGO = 'donut-pay.png';

// Rend soit {url} pour une URL http, soit {file, ref} pour un fichier local
// autorise (a joindre via attachment://), soit null si refuse.
function resolveAsset(val) {
  if (typeof val !== 'string' || !val) return null;
  if (/^https?:\/\//i.test(val)) return { url: val };
  const base = val.split(/[\\/]/).pop();
  if (!base || base.startsWith('.')) return null;
  if (!IMG_EXT.has(path.extname(base).toLowerCase())) return null;
  for (const dir of ASSET_DIRS) {
    const full = path.resolve(dir, base);
    // full doit rester dans dir (pas de traversee) et exister
    if (full.startsWith(path.resolve(dir) + path.sep)) {
      if (fs.existsSync(full) && fs.statSync(full).isFile()) return { file: full, ref: `attachment://${base}` };
    }
  }
  return null;
}

const STYLES = {
  primary: ButtonStyle.Primary, secondary: ButtonStyle.Secondary,
  success: ButtonStyle.Success, danger: ButtonStyle.Danger, link: ButtonStyle.Link,
};

let client = null;
let guildId = null;

function loadState() {
  try { return JSON.parse(fs.readFileSync(STATE_FILE, 'utf8')); } catch { return { offset: null }; }
}
function saveState(s) { fs.writeFileSync(STATE_FILE, JSON.stringify(s)); }

function init(opts) { client = opts.client; guildId = opts.guildId; }

function parseColor(c) {
  if (typeof c === 'number') return c;
  if (typeof c === 'string') {
    const n = parseInt(c.replace(/^#/, ''), 16);
    if (!Number.isNaN(n)) return n;
  }
  return BRAND_COLOR;
}

function buildEmbed(e, files) {
  const b = new EmbedBuilder().setColor(parseColor(e.color));
  if (e.title) b.setTitle(String(e.title).slice(0, 256));
  if (e.description) b.setDescription(String(e.description).slice(0, 4096));
  for (const f of (e.fields || []).slice(0, 25)) {
    b.addFields({ name: String(f.name || '​').slice(0, 256), value: String(f.value || '​').slice(0, 1024), inline: !!f.inline });
  }
  if (e.footer) b.setFooter({ text: String(e.footer).slice(0, 2048) });
  // thumbnail et image acceptent une URL http OU un nom de fichier local
  // autorise, qu'on joint alors au message (attachment://). Un chemin refuse
  // est ignore en silence : mieux vaut un embed sans vignette qu'un envoi rate.
  // NORMALISATION marque : si le logo Donut Pay est demande en grand `image`,
  // on le bascule en `thumbnail` (sauf si une autre vignette est deja prevue),
  // pour que la marque ait toujours la forme compacte voulue.
  let thumbVal = e.thumbnail;
  let imageVal = e.image;
  const isBrand = (v) => typeof v === 'string' && v.split(/[\/]/).pop() === BRAND_LOGO;
  if (isBrand(imageVal) && !thumbVal) { thumbVal = imageVal; imageVal = null; }
  const th = resolveAsset(thumbVal);
  if (th) { b.setThumbnail(th.url || th.ref); if (th.file && !files.includes(th.file)) files.push(th.file); }
  const im = resolveAsset(imageVal);
  if (im) { b.setImage(im.url || im.ref); if (im.file && !files.includes(im.file)) files.push(im.file); }
  if (e.timestamp) b.setTimestamp(new Date());
  return b;
}

function buildRows(buttons) {
  const rows = [];
  const list = (buttons || []).slice(0, 25);
  for (let i = 0; i < list.length; i += 5) {
    const row = new ActionRowBuilder();
    for (const btn of list.slice(i, i + 5)) {
      const b = new ButtonBuilder().setLabel(String(btn.label || 'OK').slice(0, 80));
      if (btn.url) { b.setStyle(ButtonStyle.Link).setURL(btn.url); }
      else {
        b.setStyle(STYLES[btn.style] || ButtonStyle.Secondary);
        const id = String(btn.id || 'ok').replace(/[^a-z0-9_-]/gi, '').slice(0, 80) || 'ok';
        b.setCustomId(`oc_agent:${id}`);
      }
      row.addComponents(b);
    }
    rows.push(row);
  }
  return rows;
}

// supprime le dernier message poste par le bot lui-meme dans le salon :
// jamais un message d'un joueur, jamais plus d'un message par ordre.
async function deleteLast(name) {
  const guild = await client.guilds.fetch(guildId);
  const chans = await guild.channels.fetch();
  const chan = chans.find(c => c && c.type === 0 && c.name === (name || DEFAULT_CHANNEL));
  if (!chan) { console.warn(`Agent post : salon #${name} introuvable, suppression ignoree`); return; }
  const me = chan.client.user.id;
  const msgs = await chan.messages.fetch({ limit: 20 });
  const mine = [...msgs.values()].filter(m => m.author.id === me).sort((a, b) => b.createdTimestamp - a.createdTimestamp)[0];
  if (!mine) { console.warn(`Agent post : aucun message du bot a supprimer dans #${name}`); return; }
  await mine.delete().catch(() => {});
  console.log(`Agent post : dernier message du bot supprime dans #${name}`);
}

// envoie un message a un joueur linke : j.dm = pseudo Minecraft ou ID Discord.
// la resolution passe par links.json, donc seul un joueur verifie peut etre DM.
async function postDm(j) {
  const links = JSON.parse(fs.readFileSync(path.join(__dirname, 'links.json'), 'utf8')).links || {};
  let userId = null;
  if (/^\d+$/.test(String(j.dm))) {
    userId = String(j.dm);
  } else {
    const wanted = String(j.dm).toLowerCase();
    const hit = Object.entries(links).find(([id, l]) => (l.player || '').toLowerCase() === wanted);
    if (hit) userId = hit[0];
  }
  if (!userId) { console.warn(`Agent DM : joueur ${j.dm} non linke, message ignore`); return; }
  const user = await client.users.fetch(userId);
  const payload = {};
  const files = [];
  if (j.content) payload.content = String(j.content).slice(0, 2000);
  const embeds = (j.embeds || []).slice(0, 10).map(e => buildEmbed(e, files));
  if (embeds.length) payload.embeds = embeds;
  const rows = buildRows(j.buttons);
  if (rows.length) payload.components = rows;
  if (files.length) payload.files = files.slice(0, 10);
  if (!payload.content && !payload.embeds) return;
  await user.send(payload);
  console.log(`Agent DM : message envoye a ${j.dm} (${userId})`);
}

async function post(j) {
  if (j.dm) { await postDm(j); return; }
  if (j.delete) { await deleteLast(j.channel); return; }
  const guild = await client.guilds.fetch(guildId);
  const chans = await guild.channels.fetch();
  const name = j.channel || DEFAULT_CHANNEL;
  const chan = chans.find(c => c && c.type === 0 && c.name === name);
  if (!chan) { console.warn(`Agent post : salon #${name} introuvable, message ignore`); return; }
  const payload = {};
  const files = [];
  if (j.content) payload.content = String(j.content).slice(0, 2000);
  const embeds = (j.embeds || []).slice(0, 10).map(e => buildEmbed(e, files));
  if (embeds.length) payload.embeds = embeds;
  const rows = buildRows(j.buttons);
  if (rows.length) payload.components = rows;
  if (files.length) payload.files = files.slice(0, 10);
  if (!payload.content && !payload.embeds) return;
  await chan.send(payload);
  console.log(`Agent post : message dans #${name} (${embeds.length} embed(s), ${(j.buttons || []).length} bouton(s))`);
}

// meme mecanique d'offset que les autres watchers : initialise a la taille
// courante au premier passage, pour ne jamais rejouer l'historique
async function tick() {
  if (!client) return;
  const state = loadState();
  let size = 0;
  try { size = fs.statSync(OUTBOX).size; } catch { return; }
  if (state.offset === null || state.offset > size) { state.offset = size; saveState(state); return; }
  if (size <= state.offset) return;
  const fd = fs.openSync(OUTBOX, 'r');
  const buf = Buffer.alloc(size - state.offset);
  fs.readSync(fd, buf, 0, buf.length, state.offset);
  fs.closeSync(fd);
  state.offset = size; saveState(state);
  for (const line of buf.toString('utf8').replace(new RegExp('^\\uFEFF'), '').split('\n')) {
    const l = line.trim();
    if (!l) continue;
    try { await post(JSON.parse(l)); }
    catch (e) { console.warn('Agent post : ligne invalide ou envoi rate :', e.message); }
  }
}

// clic sur un bouton de l'agent : trace pour lui, accuse pour l'humain
async function onButton(interaction) {
  const id = interaction.customId.slice('oc_agent:'.length);
  const line = JSON.stringify({
    at: Date.now(), id,
    userId: interaction.user.id, userTag: interaction.user.tag,
    channel: interaction.channel && interaction.channel.name,
  });
  try { fs.appendFileSync(CLICKS, line + '\n'); } catch (e) { console.warn('Agent clicks :', e.message); }
  await interaction.reply({ content: 'Noted.', flags: MessageFlags.Ephemeral }).catch(() => {});
}

module.exports = { init, tick, onButton };
