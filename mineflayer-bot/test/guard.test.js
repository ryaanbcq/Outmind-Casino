'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const { tmpDir, fakeAlert } = require('./helpers');
const creerGuard = require('../lib/guard');

const ENV = {
  BRIDGE_PUSH_SINGLE_MAX: '250000000',
  BRIDGE_PUSH_REPEAT_MIN: '5000000',
  BRIDGE_PUSH_REPEAT_MS: String(15 * 60 * 1000),
  BRIDGE_PUSH_NOTIFY: '25000000',
};

function setup(t) {
  const alert = fakeAlert();
  const logs = [];
  const guard = creerGuard({ dir: tmpDir(t), env: ENV, alert, log: (m) => logs.push(m) });
  return { guard, alert, logs, state: {} };
}

test('gel global : fichier present = banque gelee, degel = fichier supprime', (t) => {
  const { guard, alert } = setup(t);
  assert.equal(guard.frozenGlobal(), false);
  guard.freezeGlobal('test');
  assert.equal(guard.frozenGlobal(), true);
  assert.equal(JSON.parse(fs.readFileSync(guard.FREEZE_FILE, 'utf8')).reason, 'test');
  assert.equal(alert.calls[0].key, 'freeze-global');
  guard.unfreezeGlobal();
  assert.equal(guard.frozenGlobal(), false);
});

test('R1 : une poussee unitaire au-dessus du plafond gele le joueur', (t) => {
  const { guard, state, alert } = setup(t);
  assert.deepEqual(guard.checkPush(state, 'Alice', 250e6), { ok: true });
  const r = guard.checkPush(state, 'Bob', 250e6 + 1);
  assert.equal(r.ok, false);
  assert.equal(guard.isFrozenPlayer(state, 'Bob'), true);
  assert.equal(guard.isFrozenPlayer(state, 'Alice'), false);
  assert.ok(alert.calls.some((c) => c.key === 'freeze-Bob'));
  // un joueur gele n'est plus reconcilie du tout, meme pour 1 $
  assert.equal(guard.checkPush(state, 'Bob', 1).ok, false);
});

test('R2 : incident 2x70M du 2026-08-29, deux poussees quasi identiques = boucle', (t) => {
  const { guard, state } = setup(t);
  assert.equal(guard.checkPush(state, 'Alice', 70e6).ok, true);
  guard.recordPush(state, 'Alice', 70e6);
  const r = guard.checkPush(state, 'Alice', 70.5e6); // +0.7 %, dans la marge de 1 %
  assert.equal(r.ok, false);
  assert.equal(r.reason, 'poussee repetee');
  assert.equal(guard.isFrozenPlayer(state, 'Alice'), true);
});

test('R2 : un depot different ou un retrait en face ne declenchent pas', (t) => {
  const { guard, state } = setup(t);
  guard.recordPush(state, 'Alice', 70e6);
  assert.equal(guard.checkPush(state, 'Alice', 40e6).ok, true, 'montant different');
  assert.equal(guard.checkPush(state, 'Alice', -70e6).ok, true, 'signe oppose');
  assert.equal(guard.checkPush(state, 'Bob', 70e6).ok, true, 'autre joueur');
});

test('R2 : sous le seuil, rien n est journalise ni bloque', (t) => {
  const { guard, state } = setup(t);
  guard.recordPush(state, 'Alice', 1e6);
  assert.equal(state.pushLog, undefined);
  guard.checkPush(state, 'Alice', 1e6);
  assert.equal(guard.checkPush(state, 'Alice', 1e6).ok, true);
});

test('R2 : la fenetre expire, prunePushLog nettoie', (t) => {
  const { guard, state } = setup(t);
  const vieux = Date.now() - 16 * 60 * 1000;
  state.pushLog = { Alice: [{ at: vieux, amount: 70e6 }], Bob: [{ at: Date.now(), amount: 10e6 }] };
  assert.equal(guard.checkPush(state, 'Alice', 70e6).ok, true, 'hors fenetre : pas une boucle');
  guard.prunePushLog(state);
  assert.deepEqual(Object.keys(state.pushLog), ['Bob']);
  guard.prunePushLog({ pushLog: undefined });
});

test('R3 : une grosse poussee legitime passe mais est signalee', (t) => {
  const { guard, state, alert } = setup(t);
  assert.equal(guard.checkPush(state, 'Alice', 30e6).ok, true);
  assert.equal(alert.calls.length, 1);
  assert.equal(alert.calls[0].key, 'push-notify-Alice');
  guard.notifyBigPush('Bob', -30e6);
  assert.equal(alert.calls[1].key, 'push-notify-Bob');
  guard.notifyBigPush('Bob', -1e6);
  assert.equal(alert.calls.length, 2);
});

test('unfreezePlayer purge aussi l historique, sinon la poussee retentee re-gele', (t) => {
  const { guard, state, logs } = setup(t);
  guard.recordPush(state, 'Alice', 70e6);
  guard.checkPush(state, 'Alice', 70e6);
  assert.equal(guard.isFrozenPlayer(state, 'Alice'), true);
  assert.equal(guard.unfreezePlayer(state, 'Alice'), true);
  assert.equal(guard.unfreezePlayer(state, 'Alice'), false);
  assert.equal(state.pushLog.Alice, undefined);
  assert.equal(guard.checkPush(state, 'Alice', 70e6).ok, true, 'un humain a verifie : la poussee repart');
  assert.deepEqual(logs, ['degel de Alice']);
});
