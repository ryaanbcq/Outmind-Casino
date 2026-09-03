package net.thundranode.buckshot.paper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.thundranode.buckshot.Animateur;
import net.thundranode.buckshot.Fusil;
import net.thundranode.buckshot.jeu.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.UUID;

/**
 * Duel joueur contre joueur a la table de Buckshot : un seul round, deux
 * humains qui misent leur propre argent, le gagnant rafle le pot. DrDonutt
 * quitte la table le temps du duel, le second joueur prend sa place.
 *
 * <p>Frere de {@link ControleurPartie} plutot que variante : le solo ne bouge
 * pas d'une ligne, les deux controleurs se partagent la table via les
 * verrous d'occupation poses par le plugin.
 */
public final class ControleurDuel {

    private static final net.kyori.adventure.sound.Sound SON_IMPACT =
            net.kyori.adventure.sound.Sound.sound(
                    net.kyori.adventure.key.Key.key("rr", "hit.oof"),
                    net.kyori.adventure.sound.Sound.Source.PLAYER, 1f, 1f);

    private static final net.kyori.adventure.sound.Sound SON_MORT =
            net.kyori.adventure.sound.Sound.sound(
                    net.kyori.adventure.key.Key.key("rr", "hit.mort"),
                    net.kyori.adventure.sound.Sound.Source.PLAYER, 1f, 1f);

    private static final net.kyori.adventure.sound.Sound SON_DEFIB =
            net.kyori.adventure.sound.Sound.sound(
                    net.kyori.adventure.key.Key.key("rr", "hit.defib"),
                    net.kyori.adventure.sound.Sound.Source.PLAYER, 1f, 1f);

    /** Defi pose sur la table, en attente d'un adversaire. */
    private record Defi(UUID challenger, String nom, long mise, BukkitTask expiration) { }

    private final JavaPlugin plugin;
    private final Regles regles;
    private final Animateur animateur;
    private final InventairePartie inventaire;
    private final EcranNoir ecranNoir;
    private final Banque banque;
    private final MiseEnScene scene;
    private final BarreVie barreVie;
    /** SecureRandom : l'ordre du chargeur est de l'argent, un Random nu se predit. */
    private final java.security.SecureRandom aleatoire = new java.security.SecureRandom();
    private final int loupeTenueTicks;

    private SessionDuel session;
    private Defi defi;
    private long miseCourante;
    private BukkitTask suiviVisee;
    /** Poses imposees hors visee (inspection loupe), par joueur. */
    private final java.util.Set<UUID> posesForcees = new java.util.HashSet<>();
    private int cartouchesVisibles;
    private int capaciteVisible;
    private final java.util.Map<UUID, org.bukkit.Location> respawnsDiriges =
            new java.util.HashMap<>();
    private final java.util.Map<UUID, Integer> graceTirBedrock = new java.util.HashMap<>();
    /** Vrai si ce joueur est deja pris ailleurs (solo ou duel d'une autre table). */
    private java.util.function.Predicate<UUID> occupeAilleurs;
    /** Vrai si une partie SOLO tourne a cette meme table. */
    private java.util.function.BooleanSupplier soloEnCours;

    public ControleurDuel(JavaPlugin plugin, Regles regles, Animateur animateur,
                          InventairePartie inventaire, EcranNoir ecranNoir,
                          Banque banque, MiseEnScene scene, int loupeTenueTicks) {
        this.plugin = plugin;
        this.regles = regles;
        this.animateur = animateur;
        this.inventaire = inventaire;
        this.ecranNoir = ecranNoir;
        this.banque = banque;
        this.scene = scene;
        this.barreVie = new BarreVie(regles.viesPlafond());
        this.loupeTenueTicks = loupeTenueTicks;
    }

    public void verrouExterne(java.util.function.Predicate<UUID> verrou) {
        this.occupeAilleurs = verrou;
    }

    public void verrouSolo(java.util.function.BooleanSupplier verrou) {
        this.soloEnCours = verrou;
    }

    /** Boucle d'entretien d'une table DUEL : le solo n'y tourne pas, donc sa
     *  boucle d'approche (qui portait la reparation des chunks decharges)
     *  n'existe pas - celle-ci ne fait QUE reparer, jamais demarrer. */
    private BukkitTask tacheReparation;

