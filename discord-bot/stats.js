// Rapport quotidien en image du casino Outmind.
//
// Tout est reconstruit depuis les journaux du bot mineflayer, il n'y a AUCUNE
// base d'historique a tenir : casino-deltas.jsonl porte chaque mouvement de
// mise avec son horodatage, transactions.jsonl porte les depots et les
// retraits. Consequence utile : le premier rapport genere couvre deja tout le
// passe, et une journee ratee (machine eteinte) se rattrape telle quelle.
//
// Le rendu passe par @napi-rs/canvas et pas par une librairie de graphiques :
// les binaires sont precompiles (aucun build tool sur Windows, contrairement a
// node-canvas), et les quatre graphiques d'ici sont assez simples pour etre
// traces a la main. En echange on maitrise exactement la charte du casino.

const fs = require('fs');
const path = require('path');
const { createCanvas, GlobalFonts } = require('@napi-rs/canvas');

const TZ = 'Europe/Paris';
const BOT_DIR = process.env.BOT_DIR || path.join(__dirname, '..', 'mineflayer-bot');

// ---------- polices ----------

// Segoe UI est enregistree explicitement plutot que nommee : la resolution par
// famille systeme depend de la plateforme, et une police absente ne leve pas
// d'erreur, elle rend un texte au mauvais chasse sans prevenir.
const FONTS = [
  ['segoeui.ttf', 'CasinoSans'],
  ['seguisb.ttf', 'CasinoSemi'],
  ['segoeuib.ttf', 'CasinoBold'],
];
let FS_REG = true;
for (const [file, family] of FONTS) {
  const p = path.join(process.env.FONTS_DIR || path.join(__dirname, '..', 'fonts'), file);
  try { if (!fs.existsSync(p) || !GlobalFonts.registerFromPath(p, family)) FS_REG = false; }
  catch { FS_REG = false; }
}
const REG = FS_REG ? 'CasinoSans' : 'sans-serif';
const SEMI = FS_REG ? 'CasinoSemi' : 'sans-serif';
const BOLD = FS_REG ? 'CasinoBold' : 'sans-serif';

// ---------- charte ----------

const C = {
  bg: '#100e18',
  panel: '#1a1726',
  panelEdge: '#2a2540',
  grid: '#241f36',
  text: '#eae7f5',
  muted: '#8a83a3',
  accent: '#a18cd1',   // debut du degrade maison
  accent2: '#fbc2eb',  // fin du degrade maison
  good: '#5cc98c',
  bad: '#e05c5c',
};

const W = 1200, H = 1010;

// ---------- lecture des journaux ----------

function readLines(file) {
  try {
    return fs.readFileSync(path.join(BOT_DIR, file), 'utf8')
      .split('\n').filter(Boolean)
      .map(l => { try { return JSON.parse(l); } catch { return null; } })
      .filter(Boolean);
  } catch { return []; }
}

function readJson(file, fallback) {
  try { return JSON.parse(fs.readFileSync(path.join(BOT_DIR, file), 'utf8').replace(/^\uFEFF/, '')); }
  catch { return fallback; }
}

// Les deux journaux n'ont pas le meme format de date : deltas en epoch ms,
// transactions en ISO. On normalise tout en ms des l'entree.
const dayOf = (ms) => new Date(ms).toLocaleDateString('en-CA', { timeZone: TZ });
const hourOf = (ms) => Number(new Date(ms).toLocaleString('en-US', { timeZone: TZ, hour: '2-digit', hour12: false }));

function todayKey() { return dayOf(Date.now()); }
function shiftDay(key, days) {
  const [y, m, d] = key.split('-').map(Number);
  // midi UTC : evite qu'un decalage d'heure d'ete fasse sauter un jour
  return new Date(Date.UTC(y, m - 1, d + days, 12)).toISOString().slice(0, 10);
}

// ---------- agregation ----------

