// ============================================================
//  ptero : client de l'API Pterodactyl (panel Thundranode).
//
//  - timeout sur TOUT : un fetch qui pend gelait la boucle du bridge,
//    silencieusement et pour toujours (vecu 2026-08-19).
//  - readFile : endpoint contents, avec repli sur l'URL de download
//    signee quand le fichier depasse la taille max (vecu 2026-08-22 :
//    outbox trop grosse prise pour un fichier absent, reconciliation
//    figee 6 h).
//  - readTail : lecture INCREMENTALE par HTTP Range. L'outbox fait des
//    dizaines de Mo et ne fait que grandir ; la retelecharger entiere
//    toutes les 10 s brulait ~1 Mo/s de bande passante en continu.
//    On demande bytes=(offset-1)- : l'octet de recouvrement doit etre
//    le \n deja consomme, sinon le fichier a ete tronque/reecrit et on
//    s'arrete au lieu de rejouer de l'argent.
//  - compteur d'echecs consecutifs, pour le watchdog du bridge.
// ============================================================
'use strict';

module.exports = function creerPtero(env, log) {
  const PANEL = env.PTERO_PANEL_URL;
  const KEY = env.PTERO_API_KEY;
  const SRV = env.PTERO_SERVER_ID;
  if (!PANEL || !KEY || !SRV) {
    throw new Error('PTERO_PANEL_URL / PTERO_API_KEY / PTERO_SERVER_ID manquants dans .env');
  }

  let consecutiveFailures = 0;

  async function api(pathname, opts = {}) {
    try {
      const res = await fetch(`${PANEL}/api/client/servers/${SRV}${pathname}`, {
        signal: AbortSignal.timeout(20000),
        ...opts,
        headers: { Authorization: `Bearer ${KEY}`, Accept: 'application/json', ...(opts.headers || {}) },
      });
      // un 5xx durable (disque plein cote wings, panel HS) est une panne au
      // meme titre qu'un timeout : sans ca le watchdog panel-muet ne voyait
      // que les erreurs reseau et un lot pouvait rester bloque en silence
      if (res.status >= 500) consecutiveFailures++;
      else consecutiveFailures = 0;
      return res;
    } catch (e) {
      consecutiveFailures++;
      throw e;
    }
  }

  async function downloadUrl(file) {
    const dl = await api(`/files/download?file=${encodeURIComponent(file)}`);
    if (!dl.ok) return null;
    return (await dl.json()).attributes.url;
  }

  async function readFile(file) {
    const res = await api(`/files/contents?file=${encodeURIComponent(file)}`);
    if (res.status === 404) return null; // fichier pas encore cree
    if (res.status === 400) {
      // FileSizeTooLarge : passer par l'URL de download signee
      try {
        const url = await downloadUrl(file);
        if (url) {
          const big = await fetch(url, { signal: AbortSignal.timeout(60000) });
          if (big.ok) return await big.text();
        }
      } catch {}
      return null;
    }
    if (!res.ok) throw new Error(`GET ${file} -> ${res.status}`);
    return res.text();
  }

  // Queue du fichier a partir de l'octet fromByte (>= 1 : l'appelant a deja
  // consomme au moins une ligne complete, donc l'octet fromByte-1 est un \n).
  // Retourne :
  //   { status: 'ok', buf }        buf = octets nouveaux (peut etre vide)
  //   { status: 'shrunk' }         fichier tronque/reecrit -> NE PAS rejouer
  //   { status: 'missing' }        fichier absent
  async function readTail(file, fromByte) {
    const url = await downloadUrl(file);
    if (url == null) return { status: 'missing' };
    const res = await fetch(url, {
      signal: AbortSignal.timeout(60000),
      headers: { Range: `bytes=${fromByte - 1}-` },
    });
    // au-dela de la fin : wings repond 404 (constate) ou 416 selon la version.
    // fromByte-1 est un octet DEJA consomme : s'il n'existe plus, le fichier
    // a retreci.
    if (res.status === 404 || res.status === 416) return { status: 'shrunk' };
    if (!res.ok && res.status !== 206) throw new Error(`RANGE ${file} -> ${res.status}`);
    let buf = Buffer.from(await res.arrayBuffer());
    if (res.status === 200) {
      // le serveur a ignore le Range et renvoye le fichier entier
      if (buf.length < fromByte) return { status: 'shrunk' };
      buf = buf.slice(fromByte - 1);
    }
    // l'octet de recouvrement doit etre le \n de la derniere ligne consommee ;
    // autre chose = le fichier n'est plus celui qu'on croyait
    if (buf.length === 0 || buf[0] !== 0x0a) return { status: 'shrunk' };
    return { status: 'ok', buf: buf.slice(1) };
  }

  async function writeFile(file, content) {
    const res = await api(`/files/write?file=${encodeURIComponent(file)}`, {
      method: 'POST',
      body: content,
      headers: { 'Content-Type': 'text/plain' },
    });
    if (!res.ok) throw new Error(`WRITE ${file} -> ${res.status}`);
  }

  async function sendCommand(cmd) {
    // une commande console = UNE ligne : un saut de ligne glisse dans un nom
    // deviendrait une seconde commande (audit 2026-09-03, M2)
    if (typeof cmd !== 'string' || /[\r\n\x00]/.test(cmd)) throw new Error('commande console refusee (saut de ligne)');
    const res = await api('/command', {
      method: 'POST',
      body: JSON.stringify({ command: cmd }),
      headers: { 'Content-Type': 'application/json' },
    });
    if (!res.ok && res.status !== 204) throw new Error(`COMMAND ${cmd} -> ${res.status}`);
  }

  return {
    PANEL,
    SRV,
    readFile,
    readTail,
    writeFile,
    sendCommand,
    failures: () => consecutiveFailures,
  };
};
