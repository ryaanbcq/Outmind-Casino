// ============================================================
//  Outmind Casino : autorisation du depot automatique (BETA)
//
//  BUT. Un depot ne peut pas partir de Discord : l'argent est sur
//  DonutSMP et seul le joueur peut taper /pay. Le depot automatique
//  contourne ca en agissant AVEC le compte du joueur, qui autorise
//  le casino une fois pour toutes.
//
//  CE QUE CE MODULE FAIT, ET CE QU'IL NE FAIT PAS. Il ne gere que
//  l'AUTORISATION : flux device code Microsoft, verification que le
//  compte autorise est bien celui qui est lie sur Discord, puis
//  stockage chiffre du jeton. Le /pay automatique n'est PAS ici,
//  c'est l'etape suivante et elle se branchera sur ce jeton.
//
//  POURQUOI LE DEVICE CODE ET PAS UN MOT DE PASSE. Le joueur ne
//  donne jamais son mot de passe : il ouvre microsoft.com/link,
//  tape un code a 8 caracteres, et peut tout revoquer depuis ses
//  parametres de securite Microsoft. On ne detient qu'un jeton
//  Minecraft, inutilisable pour le reste de son compte Microsoft.
//  C'est aussi le seul flux compatible avec ce que fait deja
//  minecraft-protocol : authTitle MinecraftNintendoSwitch,
//  deviceType Nintendo, flow live. Le jeton obtenu ici pourra donc
//  servir tel quel a une connexion mineflayer.
//
//  CE QUI RESTE VRAI MALGRE TOUT, ET QUE PERSONNE NE DOIT OUBLIER :
//  le partage de compte viole l'EULA Minecraft, et faire tourner le
//  compte d'un joueur depuis la machine du casino reste un motif de
//  ban DonutSMP. Le risque porte sur le compte du joueur. D'ou la
//  reserve aux investisseurs et le consentement explicite affiche
//  avant de lancer le flux.
//
//  STOCKAGE. Un dossier par joueur sous .auth, dont l'heritage ACL
//  est coupe (seuls SYSTEM et Ryan y accedent), et chaque fichier
//  de cache est chiffre en AES-256-GCM. La cle vit dans la variable
//  d'environnement User AUTODEPOSIT_KEY et n'est ecrite dans aucun
//  fichier, comme le token Discord. Perdre la variable = perdre les
//  autorisations, les joueurs devront reautoriser. C'est voulu :
//  une sauvegarde de la cle a cote des jetons ne protegerait rien.
// ============================================================
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { Authflow, Titles } = require('prismarine-auth');

const AUTH_DIR = path.join(__dirname, '.auth');

let readEnv = (name) => process.env[name];
function init(opts) { if (opts && opts.userEnv) readEnv = opts.userEnv; }

function cipherKey() {
  const hex = readEnv('AUTODEPOSIT_KEY');
  if (!hex || hex.length !== 64) {
    throw new Error('AUTODEPOSIT_KEY absente ou invalide (64 caracteres hex attendus)');
  }
  return Buffer.from(hex, 'hex');
}

function encrypt(plain, key) {
  const iv = crypto.randomBytes(12);
  const c = crypto.createCipheriv('aes-256-gcm', key, iv);
  const enc = Buffer.concat([c.update(plain, 'utf8'), c.final()]);
  return Buffer.concat([iv, c.getAuthTag(), enc]).toString('base64');
}

function decrypt(b64, key) {
  const raw = Buffer.from(b64, 'base64');
  const d = crypto.createDecipheriv('aes-256-gcm', key, raw.subarray(0, 12));
  d.setAuthTag(raw.subarray(12, 28));
  return Buffer.concat([d.update(raw.subarray(28)), d.final()]).toString('utf8');
}