// profit maison = l'oppose de ce que gagne le joueur. Verifie contre
// casino-stats.json : playerLosses - playerWins vaut bien le champ profit.
function collect(dayKey) {
  const deltas = readLines('casino-deltas.jsonl').filter(o => dayOf(o.at) === dayKey);
  const tx = readLines('transactions.jsonl')
    .map(o => ({ ...o, ms: new Date(o.at).getTime() }))
    .filter(o => dayOf(o.ms) === dayKey);

  const byHour = Array.from({ length: 24 }, () => ({ profit: 0, wagered: 0, rounds: 0 }));
  const byPlayer = new Map();
  let wagered = 0, won = 0;

  for (const o of deltas) {
    const h = byHour[hourOf(o.at)];
    h.profit -= o.delta;
    h.rounds += 1;
    if (o.delta < 0) { h.wagered += -o.delta; wagered += -o.delta; } else won += o.delta;
    byPlayer.set(o.player, (byPlayer.get(o.player) || 0) + o.delta);
  }

  const deposits = tx.filter(o => o.type === 'depot').reduce((s, o) => s + o.amount, 0);
  const cashouts = tx.filter(o => o.type === 'retrait').reduce((s, o) => s + o.amount, 0);

  return {
    day: dayKey,
    profit: wagered - won,
    wagered, won,
    rounds: deltas.length,
    players: byPlayer.size,
    byHour,
    top: [...byPlayer.entries()].map(([player, net]) => ({ player, net }))
      .sort((a, b) => Math.abs(b.net) - Math.abs(a.net)),
    deposits, cashouts,
    // heure de la derniere partie, pour savoir si la journee est encore vivante
    lastAt: deltas.length ? deltas[deltas.length - 1].at : null,
  };
}

// Serie des N derniers jours, pour la bande du bas. Un seul balayage du
// journal : a 1000 lignes par jour, relire le fichier par jour serait idiot.
function history(endDay, days) {
  const acc = new Map();
  for (const o of readLines('casino-deltas.jsonl')) {
    const k = dayOf(o.at);
    acc.set(k, (acc.get(k) || 0) - o.delta);
  }
  const out = [];
  for (let i = days - 1; i >= 0; i--) {
    const k = shiftDay(endDay, -i);
    out.push({ day: k, profit: acc.get(k) || 0 });
  }
  return out;
}

// ---------- formatage ----------

function shortMoney(n) {
  const a = Math.abs(n), s = n < 0 ? '-' : '';
  if (a >= 1e9) return s + '$' + (a / 1e9).toFixed(a % 1e9 === 0 ? 0 : 2) + 'B';
  if (a >= 1e6) return s + '$' + (a / 1e6).toFixed(a % 1e6 === 0 ? 0 : 1) + 'M';
  if (a >= 1e3) return s + '$' + (a / 1e3).toFixed(a % 1e3 === 0 ? 0 : 1) + 'K';
  return s + '$' + Math.round(a);
}

function prettyDay(key) {
  const [y, m, d] = key.split('-').map(Number);
  const mois = ['January', 'February', 'March', 'April', 'May', 'June',
    'July', 'August', 'September', 'October', 'November', 'December'];
  return `${mois[m - 1]} ${d}, ${y}`;
}

// ---------- primitives de dessin ----------

function roundRect(ctx, x, y, w, h, r) {
  const rr = Math.min(r, w / 2, h / 2);
  ctx.beginPath();
  ctx.moveTo(x + rr, y);
  ctx.arcTo(x + w, y, x + w, y + h, rr);
  ctx.arcTo(x + w, y + h, x, y + h, rr);
  ctx.arcTo(x, y + h, x, y, rr);
  ctx.arcTo(x, y, x + w, y, rr);
  ctx.closePath();
}

function panel(ctx, x, y, w, h, title) {
  ctx.fillStyle = C.panel;
  roundRect(ctx, x, y, w, h, 18); ctx.fill();
  ctx.strokeStyle = C.panelEdge; ctx.lineWidth = 1;
  roundRect(ctx, x + 0.5, y + 0.5, w - 1, h - 1, 18); ctx.stroke();
  if (title) {
    ctx.fillStyle = C.muted;
    ctx.font = `13px ${SEMI}`;
    ctx.textAlign = 'left'; ctx.textBaseline = 'alphabetic';
    ctx.fillText(title.toUpperCase(), x + 22, y + 32);
  }
}

