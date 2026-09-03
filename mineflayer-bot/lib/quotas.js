// ============================================================
//  quotas : plafonds de sortie journaliers, PARTAGES entre bot.js
//  (retraits « pay me ») et bridge.js (cashouts du casino).
//
//  L'argent sort par deux portes ; un compteur par process ne verrait
//  que sa moitie - c'est comme ca que 90M sont sortis le 2026-08-16
//  malgre un plafond deja atteint. daily-cap.json est le compteur
//  commun, ecrit sous verrou et en atomique depuis la refonte.
//
//  Regles (env, memes defauts qu'avant) :
//   - CASHOUT_DAILY_MAX : plafond fixe global/jour (0 = desactive)
//   - CASHOUT_DAILY_VAULT_PCT : % de la caisse perdable/jour
//   - CASHOUT_PLAYER_MAX_GAMBLER / _INVESTOR : quota par joueur
//   - INVESTOR_MIN : total investi qui fait passer Investor
//  Reset a minuit heure de Paris (le serveur vit en UTC).
// ============================================================
'use strict';
const path = require('path');
const { readJsonStrict, readJsonLoose, writeJsonAtomic, withLock } = require('./fstore');

module.exports = function creerQuotas(dir, env) {
  const DAILY_CAP_FILE = path.join(dir, 'daily-cap.json');
  const INVESTORS_FILE = path.join(dir, 'investors.json');
  const LOCK = path.join(dir, '.dailycap.lock');

  const DAILY_MAX = env.CASHOUT_DAILY_MAX === '0'
    ? Infinity
    : Number(env.CASHOUT_DAILY_MAX || 50000000);
  const DAILY_VAULT_PCT = Number(env.CASHOUT_DAILY_VAULT_PCT || 30) / 100;
  const PLAYER_MAX_GAMBLER = Number(env.CASHOUT_PLAYER_MAX_GAMBLER || 50000000);
  const PLAYER_MAX_INVESTOR = Number(env.CASHOUT_PLAYER_MAX_INVESTOR || 100000000);
  const INVESTOR_MIN = Number(env.INVESTOR_MIN || 3000000);

  const parisDay = () => new Date().toLocaleDateString('en-CA', { timeZone: 'Europe/Paris' });

  function readDailyCap() {
    // strict : un daily-cap corrompu relu comme vide remettrait tous les
    // compteurs a zero en silence, donc plafonds contournables
    let d = readJsonStrict(DAILY_CAP_FILE, {});
    if (d.day !== parisDay()) d = { day: parisDay(), paid: 0, players: {} };
    if (!d.players) d.players = {};
    return d;
  }

  // le contenu BRUT du fichier, sans reset au changement de jour : le rapport
  // quotidien lit la veille juste apres minuit, avant que quiconque n'ecrive
  function readDailyCapRaw() {
    return readJsonLoose(DAILY_CAP_FILE, null);
  }

  function addDailyPaid(player, amount) {
    withLock(LOCK, () => {
      const d = readDailyCap();
      d.paid = (d.paid || 0) + amount;
      const k = String(player).toLowerCase();
      d.players[k] = (d.players[k] || 0) + amount;
      writeJsonAtomic(DAILY_CAP_FILE, d);
    });
  }

  // sorties manuelles (/admin pay) : tracees dans le meme fichier mais dans un
  // compteur SEPARE qui ne mord ni sur le plafond maison ni sur les quotas
  // (le 2026-08-17, 65M de cadeaux admin avaient bloque tous les cashouts)
  function addManualPaid(player, amount) {
    withLock(LOCK, () => {
      const d = readDailyCap();
      d.manualPaid = (d.manualPaid || 0) + amount;
      d.manualPlayers = d.manualPlayers || {};
      const k = String(player).toLowerCase();
      d.manualPlayers[k] = (d.manualPlayers[k] || 0) + amount;
      writeJsonAtomic(DAILY_CAP_FILE, d);
    });
  }

  // Grade deduit du total investi, publie par le bot Discord depuis le
  // state.yml du plugin. Fichier absent = tout le monde est Gambler (repli
  // prudent). Les cles invested sont SANS le point Bedrock (DonutSMP mange le
  // point du payeur) : on normalise TOUJOURS - l'ancien bot.js ne le faisait
  // pas et deux quotas differents s'appliquaient au meme joueur selon la porte.
  function isInvestor(player) {
    const inv = (readJsonLoose(INVESTORS_FILE, {}) || {}).invested || {};
    return (inv[String(player).toLowerCase().replace(/^\./, '')] || 0) >= INVESTOR_MIN;
  }

  function playerDailyMax(player) {
    return isInvestor(player) ? PLAYER_MAX_INVESTOR : PLAYER_MAX_GAMBLER;
  }

  // plafond maison du jour : le plus serre du fixe et du % de caisse.
  // treasury null (caisse inconnue) -> seul le fixe compte, on ne bloque pas tout.
  function dailyCap(treasury) {
    const byVault = (typeof treasury === 'number' && isFinite(treasury))
      ? treasury * DAILY_VAULT_PCT : Infinity;
    return Math.min(DAILY_MAX, byVault);
  }

  // ce que ce joueur peut encore sortir aujourd'hui : le plus serre entre son
  // quota personnel et ce qui reste du plafond maison. AFFICHAGE seulement -
  // la decision de payer passe par reserve(), sinon deux portes qui lisent le
  // meme reliquat au meme instant le depensent chacune (check-then-act).
  function dailyRemaining(player, treasury) {
    const d = readDailyCap();
    const globalLeft = Math.max(0, dailyCap(treasury) - (d.paid || 0));
    const persoLeft = Math.max(0, playerDailyMax(player) - (d.players[String(player).toLowerCase()] || 0));
    return Math.min(globalLeft, persoLeft);
  }

  // Reservation ATOMIQUE : lecture du reliquat, decision et increment dans le
  // MEME verrou. Retourne ce qui est reellement accorde (0 si plus rien).
  function reserve(player, amount, treasury) {
    return withLock(LOCK, () => {
      const d = readDailyCap();
      const k = String(player).toLowerCase();
      const globalLeft = Math.max(0, dailyCap(treasury) - (d.paid || 0));
      const persoLeft = Math.max(0, playerDailyMax(player) - (d.players[k] || 0));
      const take = Math.max(0, Math.min(amount, globalLeft, persoLeft));
      if (take > 0) {
        d.paid = (d.paid || 0) + take;
        d.players[k] = (d.players[k] || 0) + take;
        writeJsonAtomic(DAILY_CAP_FILE, d);
      }
      return take;
    });
  }

  // rend une reservation abandonnee (retrait annule sous verrou du grand livre)
  function release(player, amount) {
    return withLock(LOCK, () => {
      const d = readDailyCap();
      const k = String(player).toLowerCase();
      const give = Math.max(0, Math.min(Number(amount) || 0, d.players[k] || 0));
      if (give > 0) {
        d.paid = Math.max(0, (d.paid || 0) - give);
        d.players[k] = Math.max(0, (d.players[k] || 0) - give);
        writeJsonAtomic(DAILY_CAP_FILE, d);
      }
      return give;
    });
  }

  return {
    release,
    parisDay,
    readDailyCap,
    readDailyCapRaw,
    addDailyPaid,
    addManualPaid,
    isInvestor,
    playerDailyMax,
    dailyCap,
    dailyRemaining,
    reserve,
    limits: {
      DAILY_MAX, DAILY_VAULT_PCT, PLAYER_MAX_GAMBLER, PLAYER_MAX_INVESTOR, INVESTOR_MIN,
    },
  };
};
