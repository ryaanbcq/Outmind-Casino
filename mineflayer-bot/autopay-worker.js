// ============================================================
//  Outmind Casino : worker de depot automatique (BETA)
//
//  Se connecte a DonutSMP avec le compte d'un investisseur qui a
//  autorise le casino, tape /pay OutmindCompany <montant>, et sort.
//  Rien d'autre. Il ne parle a personne, ne bouge pas, ne reste pas.
//
//  POURQUOI UN PROCESS SEPARE. Une connexion mineflayer peut mourir
//  brutalement (STATUS_STACK_BUFFER_OVERRUN deja vu sur les ponts) :
//  si elle vivait dans le bot Discord, elle emporterait le panneau,
//  les retraits et le journal avec elle. Ici un crash ne coute que
//  le paiement en cours, et le bot Discord le rapporte proprement.
//
//  POURQUOI DANS CE DOSSIER ET PAS DANS discord-bot. Pour heriter du
//  mineflayer PATCHE de ce projet : le patch local de physics.js
//  envoie client_tick_end a chaque tick (PR mineflayer#3948), sans
//  quoi GrimAC kick en « Invalid sequence ». Un npm install ici
//  efface ce patch, et casse donc aussi ce worker.
//
//  Le jeton n'est jamais dechiffre sur disque : la fabrique de cache
//  chiffree de discord-bot/autodeposit.js est passee a mineflayer via
//  profilesFolder, que minecraft-protocol transmet a Authflow.
//
//  Usage : node autopay-worker.js <uuid> <player> <amount>
//  Sortie : une ligne JSON sur stdout, et rien d'autre sur stdout.
// ============================================================
const path = require('path');
// le worker est lance par le discord-bot : il lit lui-meme le .env de la banque
// (MC_HOST, BANK_ACCOUNT), sans ecraser ce que le parent lui a deja passe
require('dotenv').config({ path: path.join(__dirname, '.env'), quiet: true });
const mineflayer = require('mineflayer');
const autodeposit = require(path.join(process.env.DISCORD_BOT_DIR || path.join(__dirname, '..', 'discord-bot'), 'autodeposit.js'));
const { execSync } = require('child_process');

const HOST = process.env.MC_HOST || '';
const PORT = parseInt(process.env.MC_PORT || '25565', 10);
const BANK_ACCOUNT = process.env.BANK_ACCOUNT || ''; // pseudo Minecraft du compte banque (mineflayer-bot/.env)
if (!BANK_ACCOUNT) { console.error(JSON.stringify({ ok: false, reason: 'BANK_ACCOUNT absent de l environnement' })); process.exit(1); }

// delai entre le spawn et la commande. Le bot principal attend 8 s avant son
// premier envoi pour la meme raison : le lobby DonutSMP kick « Invalid
// sequence » les clients qui parlent trop tot apres l'arrivee.
const SETTLE_MS = 12000;
const TIMEOUT_MS = 120000;

function userEnv(name) {
  if (process.env[name]) return process.env[name];
  try {
    const out = execSync(`reg query HKCU\\Environment /v ${name}`, { encoding: 'utf8' });
    const m = out.match(/REG_(?:EXPAND_)?SZ\s+(.+)/);
    return m ? m[1].trim() : null;
  } catch { return null; }
}
autodeposit.init({ userEnv });

const [uuid, player, amountArg] = process.argv.slice(2);
const amount = Math.floor(Number(amountArg));
if (!uuid || !player || !(amount >= 1)) {
  process.stdout.write(JSON.stringify({ ok: false, reason: 'arguments invalides' }) + '\n');
  process.exit(2);
}

let done = false;
function finish(result, code) {
  if (done) return;
  done = true;
  process.stdout.write(JSON.stringify(result) + '\n');
  // laisse une chance au quit propre, puis sort quoi qu'il arrive
  setTimeout(() => process.exit(code), 1500);
}

const timer = setTimeout(() => {
  finish({ ok: false, reason: 'timeout, aucune confirmation du serveur' }, 1);
}, TIMEOUT_MS);