function accentGradient(ctx, x0, y0, x1, y1) {
  const g = ctx.createLinearGradient(x0, y0, x1, y1);
  g.addColorStop(0, C.accent);
  g.addColorStop(1, C.accent2);
  return g;
}

function centered(ctx, text, x, y, w) {
  ctx.textAlign = 'center';
  ctx.fillText(text, x + w / 2, y);
  ctx.textAlign = 'left';
}

// « pas de donnees » plutot qu'un graphique vide : un axe seul laisse croire a
// un bug alors que le casino etait simplement ferme.
function emptyNote(ctx, x, y, w, h, msg) {
  ctx.fillStyle = C.muted;
  ctx.font = `15px ${REG}`;
  centered(ctx, msg, x, y + h / 2 + 5, w);
}

// ---------- graphiques ----------

// Courbe du profit maison cumule heure par heure. Remplissage vert au-dessus de
// zero, rouge en dessous : sur une mauvaise journee la courbe passe sous l'axe
// et il faut que ca se voie au premier coup d'oeil.
function drawCumulative(ctx, x, y, w, h, day) {
  panel(ctx, x, y, w, h, 'House profit, cumulative');
  const px = x + 70, py = y + 58, pw = w - 100, ph = h - 110;
  if (!day.rounds) return emptyNote(ctx, x, y, w, h, 'No activity on this day');

  const cum = []; let run = 0;
  for (let i = 0; i < 24; i++) { run += day.byHour[i].profit; cum.push(run); }

  const hi = Math.max(0, ...cum), lo = Math.min(0, ...cum);
  const span = (hi - lo) || 1;
  const pad = span * 0.12;
  const top = hi + pad, bot = lo - pad;
  const Y = (v) => py + ph - ((v - bot) / (top - bot)) * ph;
  const X = (i) => px + (i / 23) * pw;

  // grille et echelle
  ctx.font = `12px ${REG}`;
  ctx.textAlign = 'right'; ctx.textBaseline = 'middle';
  for (let i = 0; i <= 4; i++) {
    const v = bot + ((top - bot) * i) / 4, gy = Y(v);
    ctx.strokeStyle = C.grid; ctx.lineWidth = 1;
    ctx.beginPath(); ctx.moveTo(px, gy + 0.5); ctx.lineTo(px + pw, gy + 0.5); ctx.stroke();
    ctx.fillStyle = C.muted;
    ctx.fillText(shortMoney(v), px - 12, gy);
  }
  ctx.textAlign = 'left'; ctx.textBaseline = 'alphabetic';

  // ligne du zero, en trait plein plus clair
  if (bot < 0 && top > 0) {
    ctx.strokeStyle = C.muted; ctx.lineWidth = 1;
    ctx.setLineDash([4, 4]);
    ctx.beginPath(); ctx.moveTo(px, Y(0) + 0.5); ctx.lineTo(px + pw, Y(0) + 0.5); ctx.stroke();
    ctx.setLineDash([]);
  }

  // aire sous la courbe, coupee a la ligne du zero
  const zero = Math.max(py, Math.min(py + ph, Y(0)));
  const area = (from, to, color) => {
    ctx.save();
    ctx.beginPath(); ctx.rect(px, from, pw, to - from); ctx.clip();
    ctx.beginPath();
    ctx.moveTo(X(0), zero);
    for (let i = 0; i < 24; i++) ctx.lineTo(X(i), Y(cum[i]));
    ctx.lineTo(X(23), zero);
    ctx.closePath();
    const g = ctx.createLinearGradient(0, py, 0, py + ph);
    g.addColorStop(0, color + '55'); g.addColorStop(1, color + '00');
    ctx.fillStyle = g; ctx.fill();
    ctx.restore();
  };
  area(py, zero, C.good);
  area(zero, py + ph, C.bad);

  // la courbe
  ctx.strokeStyle = accentGradient(ctx, px, 0, px + pw, 0);
  ctx.lineWidth = 3; ctx.lineJoin = 'round';
  ctx.beginPath();
  for (let i = 0; i < 24; i++) (i ? ctx.lineTo : ctx.moveTo).call(ctx, X(i), Y(cum[i]));
  ctx.stroke();

  // point final, seul repere qui compte vraiment
  const last = 23;
  ctx.fillStyle = C.accent2;
  ctx.beginPath(); ctx.arc(X(last), Y(cum[last]), 5, 0, Math.PI * 2); ctx.fill();
  ctx.strokeStyle = C.bg; ctx.lineWidth = 2; ctx.stroke();

  // heures
  ctx.fillStyle = C.muted; ctx.font = `12px ${REG}`;
  ctx.textAlign = 'center';
  for (let i = 0; i < 24; i += 3) ctx.fillText(String(i).padStart(2, '0') + 'h', X(i), py + ph + 26);
  ctx.textAlign = 'left';
}

