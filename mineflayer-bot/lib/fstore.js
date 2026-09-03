// ============================================================
//  fstore : lecture/ecriture disque de la banque.
//
//  Trois garanties que le fs nu ne donne pas :
//   - ecriture ATOMIQUE (tmp + rename) : un crash en pleine ecriture ne
//     laisse jamais un JSON a moitie ecrit. Un balances.json corrompu
//     etait lu comme {} par l'ancien code, donc TOUS les soldes a zero
//     et une reconciliation qui debite tout le monde en jeu.
//   - lecture STRICTE pour les fichiers d'argent : "absent" (premier
//     demarrage) et "corrompu" (incident) sont deux cas differents. Le
//     second doit arreter le process, pas repartir de zero en silence.
//   - verrou inter-process (mkdir) : balances.json et daily-cap.json ont
//     DEUX ecrivains (bot.js et bridge.js) en lecture-modification-
//     ecriture. Sans verrou, une ecriture peut en ecraser une autre :
//     un depot perdu ou un debit annule.
// ============================================================
'use strict';
const fs = require('fs');

class CorruptStateError extends Error {
  constructor(file, cause) {
    super(`fichier d'etat illisible : ${file} (${cause && cause.message})`);
    this.name = 'CorruptStateError';
    this.file = file;
  }
}

// fichier d'argent : absent -> fallback, corrompu -> on s'arrete
function readJsonStrict(file, fallback) {
  let raw;
  try { raw = fs.readFileSync(file, 'utf8'); }
  catch (e) {
    if (e.code === 'ENOENT') return fallback;
    throw new CorruptStateError(file, e);
  }
  try { return JSON.parse(raw); }
  catch (e) { throw new CorruptStateError(file, e); }
}

// fichier de confort (stats, decor) : toute erreur -> fallback
function readJsonLoose(file, fallback) {
  try { return JSON.parse(fs.readFileSync(file, 'utf8')); } catch { return fallback; }
}

// tmp UNIQUE par process : deux process qui ecriraient le meme .tmp au meme
// moment se voleraient le contenu au rename
function writeJsonAtomic(file, obj, pretty) {
  const tmp = `${file}.${process.pid}.tmp`;
  fs.writeFileSync(tmp, pretty ? JSON.stringify(obj, null, 2) : JSON.stringify(obj));
  fs.renameSync(tmp, file);
}

function appendLine(file, obj) {
  fs.appendFileSync(file, JSON.stringify(obj) + '\n');
}

// attente courte SYNCHRONE (pas de setTimeout : les sections verrouillees
// sont du code synchrone, on ne veut pas ceder la boucle d'evenements en
// tenant un demi-etat)
function sleepSync(ms) {
  const sab = new Int32Array(new SharedArrayBuffer(4));
  Atomics.wait(sab, 0, 0, ms);
}

// Verrou par dossier : mkdir est atomique sur tous les fs. staleMs casse le
// verrou d'un process mort (les sections font <50 ms, 10 s c'est un cadavre).
// timeoutMs > staleMs pour garantir qu'on finit soit par prendre le verrou,
// soit par le voler, jamais par attendre un fantome.
function withLock(lockPath, fn, opts = {}) {
  const timeoutMs = opts.timeoutMs || 30000;
  const staleMs = opts.staleMs || 15000;
  const t0 = Date.now();
  for (;;) {
    try { fs.mkdirSync(lockPath); break; }
    catch (e) {
      if (e.code !== 'EEXIST') throw e;
      let age = null;
      try { age = Date.now() - fs.statSync(lockPath).mtimeMs; } catch { continue; }
      if (age > staleMs) {
        // vol ATOMIQUE par rename : un seul gagnant. L'ancien rmdir+mkdir
        // laissait deux voleurs entrer ensemble dans la section critique
        // (revue adversariale 2026-08-30) - le perdant du rename repart
        // simplement dans la boucle.
        const morgue = `${lockPath}.steal.${process.pid}.${Date.now()}`;
        try { fs.renameSync(lockPath, morgue); fs.rmdirSync(morgue); } catch {}
        continue;
      }
      if (Date.now() - t0 > timeoutMs) throw new Error(`verrou ${lockPath} bloque depuis ${age} ms`);
      sleepSync(25);
    }
  }
  try { return fn(); }
  finally { try { fs.rmdirSync(lockPath); } catch {} }
}

// Decoupe un Buffer en lignes COMPLETES (terminees par \n), en comptant les
// octets consommes : les offsets de l'outbox se suivent en octets pour les
// lectures HTTP Range, une ligne partielle en queue attend le tick suivant.
function splitCompleteLines(buf) {
  const lines = [];
  let start = 0;
  let consumed = 0;
  for (;;) {
    const nl = buf.indexOf(0x0a, start);
    if (nl === -1) break;
    lines.push(buf.slice(start, nl).toString('utf8'));
    consumed = nl + 1;
    start = nl + 1;
  }
  return { lines, consumed };
}

module.exports = {
  CorruptStateError,
  readJsonStrict,
  readJsonLoose,
  writeJsonAtomic,
  appendLine,
  withLock,
  splitCompleteLines,
};
