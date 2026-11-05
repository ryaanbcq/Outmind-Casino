'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');
const { tmpDir } = require('./helpers');
const creerLedger = require('../lib/ledger');
const { CorruptStateError } = require('../lib/fstore');

test('mutate : relit, ecrit, et laisse une ligne d audit avant/apres', (t) => {
  const dir = tmpDir(t);
  const ledger = creerLedger(dir);
  assert.equal(Object.keys(ledger.load()).length, 0);
  const r = ledger.mutate('deposit', (bals, note) => {
    note('Alice', 500, { by: 'test' });
    bals.Alice = (bals.Alice || 0) + 500;
    return 'ok';
  });
  assert.equal(r, 'ok');
  assert.equal(ledger.load().Alice, 500);
  const audit = fs.readFileSync(path.join(dir, 'ledger-audit.jsonl'), 'utf8').trim().split('\n').map(JSON.parse);
  assert.equal(audit.length, 1);
  assert.equal(audit[0].op, 'deposit');
  assert.equal(audit[0].before, 0);
  assert.equal(audit[0].after, 500);
  assert.equal(audit[0].delta, 500);
  assert.equal(audit[0].by, 'test');
});

test('mutate : opts.after tourne sous le verrou avec le grand livre ecrit', (t) => {
  const dir = tmpDir(t);
  const ledger = creerLedger(dir);
  let seen = null;
  ledger.mutate('x', (bals) => { bals.Bob = 7; }, {
    after: (bals) => {
      seen = bals.Bob;
      assert.equal(fs.existsSync(path.join(dir, '.ledger.lock')), true, 'le verrou est encore tenu');
      assert.equal(JSON.parse(fs.readFileSync(ledger.file, 'utf8')).Bob, 7, 'les soldes sont deja sur disque');
    },
  });
  assert.equal(seen, 7);
  assert.equal(fs.existsSync(path.join(dir, '.ledger.lock')), false);
});

test('load : prototype nul, un pseudo "toString" ou "__proto__" n herite de rien (audit 2026-09-03 H1)', (t) => {
  const dir = tmpDir(t);
  const ledger = creerLedger(dir);
  assert.equal(ledger.load().toString, undefined);
  assert.equal(ledger.load().constructor, undefined);
  fs.writeFileSync(ledger.file, '{"__proto__": {"Alice": 999}, "Bob": 1}');
  const bals = ledger.load();
  assert.equal(bals.Alice, undefined);
  assert.equal(bals.Bob, 1);
});

test('load : un balances.json corrompu arrete le process au lieu de lire {} (tous les soldes a zero)', (t) => {
  const dir = tmpDir(t);
  const ledger = creerLedger(dir);
  fs.writeFileSync(ledger.file, '{"Alice": 1');
  assert.throws(() => ledger.load(), (e) => e instanceof CorruptStateError);
  assert.throws(() => ledger.mutate('x', () => {}), (e) => e instanceof CorruptStateError);
});

test('mutate : une exception dans fn ne touche ni le fichier ni l audit, et rend le verrou', (t) => {
  const dir = tmpDir(t);
  const ledger = creerLedger(dir);
  ledger.mutate('seed', (b, note) => { note('Alice', 10); b.Alice = 10; });
  assert.throws(() => ledger.mutate('bad', (b, note) => { note('Alice', -5); b.Alice -= 5; throw new Error('kaboom'); }), /kaboom/);
  assert.equal(ledger.load().Alice, 10);
  assert.equal(fs.readFileSync(path.join(dir, 'ledger-audit.jsonl'), 'utf8').trim().split('\n').length, 1);
  assert.equal(fs.existsSync(path.join(dir, '.ledger.lock')), false);
});

// Le vrai test : bot.js et bridge.js sont DEUX process qui font chacun des
// lecture-modification-ecriture sur le meme fichier. Sans le verrou inter-
// process, une ecriture ecrase l'autre et des depots disparaissent. On lance
// plusieurs process qui creditent chacun N fois le meme joueur : le total
// doit etre exact au dollar pres.
test('concurrence inter-process : aucun depot perdu entre plusieurs ecrivains', (t) => {
  const dir = tmpDir(t);
  const WORKERS = 4;
  const N = 150;
  const script = path.join(dir, 'worker.js');
  fs.writeFileSync(script, `
    const creerLedger = require(${JSON.stringify(path.resolve(__dirname, '../lib/ledger.js'))});
    const ledger = creerLedger(${JSON.stringify(dir)});
    for (let i = 0; i < ${N}; i++) {
      ledger.mutate('deposit', (bals, note) => {
        note('Shared', 1, { by: 'w' + process.pid });
        bals.Shared = (bals.Shared || 0) + 1;
        bals['w' + process.pid] = (bals['w' + process.pid] || 0) + 1;
      });
    }
  `);
  const procs = [];
  for (let i = 0; i < WORKERS; i++) {
    procs.push(require('child_process').spawn(process.execPath, [script], { stdio: 'inherit' }));
  }
  return Promise.all(procs.map((p) => new Promise((res, rej) => {
    p.on('exit', (code) => (code === 0 ? res() : rej(new Error(`worker exit ${code}`))));
  }))).then(() => {
    const ledger = creerLedger(dir);
    const bals = ledger.load();
    assert.equal(bals.Shared, WORKERS * N, 'chaque credit doit survivre a la concurrence');
    const perWorker = Object.keys(bals).filter((k) => k.startsWith('w')).map((k) => bals[k]);
    assert.equal(perWorker.length, WORKERS);
    assert.ok(perWorker.every((v) => v === N));
    const audit = fs.readFileSync(path.join(dir, 'ledger-audit.jsonl'), 'utf8').trim().split('\n');
    assert.equal(audit.length, WORKERS * N, 'une ligne d audit par mutation, aucune perdue');
    assert.equal(fs.existsSync(path.join(dir, '.ledger.lock')), false);
    // le tmp unique par process ne doit laisser aucune trace
    assert.deepEqual(fs.readdirSync(dir).filter((f) => f.endsWith('.tmp')), []);
  });
});
