'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { parseAmount, confirmMatches, fmt, fmtShort, fmtExact } = require('../lib/money');

test('parseAmount : suffixes k/m/b, dollar, espaces', () => {
  assert.equal(parseAmount('10m'), 10e6);
  assert.equal(parseAmount('500K'), 500e3);
  assert.equal(parseAmount('2b'), 2e9);
  assert.equal(parseAmount(' $1.5M '), 1.5e6);
  assert.equal(parseAmount('2500000'), 2500000);
});

test('parseAmount : virgule anglaise (milliers) vs virgule francaise (decimale)', () => {
  assert.equal(parseAmount('1,000,000'), 1e6);
  assert.equal(parseAmount('1,5m'), 1.5e6);
  assert.equal(parseAmount('12,345.67'), 12345.67);
});

test('parseAmount : refuse le bruit', () => {
  for (const bad of ['', 'abc', '1..2', '-5', '1.000.000', null, undefined, '10mm']) {
    assert.ok(Number.isNaN(parseAmount(bad)), `${bad} devrait etre NaN`);
  }
});

test('confirmMatches : incident 2026-08-16, 1 990 000 confirme "1.9M"', () => {
  // DonutSMP tronque : "1.9M" cache [1.9M, 2.0M). Une tolerance de 2 %
  // rejetait la confirmation et le joueur a ete paye trois fois.
  assert.equal(confirmMatches(1990000, '1.9M'), true);
  assert.equal(confirmMatches(1900000, '1.9M'), true);
  assert.equal(confirmMatches(1999999, '1.9M'), true);
  // mais pas n'importe quoi : 2.8M ne se cache pas derriere "1.9M"
  assert.equal(confirmMatches(2800000, '1.9M'), false);
  assert.equal(confirmMatches(2000000, '1.9M'), false);
});

test('confirmMatches : incident 2026-08-30, admin pay 777 999 affiche "777K"', () => {
  assert.equal(confirmMatches(777999, '777K'), true);
  assert.equal(confirmMatches(777000, '777K'), true);
  assert.equal(confirmMatches(778000, '777K'), false);
  assert.equal(confirmMatches(700000, '777K'), false);
});

test('confirmMatches : deux decimales affichees = pas plus fin', () => {
  assert.equal(confirmMatches(1950000, '1.95M'), true);
  assert.equal(confirmMatches(1959999, '1.95M'), true);
  assert.equal(confirmMatches(1960000, '1.95M'), false);
});

test('confirmMatches : montant exact sans suffixe, tolerance 2 % plancher 2000', () => {
  assert.equal(confirmMatches(950, '950'), true);
  assert.equal(confirmMatches(950, '2900'), true);   // |diff| 1950 <= 2000
  assert.equal(confirmMatches(950, '3000'), false);
  assert.equal(confirmMatches(1000000, '1,015,000'), true); // 1.5 % d'ecart
  assert.equal(confirmMatches(1000000, '1,030,000'), false);
  assert.equal(confirmMatches(1000, 'garbage'), false);
});

test('fmt / fmtShort / fmtExact : les formats que voient les joueurs', () => {
  assert.equal(fmt(1500000), '$1.5M');
  assert.equal(fmt(100000), '$100K');
  assert.equal(fmt(2e9), '$2B');
  assert.equal(fmt(950), '$950');
  assert.equal(fmt(99999), '$99,999');
  assert.equal(fmtShort(1500000), '1.5M');
  assert.equal(fmtShort(100000), '100K');
  assert.equal(fmtShort(2e9), '2B');
  assert.equal(fmtShort(999), '999');
  assert.equal(fmtExact(1234567.4), '$1,234,567');
});
