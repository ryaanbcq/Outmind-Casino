'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');
const { tmpDir } = require('./helpers');
const creerQuotas = require('../lib/quotas');
const { CorruptStateError } = require('../lib/fstore');

const ENV = {
  CASHOUT_DAILY_MAX: '50000000',
  CASHOUT_DAILY_VAULT_PCT: '30',
  CASHOUT_PLAYER_MAX_GAMBLER: '10000000',
  CASHOUT_PLAYER_MAX_INVESTOR: '20000000',
  INVESTOR_MIN: '3000000',
};

test('reserve : plafond perso, puis plus rien, puis release rend la place', (t) => {
  const q = creerQuotas(tmpDir(t), ENV);
  assert.equal(q.reserve('Alice', 6e6, null), 6e6);
  assert.equal(q.reserve('alice', 6e6, null), 4e6, 'insensible a la casse, plafonne a 10M');
  assert.equal(q.reserve('Alice', 1, null), 0);
  assert.equal(q.release('Alice', 3e6), 3e6);
  assert.equal(q.dailyRemaining('Alice', null), 3e6);
  assert.equal(q.release('Alice', 99e6), 7e6, 'on ne rend jamais plus que ce qui a ete pris');
  assert.equal(q.readDailyCap().paid, 0);
});

test('reserve : les deux portes (bot + bridge) partagent le meme plafond maison', (t) => {
  // incident 2026-08-16 : 90M sortis car chaque process comptait de son cote
  const dir = tmpDir(t);
  const porteBot = creerQuotas(dir, ENV);
  const porteBridge = creerQuotas(dir, ENV);
  const treasury = 100e6; // 30 % -> plafond maison 30M, plus serre que le fixe 50M
  assert.equal(porteBot.reserve('A', 10e6, treasury), 10e6);
  assert.equal(porteBridge.reserve('B', 10e6, treasury), 10e6);
  assert.equal(porteBot.reserve('C', 10e6, treasury), 10e6);
  assert.equal(porteBridge.reserve('D', 10e6, treasury), 0, 'plafond maison atteint pour tous');
  assert.equal(porteBot.dailyRemaining('E', treasury), 0);
});

test('dailyCap : caisse inconnue -> seul le plafond fixe compte ; CASHOUT_DAILY_MAX=0 le desactive', (t) => {
  const q = creerQuotas(tmpDir(t), ENV);
  assert.equal(q.dailyCap(null), 50e6);
  assert.equal(q.dailyCap(NaN), 50e6);
  assert.equal(q.dailyCap(10e6), 3e6);
  const sansFixe = creerQuotas(tmpDir(t), { ...ENV, CASHOUT_DAILY_MAX: '0' });
  assert.equal(sansFixe.dailyCap(null), Infinity);
  assert.equal(sansFixe.dailyCap(200e6), 60e6);
});

test('isInvestor : le point Bedrock est normalise des deux cotes', (t) => {
  const dir = tmpDir(t);
  const q = creerQuotas(dir, ENV);
  fs.writeFileSync(path.join(dir, 'investors.json'), JSON.stringify({ invested: { steveb: 5e6, poor: 1 } }));
  assert.equal(q.isInvestor('.SteveB'), true);
  assert.equal(q.isInvestor('SteveB'), true);
  assert.equal(q.isInvestor('Poor'), false);
  assert.equal(q.isInvestor('Unknown'), false);
  assert.equal(q.playerDailyMax('.SteveB'), 20e6);
  assert.equal(q.playerDailyMax('Poor'), 10e6);
  // fichier absent : tout le monde Gambler, repli prudent
  const q2 = creerQuotas(tmpDir(t), ENV);
  assert.equal(q2.isInvestor('steveb'), false);
});

test('addManualPaid : les cadeaux admin ne mordent pas sur les quotas', (t) => {
  // incident 2026-08-17 : 65M de cadeaux admin avaient bloque tous les cashouts
  const q = creerQuotas(tmpDir(t), ENV);
  q.addManualPaid('Alice', 65e6);
  assert.equal(q.readDailyCap().paid, 0);
  assert.equal(q.readDailyCap().manualPaid, 65e6);
  assert.equal(q.dailyRemaining('Alice', null), 10e6);
  q.addDailyPaid('Alice', 4e6);
  assert.equal(q.dailyRemaining('Alice', null), 6e6);
});

test('readDailyCap : un compteur de la veille repart a zero, le brut le garde pour le rapport', (t) => {
  const dir = tmpDir(t);
  const q = creerQuotas(dir, ENV);
  fs.writeFileSync(path.join(dir, 'daily-cap.json'), JSON.stringify({ day: '2000-01-01', paid: 49e6, players: { alice: 49e6 } }));
  assert.equal(q.readDailyCapRaw().paid, 49e6);
  assert.equal(q.readDailyCap().paid, 0);
  assert.equal(q.readDailyCap().day, q.parisDay());
  assert.equal(q.reserve('Alice', 5e6, null), 5e6);
});

test('readDailyCap : un daily-cap corrompu arrete tout au lieu de remettre les compteurs a zero', (t) => {
  const dir = tmpDir(t);
  const q = creerQuotas(dir, ENV);
  fs.writeFileSync(path.join(dir, 'daily-cap.json'), '{"day":');
  assert.throws(() => q.reserve('Alice', 1, null), (e) => e instanceof CorruptStateError);
  assert.throws(() => q.dailyRemaining('Alice', null), (e) => e instanceof CorruptStateError);
});