// Diagnostic avant connexion : le premier essai du 2026-08-16 est sorti en
// reauth sans qu'on sache si le cache etait illisible ou si Authflow l'avait
// juge perime. On ecrit sur stderr les CLES presentes et les expirations,
// jamais les valeurs, et le bot Discord journalise stderr.
try {
  const factory = autodeposit.cacheFactoryFor(uuid);
  for (const name of ['live', 'mca', 'xbl']) {
    factory({ cacheName: name, username: player }).getCached().then((v) => {
      const keys = Object.keys(v || {});
      const exp = v && v.token && (v.token.expires_on || v.token.expires_in);
      const mcaExp = v && v.mca && v.mca.expires_on;
      process.stderr.write(`[cache] ${name}: cles=[${keys}] exp=${exp || mcaExp || 'n/a'}\n`);
    }).catch(() => {});
  }
} catch (e) {
  process.stderr.write(`[cache] diagnostic impossible : ${e.message}\n`);
}

let bot;
try {
  bot = mineflayer.createBot({
    host: HOST,
    port: PORT,
    username: player,
    auth: 'microsoft',
    // la fabrique chiffree, pas un chemin : Authflow accepte les deux
    profilesFolder: autodeposit.cacheFactoryFor(uuid),
    // si Microsoft redemande un code, c'est que le refresh token a expire.
    // On ne peut pas le resoudre ici, le joueur doit reautoriser sur Discord.
    onMsaCode: () => finish({ ok: false, reason: 'reauth', message: 'autorisation expiree, il faut reautoriser depuis Discord' }, 3),
    disableChatSigning: true, // meme raison que le bot principal : kicks du chat signe 1.21+
    respawn: false,           // l'insta-respawn declenche un kick GrimAC
  });
} catch (e) {
  clearTimeout(timer);
  finish({ ok: false, reason: 'connexion impossible : ' + e.message }, 1);
}

if (bot) {
  let sent = false;

  bot.once('spawn', () => {
    setTimeout(() => {
      if (done) return;
      sent = true;
      bot.chat(`/pay ${BANK_ACCOUNT} ${amount}`);
    }, SETTLE_MS);
  });

  // Confirmation cote payeur : DonutSMP repond « You paid ... ». On accepte
  // aussi les refus connus pour rendre une raison utile au lieu d'un timeout.
  // Seul un message SYSTEME (pas de chat joueur, pas de whisper) « You paid
  // <banque> $X » avec le bon destinataire et le bon montant vaut succes : un
  // « you paid » tape dans le chat par un complice comptait comme paye
  // (audit 2026-09-03, H2). Le credit reel vient de toute facon de la
  // detection de depot du bot banque, ce resultat n'est qu'informatif.
  const { confirmMatches } = require('./lib/money');
  bot.on('message', (msg, position, sender) => {
    if (!sent || done) return;
    if (sender) return; // message signe par un joueur : jamais une confirmation
    const line = msg.toString();
    if (/^\s*<.+?>/.test(line)) return;
    const ok = line.match(/^You paid (\.?[A-Za-z0-9_]{3,16}) \$?\s*([\d.,]+\s*[kmbKMB]?)/);
    if (ok && ok[1].toLowerCase() === String(BANK_ACCOUNT).toLowerCase() && confirmMatches(amount, ok[2])) {
      clearTimeout(timer);
      try { bot.quit(); } catch {}
      finish({ ok: true, paid: amount, line: line.slice(0, 200) }, 0);
      return;
    }
    if (/(not enough|cannot afford|don't have|doesn't exist|does not exist|invalid)/i.test(line)) {
      clearTimeout(timer);
      try { bot.quit(); } catch {}
      finish({ ok: false, reason: 'refus du serveur', line: line.slice(0, 200) }, 1);
    }
  });

  bot.on('kicked', (reason) => {
    clearTimeout(timer);
    finish({ ok: false, reason: 'kick', line: String(reason).slice(0, 300) }, 1);
  });

  bot.on('error', (e) => {
    clearTimeout(timer);
    finish({ ok: false, reason: 'erreur : ' + e.message }, 1);
  });

  bot.on('end', () => {
    clearTimeout(timer);
    finish({ ok: false, reason: 'deconnecte avant confirmation' }, 1);
  });
}
