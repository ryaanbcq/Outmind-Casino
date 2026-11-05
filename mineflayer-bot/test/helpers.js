// Outils communs aux tests : un dossier d'etat jetable par test et un
// enregistreur d'alertes en memoire.
'use strict';
const fs = require('fs');
const os = require('os');
const path = require('path');

function tmpDir(t) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'outmind-test-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  return dir;
}

function fakeAlert() {
  const calls = [];
  const alert = (key, msg, cooldown) => calls.push({ key, msg, cooldown });
  alert.calls = calls;
  return alert;
}

module.exports = { tmpDir, fakeAlert };
