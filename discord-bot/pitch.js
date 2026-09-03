// Salons de presentation : « qui sommes-nous », « nos valeurs », « pourquoi
// nous », « comment on fonctionne », « les avantages investisseurs », et le
// depot automatique dans #beta-test.
//
// Ce module ne contient QUE du texte et des embeds bruts, pas d'appel Discord.
// Bot.js pose les messages, les rafraichit et fabrique les boutons a partir du
// champ `buttons`. Deux raisons : le texte se relit et se corrige sans toucher
// a la mecanique, et les embeds restent testables hors ligne contre les limites
// de l'API.
//
// Regle de fond : tout chiffre affiche ici sort des vrais fichiers du casino,
// jamais d'une valeur ecrite a la main, et toute affirmation doit se verifier
// dans le code. Une vitrine qui ment est lue par des gens a qui on demande de
// deposer de l'argent. Deux promesses de securite fausses ont deja du etre
// corrigees, elles sont documentees dans le CLAUDE.md.

const COLOR = 0xa18cd1;
const COLOR_OK = 0x5cc98c;
const COLOR_GOLD = 0xffd700;
const COLOR_WARN = 0xe0a45c;

// `live` porte les chiffres deja formates par bot.js, pour qu'il n'existe
// qu'une seule implementation du formatage monetaire dans le projet.
function documents(live) {
  return [
    // ---------------------------------------------------------------- #why-us
    {
      key: 'who',
      channels: ['why-us'],
      embed: {
        color: COLOR,
        title: 'Who we are',
        description:
          `Outmind Casino runs on **${live.casinoHost}** and is banked by **${live.bankAccount}**, a real account on ${live.donutHost}.\n\n` +
          'One loop, no detours: you pay the bank in DonutSMP dollars, you play on our server, you cash out in DonutSMP dollars. ' +
          'No token, no external currency, no points that only mean something to us.',
        fields: [
          { name: 'The bank', value: `Every deposit is paid to **${live.bankAccount}** and every withdrawal comes out of it. Its balance is the vault, and the vault is on screen in **#vault**, updated every minute.`, inline: false },
          { name: 'The casino', value: `**${live.casinoHost}**. Roulette, crash, jackpot, coinflip and more. Your balance follows you between Discord, DonutSMP and the server without you lifting a finger.`, inline: false },
          { name: 'The people', value: 'A small team that answers in **#ticket** and pays out at 3am. We would rather grow slowly and never miss a payment than the other way round.', inline: false },
        ],
      },
    },

    {
      key: 'values',
      channels: ['why-us'],
      embed: {
        color: COLOR,
        title: 'Our values',
        description: 'Four rules. Each one is checkable from inside this server, which is the whole point of writing them down.',
        fields: [
          { name: '1. Everything is public', value: 'The vault is live in **#vault**. Every deposit and every cash out lands in **#past-transaction** with the player and the amount, as it happens. No screenshots, no trust me.', inline: false },
          { name: '2. We cover what we owe', value: `The vault currently holds **${live.coverage}** what players have on their balances. Limits exist to keep it that way. A casino that cannot cover its players is already broken, it just does not know it yet.`, inline: false },
          { name: '3. The edge is on the games, not on the exit', value: 'No fee on a deposit. No fee on a cash out. What you win is what you get, to the dollar.', inline: false },
          { name: '4. We say no when we have to', value: 'Daily caps are real and every player goes through them, no exception and no private deal. Anything above your cap is never lost: it goes straight back on your balance and the limit reopens at midnight. A casino that always says yes is a casino that stops paying one day.', inline: false },
        ],
      },
    },

    {
      key: 'why',
      channels: ['why-us'],
      embed: {
        color: COLOR_OK,
        title: 'Why us',
        description: 'Six answers, and not one of them asks you to take our word for it.',
        fields: [
          { name: 'We already paid', value: `**${live.paidOut}** over **${live.cashoutCount}** withdrawals. They are listed one by one in **#past-transaction**.`, inline: true },
          { name: 'The vault covers us', value: `**${live.coverage}** what we owe, live in **#vault**.`, inline: true },
          { name: 'Players rate us', value: live.ratingLine, inline: true },
          { name: 'The money can only land on you', value: 'A withdrawal is always paid to the Minecraft account that proved itself with `/verify` in game. There is nowhere to type a different destination, so even someone who got into your Discord could not send a single dollar anywhere else. One Minecraft name is linked to one Discord at a time.', inline: false },
          { name: 'The books are kept, not improvised', value: 'A full report of the day is produced every morning: profit, volume, players, deposits against cash outs. The staff reads the same numbers you do, from the same ledger.', inline: false },
          { name: 'The code is being opened', value: 'The bank bot, the bridge and this Discord bot are going open source. Anyone will be able to read exactly how a balance is credited, how a payout is sent and what we store. A casino you can audit is a casino you do not have to believe.', inline: false },
        ],
        footer: { text: 'Start with the welcome bonus if you want to try before you deposit: ' + live.welcomeBonus + ', playable, non withdrawable.' },
      },
    },

    // ----------------------------------------------------------- #how-we-work
    {
      key: 'how',
      channels: ['how-we-work', 'how-it-works'],
      embed: {
        color: COLOR,
        title: 'How it works',
        description: 'Four steps, about two minutes the first time, nothing to install.',
        fields: [
          { name: '1. Link your account', value: `Type \`/verify\` in game on **${live.casinoHost}**. You get a 6 character code, valid 10 minutes. Run \`/verify\` here on Discord and paste it. That is what proves the Minecraft account is yours, and it is the only thing that does.`, inline: false },
          { name: '2. Deposit', value: `On DonutSMP, run \`/pay ${live.bankAccount} <amount>\`. It lands on your casino balance within seconds and you get a DM to confirm. Investors can skip this step entirely, see **#beta-test**.`, inline: false },
          { name: '3. Play', value: `Join **${live.casinoHost}**. Your balance is already waiting, and every win or loss is written back to the ledger instantly.`, inline: false },
          { name: '4. Cash out', value: 'Use the panel in **#cashout**, or `/cashout` in game. The bank bot pays you on DonutSMP, usually within a minute, and the movement is posted publicly.', inline: false },
          { name: 'The limits, and why they exist', value:
            `A Gambler withdraws up to **${live.gamblerMax}** a day, an Investor up to **${live.investorMax}**.\n` +
            `On top of that the house never pays out more than ${live.houseCapText} in a single day.\n` +
            'These caps are not there to keep your money. They are what makes sure the person cashing out after you still gets paid.', inline: false },
          { name: 'If you hit a cap', value: 'Nothing is lost. The part above the cap goes straight back on your casino balance, and the limit reopens at midnight, Paris time.', inline: false },
          { name: 'If something goes wrong', value: 'Open a ticket in **#ticket**. Every movement is logged on our side with a timestamp, so a missing payment is a two minute check, not your word against ours.', inline: false },
        ],
      },
    },

    // ---------------------------------------------------------- #hi-and-perks
    {
      key: 'investors',
      channels: ['hi-and-perks', 'investors-perks'],
      embed: {
        color: COLOR_GOLD,
        title: 'Investors: what it is, and what it is not',
        description:
          'Welcome. This category is yours.\n\n' +
          `You become an Investor with \`/invest <amount>\` in game, once **${live.investorMin}** total has gone into the house.`,
        fields: [
          { name: 'Read this first', value:
            'Invested money is **given up for good**. It becomes house capital. ' +
            'There is **no dividend, no interest and no refund**. What you buy is a permanent rank and the perks below, nothing else. ' +
            'The game says the same thing before it takes a single dollar, and we would rather you read it twice than once.', inline: false },
          { name: 'Double withdrawal limit', value: `**${live.investorMax}** a day instead of ${live.gamblerMax}. The house cap still applies on top, the same for everyone.`, inline: true },
          { name: 'Investor rank', value: 'In game and on Discord, synced automatically the minute your total crosses the bar.', inline: true },
          { name: 'This category', value: 'A direct line to the staff in **#investors-chat**, and everything announced here first.', inline: true },
          ...(live.autodepositOn ? [{ name: 'Auto deposit', value: 'Deposit without leaving the game, in one tap. Optional, revocable, and explained in full in **#beta-test**.', inline: false }] : []),
          { name: 'Early access', value: 'New games, new features and changes to the limits are tried with you in **#beta-test** before anyone else sees them.', inline: false },
          { name: 'Why we ask for capital at all', value: `A casino pays winners out of its own pocket, and the size of that pocket is what lets the daily cap stay high. The vault sits at **${live.treasury}** today and covers **${live.coverage}** what players hold. Investors are the reason it can grow without payouts slowing down.`, inline: false },
        ],
      },
    },

    // -------------------------------------------------------------- #beta-test
    // publie seulement quand l'auto-depot est ouvert (AUTODEPOSIT_ENABLED=on)
    ...(live.autodepositOn ? [{
      key: 'autodeposit',
      channels: ['beta-test', 'beta'],
      embed: {
        color: COLOR_WARN,
        title: 'Auto deposit (beta)',
        description:
          `Normally a deposit means logging into DonutSMP and typing \`/pay ${live.bankAccount} <amount>\`. ` +
          'Auto deposit does that step for you, from here, in one tap.\n\n' +
          '**It is entirely optional.** It buys convenience and nothing else: same limits, same rates, same everything. ' +
          'Never taking it costs you zero.',
        fields: [
          { name: 'How the authorization works', value:
            'You open a Microsoft page and type an 8 character code. ' +
            '**We never see a password.** What we hold afterwards is a Minecraft session token, stored encrypted on the casino machine, ' +
            'and you can revoke it from this panel or from your Microsoft security settings at any moment.', inline: false },
          { name: 'What actually happens on a deposit', value:
            `The casino opens a Minecraft session as you on ${live.donutHost}, runs \`/pay ${live.bankAccount} <amount>\`, reads the confirmation and disconnects. ` +
            `Nothing else is ever typed. One payment at a time, ${live.autopayMax} maximum per payment while in beta.`, inline: false },
          { name: 'Think hard before you accept this, here or anywhere', value:
            'Handing a Minecraft token to a third party is a real risk, and it is worth saying plainly: **the account exposed is yours, not ours.** ' +
            'Account sharing goes against the Minecraft EULA, and DonutSMP can ban an account played from another machine. ' +
            '**Do not do this for people you have no reason to trust.** Not us, not anyone. ' +
            'A service asking for this and unable to show you what it does with it deserves a no.', inline: false },
          { name: 'What we do about that', value:
            'We are opening the source of the whole stack: the bank bot, the bridge and this Discord bot. ' +
            'The exact file that stores the token, the exact command sent in game, all of it readable. ' +
            'It does not remove the risk, nothing does, but it means you can check what we claim instead of believing it. ' +
            'Until then, the honest answer is that this perk is for Investors precisely because they already chose to trust the house.', inline: false },
          { name: 'Requirements', value:
            `Investor rank (**${live.investorMin}** invested), a linked account, and **you must not be logged in on ${live.donutHost}** when a payment runs. The server refuses two sessions of the same account.`, inline: false },
        ],
        footer: { text: 'Beta. Report anything odd in #investors-chat, that is what this channel is for.' },
      },
      buttons: [
        { id: 'oc_auto', label: 'Set up or manage auto deposit', style: 'primary' },
      ],
    }] : []),
  ];
}

module.exports = { documents };
