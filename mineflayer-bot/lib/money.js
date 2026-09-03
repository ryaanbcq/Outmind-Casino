// ============================================================
//  money : parsing et formatage des montants, PARTAGES entre bot.js et
//  bridge.js. Avant la refonte chaque process avait sa copie, et les
//  copies divergeaient (vecu sur isInvestor : le point Bedrock etait
//  normalise d'un cote et pas de l'autre).
// ============================================================
'use strict';

function parseAmount(str) {
  if (str == null) return NaN;
  let s = String(str).trim().toLowerCase().replace(/[$\s]/g, '');
  let mult = 1;
  const suf = s.match(/[kmb]$/);
  if (suf) {
    mult = suf[0] === 'k' ? 1e3 : suf[0] === 'm' ? 1e6 : 1e9;
    s = s.slice(0, -1);
  }
  // « 1,000,000 » anglais = separateurs de milliers ; « 1,5 » francais = decimale
  if (/^\d{1,3}(,\d{3})+(\.\d+)?$/.test(s)) s = s.replace(/,/g, '');
  else s = s.replace(',', '.');
  if (!/^\d*\.?\d+$/.test(s)) return NaN;
  return parseFloat(s) * mult;
}

// Est-ce que la confirmation « You paid X $ <montant> » correspond au paiement
// attendu ? DonutSMP abrege ET TRONQUE : 1 990 000 s'affiche « 1.9M ». Quand le
// montant confirme est abrege, le vrai montant est dans [affiche, affiche + un
// pas], le pas valant un dixieme de l'unite ; plus une marge d'un pas en
// dessous au cas ou le serveur arrondirait au lieu de tronquer. Sans suffixe le
// montant est exact et une tolerance en pourcentage suffit. (Le 2026-08-16 une
// tolerance de 2 % rejetait l'arrondi : a player a recu trois fois 1,99M.)
function confirmMatches(expected, confirmedRaw) {
  const amt = parseAmount(confirmedRaw);
  if (!isFinite(amt)) return false;
  const suf = String(confirmedRaw).trim().toLowerCase().match(/[kmb]\s*$/);
  if (suf) {
    const unit = suf[0].trim() === 'k' ? 1e3 : suf[0].trim() === 'm' ? 1e6 : 1e9;
    const step = unit / 10;
    // "1.9M" cache [1.9M, 2.0M) : la borne haute est un pas de la precision
    // affichee, pas une unite entiere (sinon 1.95M confirmait aussi un 2.8M en attente)
    const decimals = (String(confirmedRaw).match(/\.(\d+)\s*[kmb]\s*$/i) || ['', ''])[1].length;
    const stepUp = unit / Math.pow(10, decimals);
    // Troncature a l affichage : "777K" peut cacher 777000 a 777999.
    // Borne haute = amt + unite (exclu), borne basse tolerante (arrondi) :
    // vecu 2026-08-30, admin pay 777999 affirme "777K" non matche, repays en boucle.
    return expected >= amt - step && expected < amt + stepUp;
  }
  return Math.abs(expected - amt) <= Math.max(2000, expected * 0.02);
}

// format long pour les MP ($1.5M, $100K, $2B, $950)
function fmt(n) {
  const abs = Math.abs(n);
  const trim = (v) => v.toFixed(2).replace(/\.?0+$/, '');
  if (abs >= 1e9) return '$' + trim(n / 1e9) + 'B';
  if (abs >= 1e6) return '$' + trim(n / 1e6) + 'M';
  if (abs >= 1e5) return '$' + trim(n / 1e3) + 'K';
  return '$' + n.toLocaleString('en-US', { maximumFractionDigits: 2 });
}

// format court pour les hologrammes (1.5M, 100K, 2B - sans le $)
function fmtShort(n) {
  let s;
  if (n >= 1e9) s = (n / 1e9).toFixed(1) + 'B';
  else if (n >= 1e6) s = (n / 1e6).toFixed(1) + 'M';
  else if (n >= 1e3) s = (n / 1e3).toFixed(1) + 'K';
  else return String(Math.floor(n));
  return s.replace(/\.0([KMB])$/, '$1'); // 100.0K -> 100K
}

// pour les messages en jeu et les rapports : $1,234,567
function fmtExact(n) {
  return '$' + Math.round(n).toLocaleString('en-US');
}

module.exports = { parseAmount, confirmMatches, fmt, fmtShort, fmtExact };