// meme contrat que le FileCache de prismarine-auth, mais chiffre. Un fichier
// par cacheName : la lib en ouvre plusieurs (msa, xbl, mca...).
function encryptedCache(file, key) {
  let cache;
  const self = {
    async reset() {
      cache = {};
      fs.writeFileSync(file, encrypt(JSON.stringify(cache), key), { mode: 0o600 });
      return cache;
    },
    async getCached() {
      if (cache === undefined) {
        try {
          cache = JSON.parse(decrypt(fs.readFileSync(file, 'utf8'), key));
        } catch (e) {
          // NE PAS ecrire ici. Un reset() sur echec de lecture ecrase le jeton
          // et transforme un probleme de lecture en perte definitive : c'est ce
          // qui a vide les trois caches au premier essai du worker le
          // 2026-08-16. On rend un cache vide en memoire, le fichier reste
          // intact, et la raison part sur stderr pour etre diagnosticable.
          if (fs.existsSync(file)) {
            process.stderr.write(`[autodeposit] cache illisible ${path.basename(file)} : ${e.message}\n`);
          }
          cache = {};
        }
      }
      return cache;
    },
    async setCached(value) {
      cache = value;
      fs.writeFileSync(file, encrypt(JSON.stringify(value), key), { mode: 0o600 });
    },
    async setCachedPartial(value) {
      await self.setCached({ ...(cache || {}), ...value });
    },
  };
  return self;
}

// uuid strictement valide : un lien sans uuid tombait dans un dossier partage
const dirFor = (uuid) => {
  const u = String(uuid || '').toLowerCase();
  if (!/^[0-9a-f]{8}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{12}$/.test(u)) throw new Error('uuid Minecraft invalide');
  return path.join(AUTH_DIR, u);
};

function isAuthorized(uuid) {
  let dir;
  try { dir = dirFor(uuid); } catch { return false; }
  try { return fs.readdirSync(dir).some(f => f.endsWith('.enc')); }
  catch { return false; }
}

function revoke(uuid) {
  let dir;
  try { dir = dirFor(uuid); } catch { return; }
  try { fs.rmSync(dir, { recursive: true, force: true }); return true; }
  catch { return false; }
}

// Lance le flux device code. onCode recoit {user_code, verification_uri, ...}
// des que Microsoft l'a emis, bien avant que le joueur ait autorise : c'est ce
// qu'on lui envoie en DM. La promesse ne se resout qu'apres son autorisation,
// ou expire au bout d'un quart d'heure environ.
//
// expectedName est le garde-fou central : sans lui, n'importe qui pourrait
// autoriser SON compte Microsoft sur le pseudo d'un autre et faire payer
// quelqu'un d'autre. Le jeton est jete si le profil ne correspond pas.
async function authorize({ player, uuid, expectedName, onCode }) {
  const dir = dirFor(uuid);
  fs.mkdirSync(dir, { recursive: true });
  const key = cipherKey();

  const flow = new Authflow(
    player,
    ({ cacheName }) => encryptedCache(path.join(dir, `${cacheName}.enc`), key),
    // exactement ce que minecraft-protocol utilise par defaut, pour que le
    // jeton serve tel quel a une connexion mineflayer plus tard
    { flow: 'live', authTitle: Titles.MinecraftNintendoSwitch, deviceType: 'Nintendo' },
    onCode,
  );

  const res = await flow.getMinecraftJavaToken({ fetchProfile: true });
  const profile = res && res.profile;
  if (!profile || !profile.name) {
    revoke(uuid);
    throw new Error('profil Minecraft introuvable, le compte possede-t-il le jeu');
  }
  if (profile.name.toLowerCase() !== String(expectedName).toLowerCase()) {
    revoke(uuid); // on ne garde RIEN d'un compte qui n'est pas le bon
    const e = new Error(`compte ${profile.name} au lieu de ${expectedName}`);
    e.mismatch = profile.name;
    throw e;
  }
  return { name: profile.name, id: profile.id };
}

// Fabrique de cache a passer telle quelle a mineflayer via `profilesFolder` :
// minecraft-protocol la transmet a Authflow, qui accepte un chemin OU une
// fabrique. C'est le point d'entree du worker de paiement, qui reutilise donc
// le jeton chiffre sans jamais le dechiffrer sur disque.
function cacheFactoryFor(uuid) {
  const dir = dirFor(uuid);
  const key = cipherKey();
  fs.mkdirSync(dir, { recursive: true }); // Authflow ecrit avant toute lecture
  return ({ cacheName }) => encryptedCache(path.join(dir, `${cacheName}.enc`), key);
}

module.exports = { init, authorize, isAuthorized, revoke, cacheFactoryFor, AUTH_DIR };
