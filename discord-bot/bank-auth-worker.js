// Worker d'authentification du compte caisse (bank account), lance par
// bank.js en process separe : le flux device code Microsoft peut durer un
// quart d'heure, rien de tout ca ne doit vivre dans le process du bot
// Discord (lecon du menu /vouch tue par un execSync). Une ligne JSON par
// evenement sur stdout, comme autopay-worker.
//
// argv[2] : pseudo attendu du compte. Il sert AUSSI de cle de cache : le
//           hash des fichiers nmp-cache est calcule dessus, et MC_USERNAME
//           devra valoir exactement cette valeur pour que mineflayer
//           retrouve le jeton. bank.js garantit l'alignement des trois.
// argv[3] : JSON, liste des dossiers nmp-cache a remplir. Le premier est le
//           dossier de travail, les autres recoivent une copie (le cache du
//           compte caisse vit en double, voir push-secrets.ps1).
//
// Ce flux N'OUVRE PAS de session de jeu sur DonutSMP : l'auth s'arrete aux
// serveurs Microsoft/Xbox, la contrainte "une seule connexion a la fois
// depuis l'IP du casino" n'est pas concernee.
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { Authflow, Titles } = require('prismarine-auth');

const username = process.argv[2];
const cacheDirs = JSON.parse(process.argv[3] || '[]');
const out = (o) => process.stdout.write(JSON.stringify(o) + '\n');

if (!username || !cacheDirs.length) {
  out({ type: 'done', ok: false, reason: 'usage: bank-auth-worker <username> <dirsJson>' });
  process.exit(1);
}

const hash = crypto.createHash('sha1').update(username, 'binary').digest('hex').substring(0, 6);
const primary = cacheDirs[0];
fs.mkdirSync(primary, { recursive: true });

function wipeCache(dir) {
  for (const f of fs.readdirSync(dir)) {
    if (f.startsWith(hash + '_')) fs.unlinkSync(path.join(dir, f));
  }
}

// Repartir de zero : un vieux cache a moitie mort ferait echouer le refresh
// en silence au lieu de relancer un device code propre.
wipeCache(primary);

(async () => {
  const flow = new Authflow(
    username,
    primary,
    // exactement les options de minecraft-protocol, pour que le jeton serve
    // tel quel a la connexion mineflayer (meme lecon qu'autodeposit.js)
    { flow: 'live', authTitle: Titles.MinecraftNintendoSwitch, deviceType: 'Nintendo' },
    (code) => out({ type: 'code', user_code: code.user_code, verification_uri: code.verification_uri, expires_in: code.expires_in }),
  );
  const res = await flow.getMinecraftJavaToken({ fetchProfile: true });
  const profile = res && res.profile;
  if (!profile || !profile.name) throw new Error('no Minecraft profile on this account, does it own the game?');
  if (profile.name.toLowerCase() !== username.toLowerCase()) {
    // mauvais compte Microsoft autorise : on ne garde RIEN, sinon la caisse
    // paierait depuis un compte qui n'est pas celui affiche
    wipeCache(primary);
    out({ type: 'done', ok: false, reason: `authorized account is ${profile.name}, expected ${username}, nothing stored` });
    return;
  }
  for (const dir of cacheDirs.slice(1)) {
    fs.mkdirSync(dir, { recursive: true });
    for (const f of fs.readdirSync(primary)) {
      if (f.startsWith(hash + '_')) fs.copyFileSync(path.join(primary, f), path.join(dir, f));
    }
  }
  out({ type: 'done', ok: true, name: profile.name, id: profile.id });
})().catch(e => out({ type: 'done', ok: false, reason: e.message }));
