'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');
const { tmpDir } = require('./helpers');
const {
  CorruptStateError, readJsonStrict, readJsonLoose, writeJsonAtomic, withLock, splitCompleteLines,
} = require('../lib/fstore');

test('readJsonStrict : absent -> fallback, corrompu -> CorruptStateError', (t) => {
  const dir = tmpDir(t);
  const f = path.join(dir, 'balances.json');
  assert.deepEqual(readJsonStrict(f, { fresh: true }), { fresh: true });
  fs.writeFileSync(f, '{"Alice": 100, "Bob":'); // ecriture coupee en plein milieu
  assert.throws(() => readJsonStrict(f, {}), (e) => e instanceof CorruptStateError && e.file === f);
});

test('readJsonLoose : toute erreur -> fallback', (t) => {
  const dir = tmpDir(t);
  const f = path.join(dir, 'decor.json');
  assert.equal(readJsonLoose(f, null), null);
  fs.writeFileSync(f, 'not json');
  assert.deepEqual(readJsonLoose(f, []), []);
});

test('writeJsonAtomic : ne laisse ni tmp ni fichier partiel', (t) => {
  const dir = tmpDir(t);
  const f = path.join(dir, 'state.json');
  writeJsonAtomic(f, { a: 1 });
  writeJsonAtomic(f, { a: 2 }, true);
  assert.deepEqual(JSON.parse(fs.readFileSync(f, 'utf8')), { a: 2 });
  assert.deepEqual(fs.readdirSync(dir), ['state.json']);
});

test('withLock : exclusion mutuelle et liberation meme en cas d erreur', (t) => {
  const dir = tmpDir(t);
  const lock = path.join(dir, '.lock');
  assert.equal(withLock(lock, () => 42), 42);
  assert.equal(fs.existsSync(lock), false);
  assert.throws(() => withLock(lock, () => { throw new Error('boom'); }), /boom/);
  assert.equal(fs.existsSync(lock), false, 'le verrou doit etre rendu apres une exception');
  // reentrance interdite : un verrou tenu n'est pas repris, il expire
  withLock(lock, () => {
    assert.throws(() => withLock(lock, () => {}, { timeoutMs: 120, staleMs: 100000 }), /bloque depuis/);
  });
});

test('withLock : un verrou de process mort est vole, pas attendu', (t) => {
  const dir = tmpDir(t);
  const lock = path.join(dir, '.lock');
  fs.mkdirSync(lock);
  const old = new Date(Date.now() - 60000);
  fs.utimesSync(lock, old, old);
  const t0 = Date.now();
  assert.equal(withLock(lock, () => 'entre', { staleMs: 1000, timeoutMs: 5000 }), 'entre');
  assert.ok(Date.now() - t0 < 1000, 'le vol doit etre immediat');
  assert.equal(fs.existsSync(lock), false);
});

test('splitCompleteLines : offsets en octets, ligne partielle gardee pour plus tard', () => {
  const buf = Buffer.from('{"a":1}\n{"é":2}\n{"partial');
  const { lines, consumed } = splitCompleteLines(buf);
  assert.deepEqual(lines, ['{"a":1}', '{"é":2}']);
  assert.equal(consumed, Buffer.byteLength('{"a":1}\n{"é":2}\n'));
  assert.deepEqual(splitCompleteLines(Buffer.from('')), { lines: [], consumed: 0 });
  assert.deepEqual(splitCompleteLines(Buffer.from('no newline')), { lines: [], consumed: 0 });
});