// Volume mise par heure. Repond a la seule question d'exploitation qui compte
// ici : a quelles heures le casino tourne, donc quand la caisse doit tenir.
function drawWagered(ctx, x, y, w, h, day) {
  panel(ctx, x, y, w, h, 'Wagered by hour');
  const px = x + 62, py = y + 56, pw = w - 88, ph = h - 104;
  if (!day.wagered) return emptyNote(ctx, x, y, w, h, 'Nothing wagered');

  const vals = day.byHour.map(o => o.wagered);
  const hi = Math.max(...vals) || 1;
  const bw = pw / 24;

  ctx.font = `12px ${REG}`;
  ctx.textAlign = 'right'; ctx.textBaseline = 'middle';
  for (let i = 0; i <= 3; i++) {
    const v = (hi * i) / 3, gy = py + ph - (v / hi) * ph;
    ctx.strokeStyle = C.grid; ctx.lineWidth = 1;
    ctx.beginPath(); ctx.moveTo(px, gy + 0.5); ctx.lineTo(px + pw, gy + 0.5); ctx.stroke();
    ctx.fillStyle = C.muted; ctx.fillText(shortMoney(v), px - 10, gy);
  }
  ctx.textAlign = 'left'; ctx.textBaseline = 'alphabetic';

  for (let i = 0; i < 24; i++) {
    if (!vals[i]) continue;
    const bh = Math.max(3, (vals[i] / hi) * ph);
    ctx.fillStyle = accentGradient(ctx, 0, py + ph - bh, 0, py + ph);
    roundRect(ctx, px + i * bw + bw * 0.18, py + ph - bh, bw * 0.64, bh, 4);
    ctx.fill();
  }

  ctx.fillStyle = C.muted; ctx.font = `12px ${REG}`;
  ctx.textAlign = 'center';
  for (let i = 0; i < 24; i += 4) ctx.fillText(String(i).padStart(2, '0') + 'h', px + i * bw + bw / 2, py + ph + 24);
  ctx.textAlign = 'left';
}

// Top joueurs, barres divergentes. Le signe est du point de vue du JOUEUR :
// vert il repart gagnant, rouge il a laisse de l'argent. C'est la lecture
// naturelle pour quiconque regarde le salon, l'inverse serait pris a rebours.
function drawTopPlayers(ctx, x, y, w, h, day) {
  panel(ctx, x, y, w, h, 'Biggest swings, player side');
  const rows = day.top.slice(0, 6);
  if (!rows.length) return emptyNote(ctx, x, y, w, h, 'No player activity');

  const px = x + 22, py = y + 54, pw = w - 44, ph = h - 76;
  const rh = Math.min(34, ph / rows.length);
  const hi = Math.max(...rows.map(r => Math.abs(r.net))) || 1;
  // Budget de largeur explicite. La barre la plus longue ne prend que 28 % de
  // chaque cote, le reste est reserve au nom d'un cote et au montant de
  // l'autre : sinon le montant du plus gros perdant sortait du cadre et
  // debordait sur le panneau voisin.
  const mid = px + pw / 2;
  const half = pw * 0.28;

  ctx.strokeStyle = C.grid; ctx.lineWidth = 1;
  ctx.beginPath(); ctx.moveTo(mid + 0.5, py); ctx.lineTo(mid + 0.5, py + rows.length * rh); ctx.stroke();

  rows.forEach((r, i) => {
    const cy = py + i * rh + rh / 2;
    const len = (Math.abs(r.net) / hi) * half;
    const win = r.net >= 0;

    ctx.fillStyle = win ? C.good : C.bad;
    if (win) roundRect(ctx, mid, cy - 9, Math.max(2, len), 18, 5);
    else roundRect(ctx, mid - Math.max(2, len), cy - 9, Math.max(2, len), 18, 5);
    ctx.fill();

    ctx.font = `13px ${SEMI}`;
    ctx.fillStyle = C.text;
    ctx.textBaseline = 'middle';
    ctx.textAlign = win ? 'right' : 'left';
    const name = r.player.length > 16 ? r.player.slice(0, 15) + '.' : r.player;
    ctx.fillText(name, win ? mid - 10 : mid + 10, cy);

    ctx.font = `13px ${BOLD}`;
    ctx.fillStyle = win ? C.good : C.bad;
    ctx.textAlign = win ? 'left' : 'right';
    ctx.fillText((r.net >= 0 ? '+' : '') + shortMoney(r.net), win ? mid + len + 10 : mid - len - 10, cy);
  });
  ctx.textAlign = 'left'; ctx.textBaseline = 'alphabetic';
}

