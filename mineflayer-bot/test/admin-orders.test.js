'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');
const { tmpDir, fakeAlert } = require('./helpers');
const creerLedger = require('../lib/ledger');
const creerQuotas = require('../lib/quotas');
const creerGuard = require('../lib/guard');
const creerAdminOrders = require('../lib/admin-orders');

function setup(t) {
  const dir = tmpDir(t);
  const ledger = creerLedger(dir);
  const quotas = creerQuotas(dir, {});
  const logs = [];
  const guard = creerGuard({ dir, env: {}, alert: fakeAlert(), log: (m) => logs.push(m) });
  const orders = creerAdminOrders({ dir, ledger, quotas, guard, log: (m) => logs.push(m) });
  const state = {};
  let saves = 0;
  const run = () => orders.consume(state, () => { saves++; });
  const queue = (o) => fs.appendFileSync(path.join(dir, 'admin-orders.jsonl'), JSON.stringify(o) + '\n');
  const tx = () => { try { return fs.readFileSync(path.join(dir, 'transactions.jsonl'), 'utf8').trim().split('\n').map(JSON.parse); } catch { return []; } };
  return { dir, ledger, orders, state, run, queue, logs, tx, saves: () => saves };
}

test('bet : mise perdue = solde - mise, mise gagnee = solde + mise, en UNE mutation', (t) => {
  const s = setup(t);
  s.ledger.mutate('seed', (b, note) => { note('Alice', 10e6); b.Alice = 10e6; });
  s.queue({ kind: 'bet', player: 'Alice', amount: 2e6, payout: 0, by: 'chain:alice' });
  s.queue({ kind: 'bet', player: 'Alice', amount: 2e6, payout: 4e6, by: 'chain:alice' });
  s.run();
  assert.equal(s.ledger.load().Alice, 10e6);
  assert.equal(s.state.adminOffset, 2);
  const audit = fs.readFileSync(path.join(s.dir, 'ledger-audit.jsonl'), 'utf8').trim().split('\n').map(JSON.parse);
  assert.deepEqual(audit.slice(1).map((a) => [a.op, a.delta]), [['bet', -2e6], ['bet', 2e6]]);
});

test('bet : la mise n est plus la -> RIEN n est pris et RIEN n est paye (plus de pari gratuit)', (t) => {
  // Scenario de la revue 2026-11-05 : le joueur confirme une mise de 10M sur
  // Discord puis sort ses 10M du vault avant le tick du bridge. Avec le couple
  // debit (plafonne a 0) + credit, il gagnait 20M sans avoir rien risque.
  const s = setup(t);
  s.ledger.mutate('seed', (b, note) => { note('Alice', 0); b.Alice = 0; });
  s.queue({ kind: 'bet', player: 'Alice', amount: 10e6, payout: 20e6, by: 'chain:alice', reason: 'chain game: opening bet, doubled' });
  s.run();
  assert.equal(s.ledger.load().Alice, 0);
  assert.equal(s.state.adminOffset, 1, 'l ordre est consomme, pas rejoue');
  const refus = s.tx().filter((l) => l.type === 'bet-refused');
  assert.equal(refus.length, 1);
  assert.equal(refus[0].amount, 10e6);
  assert.equal(refus[0].payout, 20e6);
  assert.equal(refus[0].balance, 0);
  assert.ok(s.logs.some((l) => /bet REFUSE/.test(l)));
});

test('bet : solde partiel = refuse aussi (pas de mise a moitie)', (t) => {
  const s = setup(t);
  s.ledger.mutate('seed', (b, note) => { note('Alice', 9999999); b.Alice = 9999999; });
  s.queue({ kind: 'bet', player: 'Alice', amount: 10e6, payout: 0, by: 'chain:alice' });
  s.run();
  assert.equal(s.ledger.load().Alice, 9999999);
  assert.equal(s.tx().filter((l) => l.type === 'bet-refused').length, 1);
});

test('bet : gain negatif ou illisible = ordre refuse sans toucher au solde', (t) => {
  const s = setup(t);
  s.ledger.mutate('seed', (b, note) => { note('Alice', 5e6); b.Alice = 5e6; });
  s.queue({ kind: 'bet', player: 'Alice', amount: 1e6, payout: -5, by: 'x' });
  s.queue({ kind: 'bet', player: 'Alice', amount: 1e6, payout: 'Infinity', by: 'x' });
  s.run();
  assert.equal(s.ledger.load().Alice, 5e6);
  assert.equal(s.state.adminOffset, 2);
});

test('debit : documente le plancher a 0, la raison d etre de bet', (t) => {
  const s = setup(t);
  s.ledger.mutate('seed', (b, note) => { note('Alice', 1e6); b.Alice = 1e6; });
  s.queue({ kind: 'debit', player: 'Alice', amount: 5e6, by: 'admin' });
  s.queue({ kind: 'credit', player: 'Alice', amount: 2e6, by: 'admin' });
  s.run();
  assert.equal(s.ledger.load().Alice, 2e6, 'debit tombe a vide (0), le credit passe en entier');
});

test('consume : noms et montants invalides refuses, offset avance, pas de rejeu sur fichier retreci', (t) => {
  const s = setup(t);
  s.queue({ kind: 'credit', player: '__proto__', amount: 1e6, by: 'x' });
  s.queue({ kind: 'credit', player: 'Bob', amount: -1, by: 'x' });
  s.queue({ kind: 'credit', player: 'Bob', amount: 'abc', by: 'x' });
  s.queue({ kind: 'credit', player: 'Bob', amount: 1e6, by: 'x' });
  s.run();
  assert.equal(s.ledger.load().Bob, 1e6);
  assert.equal(s.ledger.load().__proto__, undefined);
  assert.equal(s.state.adminOffset, 4);
  fs.writeFileSync(path.join(s.dir, 'admin-orders.jsonl'), JSON.stringify({ kind: 'credit', player: 'Bob', amount: 1e6, by: 'x' }) + '\n');
  s.run();
  assert.equal(s.ledger.load().Bob, 1e6, 'fichier retreci : aucun rejeu');
  assert.equal(s.state.adminOffset, 1);
});

test('bet : le point Bedrock est recolle comme pour un credit', (t) => {
  const s = setup(t);
  s.ledger.mutate('seed', (b, note) => { note('.Steve', 5e6); b['.Steve'] = 5e6; });
  s.queue({ kind: 'bet', player: 'Steve', amount: 1e6, payout: 2e6, by: 'chain:steve' });
  s.run();
  assert.equal(s.ledger.load()['.Steve'], 6e6);
  assert.equal(s.ledger.load().Steve, undefined);
});