    public void surveillerReparation() {
        tacheReparation = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (session == null) scene.reparerSiNecessaire();
        }, 40L, 10L);
    }

    // ---- Statut ----

    /** Vrai des qu'un duel se joue a cette table. */
    public boolean enCours() {
        return session != null;
    }

    /** Vrai si la table est reservee par le duel : partie OU defi en attente. */
    public boolean occupeTable() {
        return session != null || defi != null;
    }

    /** Vrai si ce joueur joue un duel ici ou a un defi pose ici. */
    public boolean estConcerne(UUID joueurId) {
        return estEnPartie(joueurId)
                || (defi != null && defi.challenger().equals(joueurId));
    }

    /** Nom du provocateur du defi en attente, ou null : sert au routage du JOIN. */
    public String nomDefi() {
        return defi == null ? null : defi.nom();
    }

    public boolean estEnPartie(UUID joueurId) {
        return session != null && session.participe(joueurId);
    }

    public boolean estVerrouille(UUID joueurId) {
        return estEnPartie(joueurId) && session.verrouille();
    }

    public InventairePartie inventaire() {
        return inventaire;
    }

    // ---- Defi ----

    private long miseMin() {
        return plugin.getConfig().getLong("duel.mise-min",
                plugin.getConfig().getLong("gains.mise-min", 100_000L));
    }

    private long miseMax() {
        return plugin.getConfig().getLong("duel.mise-max",
                plugin.getConfig().getLong("gains.mise-max", 10_000_000L));
    }

    /** /rr duel <montant> : pose un defi sur la table, mise debitee d'avance. */
    public void proposer(Player joueur, long montant) {
        if (joueur.isDead()) {
            joueur.sendMessage(Component.text("You cannot duel while dead.", NamedTextColor.RED));
            return;
        }
        if (occupeAilleurs != null && occupeAilleurs.test(joueur.getUniqueId())) {
            joueur.sendMessage(Component.text("You are already in a game.", NamedTextColor.RED));
            return;
        }
        if (session != null) {
            joueur.sendMessage(Component.text("A duel is already running at this table.",
                    NamedTextColor.RED));
            return;
        }
        if (defi != null) {
            joueur.sendMessage(Component.text(defi.challenger().equals(joueur.getUniqueId())
                    ? "Your challenge is already up. /rr duel annuler to take it back."
                    : defi.nom() + " already has a challenge up: /rr duel accepter to face them.",
                    NamedTextColor.RED));
            return;
        }
        if (soloEnCours != null && soloEnCours.getAsBoolean()) {
            joueur.sendMessage(Component.text("A game is running at this table. Wait for the end.",
                    NamedTextColor.RED));
            return;
        }
        if (!scene.configuree()) {
            joueur.sendMessage(Component.text("The Buckshot table is not set up.", NamedTextColor.RED));
            return;
        }
        if (!scene.aPortee(joueur)) {
            joueur.sendMessage(Component.text("Step closer to the table.", NamedTextColor.RED));
            return;
        }
        long mise = montant;
        if (banque.disponible()) {
            if (mise < miseMin() || mise > miseMax()) {
                joueur.sendMessage(Component.text("Bet must be between $"
                        + net.thundranode.buckshot.Mises.formater(miseMin()) + " and $"
                        + net.thundranode.buckshot.Mises.formater(miseMax()) + ".",
                        NamedTextColor.RED));
                return;
            }
            long solde = banque.solde(joueur);
            if (solde < mise) {
                joueur.sendMessage(Component.text("You only have $"
                        + net.thundranode.buckshot.Mises.formater(solde) + ".", NamedTextColor.RED));
                return;
            }
            if (!banque.debiter(joueur, mise)) {
                joueur.sendMessage(Component.text("Payment failed, try again.", NamedTextColor.RED));
                return;
            }
        } else {
            mise = 0;
        }
        long expirationTicks = plugin.getConfig().getLong("duel.defi-ticks", 1200L);
        UUID challengerId = joueur.getUniqueId();
        BukkitTask expiration = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (defi == null || !defi.challenger().equals(challengerId)) return;
            Defi perime = defi;
            defi = null;
            Player challenger = Bukkit.getPlayer(perime.challenger());
            if (challenger != null) {
                rembourserMise(challenger, perime.mise());
                challenger.sendMessage(Component.text(
                        "Nobody picked up the duel. Your bet is back.", NamedTextColor.GRAY));
            }
        }, expirationTicks);
        defi = new Defi(challengerId, joueur.getName(), mise, expiration);
        // Matchmaking a l'echelle du serveur : tout le monde voit la mise et
        // le premier qui clique JOIN paie la meme et la partie demarre. Le
        // bouton porte le nom du provocateur pour viser la bonne table,
        // meme depuis un autre monde.
        var annonce = Component.text()
                .append(Component.text(joueur.getName() + " bet $"
                        + net.thundranode.buckshot.Mises.formater(mise)
                        + " on Donut's Buckshot ", NamedTextColor.GOLD))
                .append(Component.text("[JOIN]", NamedTextColor.GREEN,
                                net.kyori.adventure.text.format.TextDecoration.BOLD)
                        .clickEvent(net.kyori.adventure.text.event.ClickEvent
                                .runCommand("/rr duel accepter " + joueur.getName()))
                        .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                                Component.text("Pay $"
                                        + net.thundranode.buckshot.Mises.formater(mise)
                                        + " and face " + joueur.getName() + " in a 1v1",
                                        NamedTextColor.YELLOW))))
                // Bedrock ne clique pas les composants du chat : la commande
                // reste lisible en clair pour eux.
                .append(Component.text(" (/rr duel accepter)", NamedTextColor.DARK_GRAY))
                .build();
        joueur.sendMessage(Component.text("Challenge placed: $"
                + net.thundranode.buckshot.Mises.formater(mise)
                + ". Waiting for an opponent (60 s).", NamedTextColor.GOLD));
        for (Player enLigne : Bukkit.getOnlinePlayers()) {
            if (!enLigne.equals(joueur)) enLigne.sendMessage(annonce);
        }
    }

    /** /rr duel annuler : le provocateur reprend sa mise. */
    public void annulerDefi(Player joueur) {
        if (defi == null || !defi.challenger().equals(joueur.getUniqueId())) {
            joueur.sendMessage(Component.text("You have no pending challenge here.",
                    NamedTextColor.RED));
            return;
        }
        retirerDefi(joueur, "Challenge cancelled, your bet is back.");
    }

    /** Depart du provocateur (quit/kick) : la mise revient avant qu'il parte. */
    public void challengerParti(Player joueur) {
        if (defi != null && defi.challenger().equals(joueur.getUniqueId())) {
            retirerDefi(joueur, null);
        }
    }

    private void retirerDefi(Player challenger, String message) {
        Defi courant = defi;
        defi = null;
        courant.expiration().cancel();
        rembourserMise(challenger, courant.mise());
        if (message != null) {
            challenger.sendMessage(Component.text(message, NamedTextColor.GRAY));
        }
    }

    private void rembourserMise(Player joueur, long mise) {
        if (mise > 0 && banque.disponible() && !banque.crediter(joueur, mise)) {
            plugin.getLogger().severe("[Buckshot] remboursement duel de " + mise
                    + "$ a " + joueur.getName() + " refuse par Vault");
        }
    }

    /** /rr duel accepter : debite l'accepteur et lance le duel. */
    public void accepter(Player joueur) {
        accepter(joueur, null);
    }

    /**
     * {@code nomChallenger} : nom porte par le bouton JOIN, ou null si tape
     * a la main. S'il est donne, il doit designer LE defi en attente ici :
     * un defi remplace entre le clic et son traitement ne se fait pas
     * accepter par erreur (et payer) a la place de l'autre.
     */
    public void accepter(Player joueur, String nomChallenger) {
        if (defi == null) {
            joueur.sendMessage(Component.text("No duel to accept here. /rr duel <amount> to start one.",
                    NamedTextColor.RED));
            return;
        }
        if (nomChallenger != null && !nomChallenger.equalsIgnoreCase(defi.nom())) {
            joueur.sendMessage(Component.text("That challenge is gone.", NamedTextColor.RED));
            return;
        }
        if (joueur.isDead()) {
            joueur.sendMessage(Component.text("You cannot duel while dead.", NamedTextColor.RED));
            return;
        }
        if (defi.challenger().equals(joueur.getUniqueId())) {
            joueur.sendMessage(Component.text("You cannot accept your own challenge.",
                    NamedTextColor.RED));
            return;
        }
        if (occupeAilleurs != null && occupeAilleurs.test(joueur.getUniqueId())) {
            joueur.sendMessage(Component.text("You are already in a game.", NamedTextColor.RED));
            return;
        }
        if (session != null || (soloEnCours != null && soloEnCours.getAsBoolean())) {
            joueur.sendMessage(Component.text("The table is already taken.", NamedTextColor.RED));
            return;
        }
        // Pas de condition de distance : le JOIN part du chat, d'ou qu'on
        // soit - l'installation teleporte les deux joueurs a la table.
        Player challenger = Bukkit.getPlayer(defi.challenger());
        if (challenger == null || !challenger.isOnline()) {
            // Ne devrait pas arriver (le depart rembourse et retire le defi),
            // mais un defi orphelin ne doit pas bloquer la table.
            defi.expiration().cancel();
            defi = null;
            joueur.sendMessage(Component.text("The challenger is gone.", NamedTextColor.RED));
            return;
        }
        if (challenger.isDead()) {
            joueur.sendMessage(Component.text("The challenger is dead right now, try again in a moment.",
                    NamedTextColor.RED));
            return;
        }
        long mise = defi.mise();
        if (mise > 0 && banque.disponible()) {
            long solde = banque.solde(joueur);
            if (solde < mise) {
                joueur.sendMessage(Component.text("You need $"
                        + net.thundranode.buckshot.Mises.formater(mise) + " to accept (you have $"
                        + net.thundranode.buckshot.Mises.formater(solde) + ").", NamedTextColor.RED));
                return;
            }
            if (!banque.debiter(joueur, mise)) {
                joueur.sendMessage(Component.text("Payment failed, try again.", NamedTextColor.RED));
                return;
            }
        }
        Defi accepte = defi;
        defi = null;
        accepte.expiration().cancel();
        if (!demarrerDuel(challenger, joueur, mise)) {
            rembourserMise(challenger, mise);
            rembourserMise(joueur, mise);
            joueur.sendMessage(Component.text("Duel could not start, bets refunded.",
                    NamedTextColor.RED));
            challenger.sendMessage(Component.text("Duel could not start, bets refunded.",
                    NamedTextColor.RED));
        }
    }

    // ---- Deroulement ----

    private boolean demarrerDuel(Player j1, Player j2, long mise) {
        if (session != null || !scene.configuree()) return false;
        miseCourante = mise;
        MoteurPartie moteur = new MoteurPartie(regles, aleatoire, null, 1);
        SessionDuel nouvelle = new SessionDuel(j1.getUniqueId(), j2.getUniqueId(), moteur);
        inventaire.sauvegarder(j1, nouvelle.id());
        inventaire.sauvegarder(j2, nouvelle.id());
        session = nouvelle;
        // DrDonutt cede sa place : ses coeurs d'abord, puis lui.
        scene.masquerChuteJoueur();
        scene.cacherDealerPourDuel();
        scene.installerJoueur(j1);
        scene.installerJoueur2(j2);
        barreVie.installer(j1, moteur.participant(Acteur.JOUEUR).vies());
        barreVie.installer(j2, moteur.participant(Acteur.DEALER).vies());
        scene.montrerViesJoueur(j1, moteur.participant(Acteur.JOUEUR).vies(),
                moteur.participant(Acteur.JOUEUR).viesClope(), regles.viesPlafond());
        scene.montrerViesJoueur2(j2, moteur.participant(Acteur.DEALER).vies(),
                moteur.participant(Acteur.DEALER).viesClope(), regles.viesPlafond());
        scene.demarrerMusique(j1);
        cartouchesVisibles = 0;
        capaciteVisible = 0;
        posesForcees.clear();
        long pot = mise * 2;
        var annonce = Component.text(j1.getName() + " vs " + j2.getName()
                + (pot > 0 ? " - $" + net.thundranode.buckshot.Mises.formater(pot) + " pot."
                : " - free duel."), NamedTextColor.GOLD);
        pourJoueurs(j -> j.sendMessage(annonce));
        annoncerTemoins(annonce);
        suiviVisee = Bukkit.getScheduler().runTaskTimer(plugin,
                () -> rafraichirVisee(nouvelle), 1L, 1L);
        traiter(nouvelle, moteur.demarrer());
        return true;
    }

    public boolean tirer(Player joueur, Cible cible) {
        SessionDuel courante = sessionJoueur(joueur);
        if (courante == null || courante.verrouille()) return false;
        Acteur acteur = courante.acteurDe(joueur.getUniqueId());
        ResultatAction action = courante.moteur().tirer(acteur, cible);
        if (!action.acceptee()) {
            erreur(joueur, action.erreur());
            return false;
        }
        courante.verrouiller(true);
        courante.demanderPompe(acteur);
        String visee = cible == Cible.SOI ? "aim_self" : "aim_front";
        lancerTir(courante, acteur, cible, chronologie(action, visee));
        return true;
    }

    public boolean utiliser(Player joueur, Objet objet) {
        SessionDuel courante = sessionJoueur(joueur);
        if (courante == null || courante.verrouille()) return false;
        Acteur acteur = courante.acteurDe(joueur.getUniqueId());
        ResultatAction action = courante.moteur().utiliser(acteur, objet);
        if (!action.acceptee()) {
            erreur(joueur, action.erreur());
            return false;
        }
        if (objet != Objet.LOUPE) courante.demanderPompe(acteur);
        if (objet == Objet.LOUPE) {
            courante.verrouiller(true);
            joueur.getInventory().setItemInMainHand(Fusil.creer());
            // Cartouche NEUTRE dans le port : le custom_model_data de l'item
            // tenu est visible du client d'en face, la couleur de la charge
            // trahirait la lecture. Le resultat ne passe que par le message
            // prive de ChambrePrivee.
            String vue = Animateur.inspection(null);
            programmer(courante, 1L, () -> animateur.jouerInspection(joueur, loupeTenueTicks, vue));
            posesForcees.add(joueur.getUniqueId());
            scene.montrerPose(joueur, "inspect");
            programmer(courante, 2L, () -> scene.brasInspection(joueur, true));
            programmer(courante, 1L + animateur.dureeInspection(loupeTenueTicks), () -> {
                posesForcees.remove(joueur.getUniqueId());
                scene.brasInspection(joueur, false);
                scene.cacherPose(joueur);
                traiter(courante, action);
            });
        } else if (objet == Objet.CIGARETTES) {
            courante.verrouiller(true);
            scene.fumerCigarette(joueur, true, () -> traiter(courante, action));
        } else if (objet == Objet.BIERE) {
            courante.verrouiller(true);
            scene.boireBiere(joueur, plugin.getConfig().getInt("animations.biere-ticks", 22),
                    () -> traiter(courante, action));
        } else {
            traiter(courante, action);
        }
        return true;
    }

    private void lancerTir(SessionDuel courante, Acteur acteur, Cible cible,
                           ChronologieTir chronologie) {
        Player tireur = joueur(courante, acteur);
        if (tireur == null) return;
        boolean reelle = chronologie.blackoutTicks() > 0;
        if (reelle) animateur.jouerForce(tireur, "fire");
        else animateur.jouerTirABlanc(tireur);
        programmer(courante, 2L, () -> scene.ejecterCartouche(acteur, reelle, tireur));
        if (reelle) {
            var impact = courante.moteur().tirEnAttenteMortel() ? SON_MORT : SON_IMPACT;
            pourJoueurs(j -> j.playSound(impact));
            for (Player temoin : temoins(courante)) temoin.playSound(impact);
        }
        programmer(courante, chronologie.attenteAvantResolutionTicks(),
                () -> resoudreTir(courante, acteur, cible, chronologie));
    }

    private void resoudreTir(SessionDuel courante, Acteur acteur, Cible cible,
                             ChronologieTir chronologie) {
        Player tireur = joueur(courante, acteur);
        if (tireur == null) return;
        if (chronologie.blackoutTicks() == 0) {
            traiter(courante, courante.moteur().reveler());
            return;
        }
        // Defibrillateur pour celui qui encaisse un coup reel non mortel :
        // meme rituel qu'en solo, mais la cible peut etre l'un ou l'autre.
        Acteur acteurCible = cible == Cible.SOI ? acteur : acteur.oppose();
        if (!courante.moteur().tirEnAttenteMortel()) {
            Player touche = joueur(courante, acteurCible);
            if (touche != null) {
                touche.playSound(SON_DEFIB);
                programmer(courante, chronologie.blackoutTicks(), () -> {
                    Player vivant = joueur(courante, acteurCible);
                    if (vivant != null) scene.defibrillation(vivant);
                });
            }
        }
        // Un seul rappel de revelation : l'ecran du tireur porte la suite,
        // celui de l'autre joueur et des temoins n'est que du noir.
        ecranNoir.afficher(tireur, chronologie.blackoutTicks(), () -> {
            if (active(courante)) traiter(courante, courante.moteur().reveler());
        });
        Player autre = joueur(courante, acteur.oppose());
        if (autre != null) ecranNoir.afficher(autre, chronologie.blackoutTicks(), () -> { });
        for (Player temoin : temoins(courante)) {
            ecranNoir.afficher(temoin, chronologie.blackoutTicks(), () -> { });
        }
    }

    private void traiter(SessionDuel courante, ResultatAction resultat) {
        if (!active(courante)) return;
        if (!resultat.acceptee()) {
            plugin.getLogger().warning("[Buckshot] duel : action refusee - " + resultat.erreur());
            return;
        }
        traiterEvenements(courante, resultat.evenements());
    }

    private void traiterEvenements(SessionDuel courante,
                                   java.util.List<EvenementPartie> evenements) {
        if (!active(courante)) return;
        Player j1 = joueur(courante, Acteur.JOUEUR);
        Player j2 = joueur(courante, Acteur.DEALER);
        if (j1 == null || j2 == null) return;
        boolean fin = false;
        Acteur vainqueur = null;
        Acteur dernierBuveur = null;
        for (EvenementPartie evenement : evenements) {
            if (evenement instanceof EvenementPartie.RoundCommence) {
                var titre = net.kyori.adventure.title.Title.title(
                        Component.text("DUEL", NamedTextColor.GOLD), Component.empty());
                pourJoueurs(j -> j.showTitle(titre));
                for (Player temoin : temoins(courante)) temoin.showTitle(titre);
            } else if (evenement instanceof EvenementPartie.ChargeurAnnonce e) {
                cartouchesVisibles = e.reelles() + e.blanches();
                capaciteVisible = cartouchesVisibles;
                var annonceReelles = Component.text(
                        e.reelles() + " live round" + (e.reelles() > 1 ? "s" : "") + ".",
                        NamedTextColor.RED);
                var annonceBlanches = Component.text(
                        e.blanches() + " blank" + (e.blanches() > 1 ? "s" : "") + ".",
                        NamedTextColor.WHITE);
                var titreChargeur = net.kyori.adventure.title.Title.title(
                        Component.text()
                                .append(Component.text(e.reelles() + " RED", NamedTextColor.RED))
                                .append(Component.text("   ", NamedTextColor.WHITE))
                                .append(Component.text(e.blanches() + " BLANK", NamedTextColor.GRAY))
                                .build(),
                        Component.empty());
                pourJoueurs(j -> {
                    j.sendMessage(annonceReelles);
                    j.sendMessage(annonceBlanches);
                    j.showTitle(titreChargeur);
                });
                for (Player temoin : temoins(courante)) {
                    temoin.sendMessage(annonceReelles);
                    temoin.sendMessage(annonceBlanches);
                    temoin.showTitle(titreChargeur);
                }
            } else if (evenement instanceof EvenementPartie.ObjetsDistribues e) {
                Player proprio = joueur(courante, e.acteur());
                Player adversaire = joueur(courante, e.acteur().oppose());
                if (proprio != null) {
                    proprio.sendMessage(Component.text("You get "
                            + e.objets().size() + " item(s).", NamedTextColor.AQUA));
                }
                var pourLesAutres = Component.text(nomActeur(courante, e.acteur()) + " gets "
                        + e.objets().size() + " item(s).", NamedTextColor.AQUA);
                if (adversaire != null) adversaire.sendMessage(pourLesAutres);
                annoncerTemoins(pourLesAutres);
            } else if (evenement instanceof EvenementPartie.ObjetUtilise e) {
                var annonce = Component.text(nomActeur(courante, e.acteur()) + " uses "
                        + nomObjet(e.objet()) + ".", NamedTextColor.GOLD);
                pourJoueurs(j -> j.sendMessage(annonce));
                annoncerTemoins(annonce);
                if (e.objet() == Objet.BIERE) dernierBuveur = e.acteur();
            } else if (evenement instanceof EvenementPartie.ChambrePrivee e) {
                // SECRET : contrairement au solo, l'adversaire est un humain
                // assis en face - la lecture de la loupe ne sort pas de
                // l'ecran de celui qui regarde. Pas de relais aux temoins.
                Player lecteur = joueur(courante, e.acteur());
                if (lecteur != null) {
                    lecteur.sendMessage(Component.text("Magnifier: " + nomCartouche(e.type())
                            + " round.", NamedTextColor.LIGHT_PURPLE));
                }
            } else if (evenement instanceof EvenementPartie.CartoucheRevelee e) {
                cartouchesVisibles = Math.max(0, cartouchesVisibles - 1);
                if (e.ejectee()) {
                    boolean cartoucheReelle = e.type() == TypeCartouche.REELLE;
                    var annonceEjection = Component.text(
                            "Ejected: " + nomCartouche(e.type()),
                            cartoucheReelle ? NamedTextColor.RED : NamedTextColor.WHITE);
                    pourJoueurs(j -> j.sendMessage(annonceEjection));
                    annoncerTemoins(annonceEjection);
                    Acteur buveur = dernierBuveur != null ? dernierBuveur : Acteur.JOUEUR;
                    scene.ejecterCartouche(buveur, cartoucheReelle,
                            joueur(courante, buveur));
                }
            } else if (evenement instanceof EvenementPartie.TourSaute e) {
                String suiteSaut = e.restants() > 1
                        ? " skips this turn and the next one."
                        : " skips this turn, then plays again.";
                var annonce = Component.text(nomActeur(courante, e.acteur()) + suiteSaut,
                        NamedTextColor.GRAY);
                pourJoueurs(j -> j.sendMessage(annonce));
                annoncerTemoins(annonce);
            } else if (evenement instanceof EvenementPartie.PartieTerminee e) {
                fin = true;
                vainqueur = e.vainqueur();
            }
        }
        barreVie.afficher(j1, courante.moteur().participant(Acteur.JOUEUR).vies(),
                courante.moteur().participant(Acteur.JOUEUR).viesClope());
        barreVie.afficher(j2, courante.moteur().participant(Acteur.DEALER).vies(),
                courante.moteur().participant(Acteur.DEALER).viesClope());
        scene.montrerViesJoueur(j1, courante.moteur().participant(Acteur.JOUEUR).vies(),
                courante.moteur().participant(Acteur.JOUEUR).viesClope(), regles.viesPlafond());
        scene.montrerViesJoueur2(j2, courante.moteur().participant(Acteur.DEALER).vies(),
                courante.moteur().participant(Acteur.DEALER).viesClope(), regles.viesPlafond());
        majMusique(courante);
        scene.synchroniserMenottes(
                courante.moteur().participant(Acteur.DEALER).porteMenottes(),
                courante.moteur().participant(Acteur.JOUEUR).porteMenottes(),
                j1, j2);
        if (fin) {
            finDuel(courante, vainqueur);
            return;
        }
        Acteur pompe = courante.consommerPompe();
        if (pompe == null) {
            synchroniserPhase(courante);
            return;
        }
        courante.verrouiller(true);
        Player porteur = joueur(courante, pompe);
        if (porteur != null) animateur.jouerForce(porteur, "reload");
        programmer(courante,
                ChronologieTir.animationApresClicAnnule(animateur.dureeTicks("reload")),
                () -> synchroniserPhase(courante));
    }

    private void majMusique(SessionDuel courante) {
        if (courante.moteur().participant(Acteur.JOUEUR).vies() == 0
                || courante.moteur().participant(Acteur.DEALER).vies() == 0) {
            return;
        }
        // Un round unique = un round final : le dernier coeur de n'importe
        // quel cote prend l'OST du dernier coeur final, comme en solo.
        boolean unCoeur = courante.moteur().participant(Acteur.JOUEUR).vies() == 1
                || courante.moteur().participant(Acteur.DEALER).vies() == 1;
        scene.musiqueSituation(unCoeur
                ? ScenePartie.Musique.DERNIER_COEUR_FINAL : ScenePartie.Musique.CALME);
    }

    private void synchroniserPhase(SessionDuel courante) {
        if (!active(courante)) return;
        switch (courante.moteur().phase()) {
            case RECHARGEMENT -> lancerRechargement(courante);
            case TOUR_JOUEUR -> donnerLaMain(courante, Acteur.JOUEUR);
            case TOUR_DEALER -> donnerLaMain(courante, Acteur.DEALER);
            default -> { }
        }
    }

    /** Le fusil passe directement en main : pas de rituel de ramassage en duel. */
    private void donnerLaMain(SessionDuel courante, Acteur actif) {
        Player joueurActif = joueur(courante, actif);
        Player joueurPassif = joueur(courante, actif.oppose());
        if (joueurActif == null || joueurPassif == null) return;
        courante.verrouiller(false);
        inventaire.preparerHotbar(joueurActif, courante.id(),
                courante.moteur().participant(actif), true, joueurPassif.getName());
        inventaire.preparerHotbar(joueurPassif, courante.id(),
                courante.moteur().participant(actif.oppose()), false, joueurActif.getName());
        armerGraceBedrock(joueurActif);
    }

    private void lancerRechargement(SessionDuel courante) {
        courante.verrouiller(true);
        Acteur prochain = courante.moteur().tour();
        Player recharge = joueur(courante, prochain);
        Player autre = joueur(courante, prochain.oppose());
        if (recharge == null || autre == null) return;
        inventaire.preparerAnimationFusil(recharge);
        inventaire.preparerHotbar(autre, courante.id(),
                courante.moteur().participant(prochain.oppose()), false, recharge.getName());
        animateur.jouer(recharge, "reload");
        programmer(courante, animateur.dureeTicks("reload") + 2L,
                () -> traiter(courante, courante.moteur().terminerRechargement()));
    }

    private void finDuel(SessionDuel courante, Acteur vainqueur) {
        // Le pot se paye UNE fois : un quit pendant la fenetre de fin (les
        // 100 ticks avant le nettoyage) ne doit pas declencher un forfait
        // qui repayerait - voire payerait le perdant si le gagnant part.
        courante.regler();
        Player gagnant = joueur(courante, vainqueur);
        Player perdant = joueur(courante, vainqueur.oppose());
        UUID gagnantId = courante.joueurId(vainqueur);
        UUID perdantId = courante.joueurId(vainqueur.oppose());
        long pot = miseCourante * 2;
        if (gagnant != null) {
            scene.reposerFusil(gagnant);
            if (pot > 0 && banque.disponible() && !banque.crediter(gagnant, pot)) {
                plugin.getLogger().severe("[Buckshot] pot de duel de " + pot
                        + "$ a " + gagnant.getName() + " refuse par Vault");
            }
        }
        if (perdant != null) scene.reposerFusil(perdant);
        scene.synchroniserMenottes(false, false, gagnant != null ? gagnant : perdant);
        String nomGagnant = gagnant != null ? gagnant.getName() : "?";
        var annonce = Component.text(nomGagnant + " wins the duel"
                + (pot > 0 ? " and takes $" + net.thundranode.buckshot.Mises.formater(pot)
                : "") + ".", NamedTextColor.GOLD);
        pourJoueurs(j -> j.sendMessage(annonce));
        annoncerTemoins(annonce);
        long miseAffichee = miseCourante;
        programmer(courante, 22L, () -> {
            Player vainqueurVivant = Bukkit.getPlayer(gagnantId);
            if (vainqueurVivant != null) {
                vainqueurVivant.showTitle(net.kyori.adventure.title.Title.title(
                        Component.text("VICTORY", NamedTextColor.GREEN),
                        pot > 0 ? Component.text("+$"
                                + net.thundranode.buckshot.Mises.formater(pot - miseAffichee),
                                NamedTextColor.GOLD) : Component.empty()));
            }
            Player vaincu = Bukkit.getPlayer(perdantId);
            if (vaincu != null) {
                vaincu.showTitle(net.kyori.adventure.title.Title.title(
                        Component.text("YOU LOST", NamedTextColor.RED),
                        miseAffichee > 0 ? Component.text("-$"
                                + net.thundranode.buckshot.Mises.formater(miseAffichee),
                                NamedTextColor.DARK_RED) : Component.empty()));
            }
        });
        programmer(courante, 100L, () -> {
            annulerDuel(null, false);
            tuerPourDeVrai(perdantId);
            renvoyerAuSpawn(gagnantId);
        });
    }

    /** Abandon en plein duel = forfait : l'adversaire prend le pot. */
    public void abandonner(Player joueur) {
        forfait(joueur.getUniqueId(), joueur.getName() + " gives up the duel.");
    }

    /**
     * Fin par forfait (abandon, deconnexion, mort hors jeu) : l'adversaire
     * encore la prend le pot entier, sans cinematique.
     */
    public void forfait(UUID quitteurId, String raison) {
        SessionDuel courante = session;
        if (courante == null || !courante.participe(quitteurId) || courante.reglee()) return;
        Acteur partant = courante.acteurDe(quitteurId);
        UUID gagnantId = courante.joueurId(partant.oppose());
        long pot = miseCourante * 2;
        annulerDuel(null, false);
        Player gagnant = Bukkit.getPlayer(gagnantId);
        if (gagnant != null && gagnant.isOnline()) {
            if (pot > 0 && banque.disponible() && !banque.crediter(gagnant, pot)) {
                plugin.getLogger().severe("[Buckshot] pot de forfait de " + pot
                        + "$ a " + gagnant.getName() + " refuse par Vault");
            }
            gagnant.showTitle(net.kyori.adventure.title.Title.title(
                    Component.text("VICTORY", NamedTextColor.GREEN), Component.empty()));
            gagnant.sendMessage(Component.text("Your opponent is out."
                    + (pot > 0 ? " You take the $"
                    + net.thundranode.buckshot.Mises.formater(pot) + " pot." : ""),
                    NamedTextColor.GOLD));
        }
        if (raison != null) {
            var annonce = Component.text(raison, NamedTextColor.GRAY);
            for (Player temoin : scene.spectateurs(rayonTemoins())) {
                temoin.sendMessage(annonce);
            }
        }
    }

    private void renvoyerAuSpawn(UUID joueurId) {
        Player joueur = Bukkit.getPlayer(joueurId);
        if (joueur != null && joueur.isOnline()) {
            joueur.teleport(joueur.getWorld().getSpawnLocation());
        }
    }

    private void tuerPourDeVrai(UUID joueurId) {
        Player joueur = Bukkit.getPlayer(joueurId);
        if (joueur == null || !joueur.isOnline() || joueur.isDead()) return;
        respawnsDiriges.put(joueurId, joueur.getWorld().getSpawnLocation());
        joueur.setHealth(0);
    }

    /** Destination de respawn posee par {@link #tuerPourDeVrai}, une fois. */
    public org.bukkit.Location consommerRespawnDirige(UUID joueurId) {
        return respawnsDiriges.remove(joueurId);
    }

    /**
     * Vrai pendant la mort du perdant infligee par {@link #tuerPourDeVrai} :
     * l'inventaire reel est deja rendu, l'ecouteur le garde et vide les
     * drops (les mondes sans keepInventory feraient tomber ses objets).
     */
    public boolean mortProgrammee(UUID joueurId) {
        return respawnsDiriges.containsKey(joueurId);
    }

    /**
     * Demontage du duel : taches, ecrans, inventaires, coeurs, et DrDonutt
     * reprend sa place. {@code rembourser} : fin technique, chaque joueur
     * reprend SA mise (jamais le pot).
     */
    public void annulerDuel(String message, boolean rembourser) {
        SessionDuel courante = session;
        if (courante == null) return;
        // Pot deja paye : plus aucun remboursement, sous peine de creer de
        // l'argent (pot au gagnant PUIS mises rendues aux deux).
        boolean rendreLesMises = courante.rembourserAutorise(rembourser);
        courante.annuler();
        if (suiviVisee != null) { suiviVisee.cancel(); suiviVisee = null; }
        posesForcees.clear();
        scene.masquerViesJoueur();
        scene.masquerViesJoueur2();
        Player premier = null;
        for (UUID id : courante.joueurs()) {
            Player j = Bukkit.getPlayer(id);
            if (j == null) continue;
            if (premier == null) premier = j;
            scene.cacherPose(j);
            animateur.annuler(j);
            ecranNoir.nettoyer(j);
            scene.reposerFusil(j);
            inventaire.restaurer(j);
            if (rendreLesMises && miseCourante > 0 && banque.disponible()) {
                banque.crediter(j, miseCourante);
                j.sendMessage(Component.text("Your $"
                        + net.thundranode.buckshot.Mises.formater(miseCourante)
                        + " bet was refunded.", NamedTextColor.GOLD));
            }
            if (message != null) j.sendMessage(Component.text(message, NamedTextColor.RED));
        }
        if (premier != null) {
            scene.synchroniserMenottes(false, false, premier);
            scene.arreterMusique(premier);
        }
        scene.retablirDealerApresDuel();
        if (premier != null && premier.isOnline()) scene.demarrerMusique(premier);
        miseCourante = 0;
        session = null;
    }

    /** Arret du plugin ou retrait de la table : tout le monde reprend sa mise. */
    public void arreter() {
        if (tacheReparation != null) {
            tacheReparation.cancel();
            tacheReparation = null;
        }
        if (defi != null) {
            Player challenger = Bukkit.getPlayer(defi.challenger());
            Defi courant = defi;
            defi = null;
            courant.expiration().cancel();
            if (challenger != null) rembourserMise(challenger, courant.mise());
        }
        if (session != null) annulerDuel("Duel stopped by the server.", true);
    }

    // ---- Visee, jauge, Bedrock ----

    private Component jaugeChargeur() {
        var ligne = Component.text().append(Component.text("[ ", NamedTextColor.DARK_GRAY));
        for (int i = 0; i < capaciteVisible; i++) {
            ligne.append(Component.text("■ ",
                    i < cartouchesVisibles ? NamedTextColor.WHITE : NamedTextColor.DARK_GRAY));
        }
        return ligne.append(Component.text("]", NamedTextColor.DARK_GRAY)).build();
    }

    private void rafraichirVisee(SessionDuel courante) {
        if (!active(courante)) return;
        Component jauge = jaugeChargeur();
        for (Player temoin : scene.spectateurs(rayonTemoins())) {
            temoin.sendActionBar(jauge);
        }
        for (Acteur acteur : Acteur.values()) {
            Player j = joueur(courante, acteur);
            if (j == null) continue;
            j.sendActionBar(jauge);
            if (posesForcees.contains(j.getUniqueId())) continue;
            String type = inventaire.type(j.getInventory().getItemInMainHand());
            boolean enJoue = type != null && type.startsWith("shot:")
                    && j.isHandRaised()
                    && j.getItemInUseTicks() >= animateur.dureeTicks("aim_front");
            if (enJoue) scene.montrerPose(j, "shot:self".equals(type) ? "self" : "front");
            else scene.cacherPose(j);
        }
    }

    private void armerGraceBedrock(Player joueur) {
        graceTirBedrock.put(joueur.getUniqueId(), Bukkit.getCurrentTick() + 10);
    }

    public boolean tirBedrockAutorise(UUID joueurId) {
        Integer jusqu = graceTirBedrock.get(joueurId);
        return jusqu == null || Bukkit.getCurrentTick() >= jusqu;
    }

    // ---- Outils ----

    private void programmer(SessionDuel courante, long delai, Runnable action) {
        UUID id = courante.id();
        BukkitTask tache = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (session != null && session.id().equals(id) && !session.annulee()) {
                try {
                    action.run();
                } catch (RuntimeException erreur) {
                    plugin.getLogger().log(java.util.logging.Level.SEVERE,
                            "duel " + id + " : " + erreur.getMessage(), erreur);
                    // JAMAIS de remboursement ici : le perdant annonce
                    // pourrait provoquer l'exception (warp inter-mondes)
                    // pour reprendre sa mise.
                    annulerDuel("The duel was cancelled after an error.", false);
                }
            }
        }, delai);
        courante.suivre(tache);
    }

    private ChronologieTir chronologie(ResultatAction resultat, String visee) {
        boolean reelle = resultat.evenements().stream()
                .anyMatch(EvenementPartie.BlackoutDemande.class::isInstance);
        return ChronologieTir.creer(animateur.dureeTicks(visee), reelle, regles.blackoutTicks());
    }

    private double rayonTemoins() {
        return plugin.getConfig().getDouble("staging.witness-radius", 15.0);
    }

    /** Les spectateurs dans le rayon, joueurs du duel exclus. */
    private java.util.List<Player> temoins(SessionDuel courante) {
        java.util.List<Player> proches = scene.spectateurs(rayonTemoins());
        proches.removeIf(p -> courante.participe(p.getUniqueId()));
        return proches;
    }

    private void annoncerTemoins(Component message) {
        SessionDuel courante = session;
        if (courante == null) return;
        for (Player temoin : temoins(courante)) temoin.sendMessage(message);
    }

    private void pourJoueurs(java.util.function.Consumer<Player> action) {
        SessionDuel courante = session;
        if (courante == null) return;
        for (UUID id : courante.joueurs()) {
            Player j = Bukkit.getPlayer(id);
            if (j != null) action.accept(j);
        }
    }

    private String nomActeur(SessionDuel courante, Acteur acteur) {
        Player j = joueur(courante, acteur);
        return j != null ? j.getName() : "?";
    }

    private SessionDuel sessionJoueur(Player joueur) {
        return estEnPartie(joueur.getUniqueId()) ? session : null;
    }

    private Player joueur(SessionDuel courante, Acteur acteur) {
        return Bukkit.getPlayer(courante.joueurId(acteur));
    }

    private boolean active(SessionDuel courante) {
        return session == courante && !courante.annulee();
    }

    private static String nomObjet(Objet objet) {
        return switch (objet) {
            case CIGARETTES -> "cigarettes";
            case BIERE -> "beer";
            case MENOTTES -> "handcuffs";
            case COUTEAU -> "knife";
            case LOUPE -> "the magnifier";
        };
    }

    private static String nomCartouche(TypeCartouche type) {
        return type == TypeCartouche.REELLE ? "live" : "blank";
    }

    private static void erreur(Player joueur, String texte) {
        joueur.sendMessage(Component.text(texte, NamedTextColor.RED));
    }
}