// Bande des 7 derniers jours. Sert a repondre a « est-ce que la journee est
// normale », ce qu'un chiffre seul ne dit jamais.
function drawHistory(ctx, x, y, w, h, hist, focusDay) {
  panel(ctx, x, y, w, h, 'Last 7 days, house profit');
  const px = x + 24, py = y + 52, pw = w - 48, ph = h - 92;
  const hi = Math.max(1, ...hist.map(d => Math.abs(d.profit)));
  const bw = pw / hist.length;
  const zero = py + ph / 2;

  ctx.strokeStyle = C.grid; ctx.lineWidth = 1;
  ctx.beginPath(); ctx.moveTo(px, zero + 0.5); ctx.lineTo(px + pw, zero + 0.5); ctx.stroke();

  hist.forEach((d, i) => {
    const bh = (Math.abs(d.profit) / hi) * (ph / 2) * 0.9;
    const cx = px + i * bw + bw / 2;
    const focus = d.day === focusDay;
    const pos = d.profit >= 0;
    ctx.fillStyle = d.profit === 0 ? C.grid : pos ? C.good : C.bad;
    ctx.globalAlpha = focus || d.profit === 0 ? 1 : 0.45;
    roundRect(ctx, cx - bw * 0.22, pos ? zero - bh : zero, bw * 0.44, Math.max(3, bh), 4);
    ctx.fill();
    ctx.globalAlpha = 1;

    ctx.textAlign = 'center';
    ctx.font = `12px ${focus ? BOLD : REG}`;
    ctx.fillStyle = focus ? C.text : C.muted;
    ctx.fillText(d.day.slice(5).replace('-', '/'), cx, py + ph + 22);
    if (d.profit) {
      ctx.font = `12px ${SEMI}`;
      ctx.fillStyle = focus ? (pos ? C.good : C.bad) : C.muted;
      ctx.fillText(shortMoney(d.profit), cx, pos ? zero - bh - 8 : zero + bh + 16);
    }
  });
  ctx.textAlign = 'left';
}

// ---------- tuiles du haut ----------

// La valeur retrecit jusqu'a tenir dans la tuile. Sans ca « $436.1M / $275.2M »
// deborde du cadre : les montants varient de $0 a plusieurs milliards, aucune
// taille fixe ne convient a tous les jours.
function fitFont(ctx, text, family, max, min, width) {
  for (let s = max; s > min; s -= 1) {
    ctx.font = `${s}px ${family}`;
    if (ctx.measureText(text).width <= width) return;
  }
  ctx.font = `${min}px ${family}`;
}

function kpi(ctx, x, y, w, h, label, value, sub, color) {
  panel(ctx, x, y, w, h);
  ctx.textAlign = 'left';
  ctx.fillStyle = C.muted;
  ctx.font = `12px ${SEMI}`;
  ctx.fillText(label.toUpperCase(), x + 20, y + 30);
  ctx.fillStyle = color || C.text;
  fitFont(ctx, value, BOLD, 30, 15, w - 40);
  ctx.fillText(value, x + 20, y + 68);
  if (sub) {
    ctx.fillStyle = C.muted;
    ctx.font = `12px ${REG}`;
    ctx.fillText(sub, x + 20, y + 90);
  }
}

