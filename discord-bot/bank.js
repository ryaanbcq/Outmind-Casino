// Gestion des comptes caisse (bank accounts) depuis Discord : lister,
// ajouter, re-authentifier (device code Microsoft) et basculer le compte que
// le bot mineflayer utilise. Concu pour etre pilotable a distance : si la
// session de la caisse meurt pendant une absence, /bank auth suffit, le code
// se tape sur microsoft.com/link depuis un telephone, aucun mot de passe ne
// circule.
//
// Le registre bank-accounts.json vit a cote du bot mineflayer, comme
// bank-state.json. La cle d'un compte est le pseudo passe a l'auth : le hash
// des fichiers nmp-cache ET la valeur MC_USERNAME du .env en derivent, les
// trois doivent rester alignes, et c'est ce module qui garantit l'alignement.
const fs = require('fs');
const os = require('os');
const path = require('path');
const crypto = require('crypto');
const { spawn, exec } = require('child_process');

const BOT_DIR = process.env.BOT_DIR || path.join(__dirname, '..', 'mineflayer-bot');
const REG_FILE = path.join(BOT_DIR, 'bank-accounts.json');
const ENV_FILE = path.join(BOT_DIR, '.env');
const CACHE_DIRS = [path.join(BOT_DIR, 'nmp-cache'), path.join(os.homedir(), '.minecraft', 'nmp-cache')];
// 14 min 30 : le device code Microsoft expire vers 15 min, et le jeton
// d'interaction Discord aussi ; en dessous des deux, le message d'echec
// atteint encore l'admin au lieu de mourir en silence.
const AUTH_TIMEOUT_MS = 14 * 60 * 1000 + 30 * 1000;

function hashOf(u) { return crypto.createHash('sha1').update(u, 'binary').digest('hex').substring(0, 6); }

function currentEnvUsername() {
  try {
    const m = fs.readFileSync(ENV_FILE, 'utf8').match(/^MC_USERNAME=(.*)$/m);
    return m ? m[1].trim() : null;
  } catch { return null; }
}

// Le .env est la source de verite du compte ACTIF : le registre s'y aligne a
// chaque lecture, et s'amorce tout seul avec la caisse deja configuree.
function readReg() {
  let reg = { active: null, accounts: {} };
  try { reg = JSON.parse(fs.readFileSync(REG_FILE, 'utf8')); } catch {}
  if (!reg.accounts) reg.accounts = {};
  const cur = currentEnvUsername();
  if (cur && !reg.accounts[cur]) reg.accounts[cur] = { addedAt: Date.now(), lastAuthAt: null };
  if (cur) reg.active = cur;
  return reg;
}
function saveReg(reg) { fs.writeFileSync(REG_FILE, JSON.stringify(reg, null, 2)); }

function hasCache(u) {
  const h = hashOf(u);
  return CACHE_DIRS.some(d => {
    try { return fs.existsSync(path.join(d, `${h}_live-cache.json`)); } catch { return false; }
  });
}

function list() {
  const reg = readReg();
  saveReg(reg);
  return Object.entries(reg.accounts).map(([name, a]) => ({
    name,
    active: name === reg.active,
    authed: hasCache(name),
    lastAuthAt: a.lastAuthAt || null,
  }));
}

let authRunning = null; // un seul flux device code a la fois sur la machine

function startAuth(username, onCode) {
  if (authRunning) return Promise.reject(new Error(`an auth flow is already running for ${authRunning}, wait for it`));
  authRunning = username;
  const reg = readReg();
  if (!reg.accounts[username]) reg.accounts[username] = { addedAt: Date.now(), lastAuthAt: null };
  saveReg(reg);
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath,
      [path.join(__dirname, 'bank-auth-worker.js'), username, JSON.stringify(CACHE_DIRS)],
      { stdio: ['ignore', 'pipe', 'pipe'] });
    let settled = false;
    const finish = (fn, arg) => { if (!settled) { settled = true; fn(arg); } };
    const timer = setTimeout(() => {
      child.kill();
      finish(reject, new Error('device code expired, run the command again'));
    }, AUTH_TIMEOUT_MS);
    let buf = '';
    child.stdout.on('data', (d) => {
      buf += d;
      let i;
      while ((i = buf.indexOf('\n')) >= 0) {
        const line = buf.slice(0, i); buf = buf.slice(i + 1);
        let ev; try { ev = JSON.parse(line); } catch { continue; }
        if (ev.type === 'code') onCode(ev);
        if (ev.type === 'done') {
          clearTimeout(timer);
          if (ev.ok) {
            const r = readReg();
            r.accounts[username].lastAuthAt = Date.now();
            r.accounts[username].profile = { name: ev.name, id: ev.id };
            saveReg(r);
            finish(resolve, ev);
          } else finish(reject, new Error(ev.reason || 'auth failed'));
        }
      }
    });
    child.stderr.on('data', d => console.warn('bank-auth-worker :', String(d).trim()));
    child.on('exit', (code) => {
      clearTimeout(timer);
      if (code !== 0) finish(reject, new Error(`auth worker died (${code})`));
    });
  }).finally(() => { authRunning = null; });
}

function use(username) {
  const reg = readReg();
  if (!reg.accounts[username]) throw new Error(`unknown account ${username}, add it first with /bank add`);
  if (!hasCache(username)) throw new Error(`no stored credentials for ${username}, run /bank auth first`);
  let env = fs.readFileSync(ENV_FILE, 'utf8');
  if (/^MC_USERNAME=/m.test(env)) env = env.replace(/^MC_USERNAME=.*$/m, `MC_USERNAME=${username}`);
  else env += `\nMC_USERNAME=${username}\n`;
  fs.writeFileSync(ENV_FILE, env);
  reg.active = username;
  saveReg(reg);
  // dotenv relit le .env a chaque demarrage du process : un pm2 restart
  // suffit ici. Le piege "pm2 restart ne relit pas l'env" ne vaut que pour
  // les variables definies dans ecosystem.config.js.
  exec('pm2 restart mineflayer-bot', (e) => { if (e) console.warn('pm2 restart mineflayer-bot :', e.message); });
  return username;
}

module.exports = { list, startAuth, use, currentEnvUsername };