// ---------- rendu ----------

function render(day, hist, treasury) {
  const canvas = createCanvas(W, H);
  const ctx = canvas.getContext('2d');

  ctx.fillStyle = C.bg;
  ctx.fillRect(0, 0, W, H);
  // halo violet en haut a gauche, le seul ornement : sans lui le fond plat
  // fait capture d'ecran de terminal
  const halo = ctx.createRadialGradient(120, 40, 0, 120, 40, 620);
  halo.addColorStop(0, '#a18cd118');
  halo.addColorStop(1, '#a18cd100');
  ctx.fillStyle = halo; ctx.fillRect(0, 0, W, 420);

  // en-tete
  ctx.textBaseline = 'alphabetic';
  ctx.fillStyle = accentGradient(ctx, 48, 0, 520, 0);
  ctx.font = `34px ${BOLD}`;
  ctx.fillText('OUTMIND CASINO', 48, 62);
  ctx.fillStyle = C.muted;
  ctx.font = `15px ${REG}`;
  ctx.fillText('Daily report  ' + String.fromCharCode(183) + '  ' + prettyDay(day.day), 48, 88);

  ctx.textAlign = 'right';
  ctx.fillStyle = C.muted;
  ctx.font = `13px ${SEMI}`;
  ctx.fillText('VAULT', W - 48, 52);
  ctx.fillStyle = C.text;
  ctx.font = `26px ${BOLD}`;
  ctx.fillText(shortMoney(treasury), W - 48, 84);
  ctx.textAlign = 'left';

  // tuiles
  const gx = 48, gw = W - 96, gap = 16;
  const kw = (gw - gap * 3) / 4, kh = 108, ky = 112;
  const edge = day.wagered ? (day.profit / day.wagered) * 100 : 0;
  kpi(ctx, gx, ky, kw, kh, 'House profit',
    (day.profit > 0 ? '+' : '') + shortMoney(day.profit),
    day.wagered ? `${edge.toFixed(1)}% edge on volume` : 'no volume',
    day.profit > 0 ? C.good : day.profit < 0 ? C.bad : C.text);
  kpi(ctx, gx + (kw + gap), ky, kw, kh, 'Wagered',
    shortMoney(day.wagered), `${day.rounds.toLocaleString('en-US')} rounds`);
  kpi(ctx, gx + (kw + gap) * 2, ky, kw, kh, 'Players',
    String(day.players), day.players ? shortMoney(day.wagered / (day.players || 1)) + ' each' : 'nobody played');
  kpi(ctx, gx + (kw + gap) * 3, ky, kw, kh, 'In / Out',
    shortMoney(day.deposits) + ' / ' + shortMoney(day.cashouts),
    'deposits vs cash outs',
    day.deposits >= day.cashouts ? C.good : C.bad);

  // graphiques
  drawCumulative(ctx, gx, 244, gw, 300, day);
  const halfW = (gw - gap) / 2;
  drawWagered(ctx, gx, 560, halfW, 232, day);
  drawTopPlayers(ctx, gx + halfW + gap, 560, halfW, 232, day);
  drawHistory(ctx, gx, 808, gw, 158, hist, day.day);

  ctx.fillStyle = C.muted;
  ctx.font = `11px ${REG}`;
  ctx.fillText('Generated from the casino ledger  ' + String.fromCharCode(183) + '  times in Europe/Paris', 48, H - 16);
  ctx.textAlign = 'right';
  ctx.fillText('outmind', W - 48, H - 16);
  ctx.textAlign = 'left';

  return canvas.toBuffer('image/png');
}

// ---------- entree publique ----------

// Rend le rapport d'un jour donne. Retourne l'image et les chiffres, pour que
// l'appelant compose l'embed sans refaire les calculs.
function report(dayKey) {
  const key = dayKey || todayKey();
  const day = collect(key);
  const hist = history(key, 7);
  const treasury = (readJson('bank-state.json', {}) || {}).treasury || 0;
  return { day, hist, treasury, png: render(day, hist, treasury) };
}

module.exports = { report, collect, history, todayKey, shiftDay, prettyDay, shortMoney };
