package net.thundranode.buckshot.paper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.thundranode.buckshot.Animateur;
import net.thundranode.buckshot.Fusil;
import net.thundranode.buckshot.ia.ActionIA;
import net.thundranode.buckshot.ia.StrategieDrDonutt;
import net.thundranode.buckshot.jeu.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.UUID;

public final class ControleurPartie {

    /** Impact d'une cartouche reelle non mortelle, au moment ou le noir tombe. */
    private static final net.kyori.adventure.sound.Sound SON_IMPACT =
            net.kyori.adventure.sound.Sound.sound(
                    net.kyori.adventure.key.Key.key("rr", "hit.oof"),
                    net.kyori.adventure.sound.Sound.Source.PLAYER, 1f, 1f);

    /** Coup qui vide les vies : remplace le oof, pour le joueur comme pour DrDonutt. */
    private static final net.kyori.adventure.sound.Sound SON_MORT =
            net.kyori.adventure.sound.Sound.sound(
                    net.kyori.adventure.key.Key.key("rr", "hit.mort"),
                    net.kyori.adventure.sound.Sound.Source.PLAYER, 1f, 1f);

    /**
     * Defibrillateur pendant l'ecran noir (fichier fourni par l'utilisateur
     * le 2026-08-27). Il porte sa propre seconde de charge avant la premiere
     * decharge : c'est elle qui fait le silence demande, inutile de retarder
     * le declenchement. Ne part que sur un coup encaisse par le joueur et non
     * mortel -- un coup qui tue a deja son bruit de cervelle.
     */
    private static final net.kyori.adventure.sound.Sound SON_DEFIB =
            net.kyori.adventure.sound.Sound.sound(
                    net.kyori.adventure.key.Key.key("rr", "hit.defib"),
                    net.kyori.adventure.sound.Sound.Source.PLAYER, 1f, 1f);

    private final JavaPlugin plugin;
    private final Regles regles;
    private final Animateur animateur;
    private final InventairePartie inventaire;
    private final EcranNoir ecranNoir;
    private final ScenePartie scene;
    private final BarreVie barreVie;
    private final StrategieDrDonutt strategie = new StrategieDrDonutt();
    /** SecureRandom : l'ordre du chargeur est de l'argent, un Random nu se predit. */
    private final java.security.SecureRandom aleatoire = new java.security.SecureRandom();
    private final int reflexionMin;
    private final int reflexionMax;
    private final int viseeDealerTicks;
    private final int objetDealerTicks;
    private final int loupeTenueTicks;
    /** Suit la mise en joue du joueur pour porter sa pose cote spectateurs. */
    private BukkitTask suiviVisee;
    /** Boucle d'auto-start a la table ; annulee quand la table est retiree. */
    private BukkitTask tacheApproche;
    /** Multi-tables : vrai si le joueur est deja en partie a une AUTRE table.
     * Sans ce verrou, cliquer le dealer d'une table voisine (aPortee = rayon
     * musique, tres large) ouvrirait une deuxieme session en parallele. */
    private java.util.function.Predicate<UUID> occupeAilleurs;

    public void verrouExterne(java.util.function.Predicate<UUID> verrou) {
        this.occupeAilleurs = verrou;
    }

    /**
     * Duel PvP (2026-08-30) : vrai quand la table est reservee par le duel
     * (defi pose ou partie en cours). Suspend la boucle d'approche ET la
     * reparation automatique -- reparerSiNecessaire reconstruirait la scene
     * et ferait reapparaitre DrDonutt en plein duel.
     */
    private java.util.function.BooleanSupplier occupationExterne;

    public void occupationExterne(java.util.function.BooleanSupplier verrou) {
        this.occupationExterne = verrou;
    }

    private boolean tableReserveeAilleurs() {
        return occupationExterne != null && occupationExterne.getAsBoolean();
    }
    /**
     * Vrai pendant qu'une pose est imposee hors visee (inspection a la loupe).
     * Sans ce verrou, le suivi de visee -- qui tourne a chaque tick et ne voit
     * pas d'arme en joue -- effacerait la pose des le tick suivant.
     */
    private boolean poseForcee;
    /**
     * Cartouches que le joueur a vu rester dans l'arme.
     *
     * <p>Distinct de l'etat du moteur, qui retire la cartouche des que le tir
     * est DECLARE : le dealer vise deux secondes avant de tirer, donc lire le
     * moteur faisait tomber la jauge avant le coup. La jauge suit donc le flux
     * d'evenements, qui est ce que le joueur percoit.
     */
    private int cartouchesVisibles;
    /** Taille du chargeur en cours : la jauge dessine autant de logements. */
    private int capaciteVisible;
    private SessionPartie session;
    /** Round en cours, pour choisir le pool de repliques au moment d'un tir. */
    private int roundCourant;
    /** Suite d'evenements gelee entre deux rounds, relancee par /rr continuer. */
    private Runnable reprisePendante;
    /** Rounds remportes par le joueur : la base du gain affiche a la relance. */
    private int roundsGagnes;
    /** Auteur du dernier tir parti : distingue l'auto-tir du dealer d'un tir subi. */
    private Acteur dernierTireur;
    /** Depart de la derniere replique jouee, pour ne pas parler par-dessus soi. */
    private long derniereVoixMs;
    /**
     * Joueurs dont la partie vient de finir : l'approche automatique les
     * ignore tant qu'ils ne sont pas ressortis du rayon, sans quoi la table
     * les rassoirait en boucle.
     */
    private final java.util.Set<UUID> attenteSortie = new java.util.HashSet<>();
    /**
     * Joueurs invites a taper leur mise au chat. Lu depuis le thread du chat
     * asynchrone, d'ou le set concurrent.
     */
    private final java.util.Set<UUID> attenteMise =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Mise de la partie en cours, en dollars ; 0 = partie gratuite. */
    private long miseCourante;
    private final Banque banque;
    /** Respawns a diriger vers le spawn du monde de jeu apres un /kill. */
    private final java.util.Map<UUID, org.bukkit.Location> respawnsDiriges =
            new java.util.HashMap<>();
    /** Vrai des le premier coup encaisse par le joueur au round final. */
    private boolean joueurToucheAuRound3;
    /** Vrai tant que le fusil du tour est pose sur la table, pas en main. */
    private boolean attentePriseFusil;
    /**
     * Vrai une fois le fusil ramasse, jusqu'au tour du dealer ou au
     * rechargement : chaque action resynchronise la phase, et sans cette
     * memoire, boire une biere reposait le fusil sur la table. Un tir garde
     * (blanche sur soi) ne le fait PAS retomber -- le rituel de la prise ne
     * se rejoue qu'apres le tour de DrDonutt (demande user 2026-08-27).
     */
    private boolean fusilEnMain;
    /**
     * Pendant du precedent cote dealer : vrai des qu'il ramasse le fusil
     * pour viser, et une blanche sur lui le lui laisse en main au tour
     * suivant (demande user 2026-08-27) au lieu de le reposer.
     */
    private boolean fusilEnMainDealer;
    /**
     * Joueur dont la camera de cinematique est verrouillee : sneaker
     * demonte la camera spectateur cote client, l'ecouteur annule le
     * PlayerStopSpectatingEntityEvent tant que ce champ le designe.
     */
    private UUID cinematiqueSpectateur;

    public ControleurPartie(JavaPlugin plugin, Regles regles, Animateur animateur,
                             InventairePartie inventaire, EcranNoir ecranNoir,
                             Banque banque,
                             ScenePartie scene, int reflexionMin, int reflexionMax,
                             int viseeDealerTicks, int objetDealerTicks,
                             int loupeTenueTicks) {
        this.plugin = plugin;
        this.regles = regles;
        this.barreVie = new BarreVie(regles.viesPlafond());
        this.animateur = animateur;
        this.inventaire = inventaire;
        this.ecranNoir = ecranNoir;
        this.banque = banque;
        this.scene = scene;
        this.reflexionMin = reflexionMin;
        this.reflexionMax = reflexionMax;
        this.viseeDealerTicks = viseeDealerTicks;
        this.objetDealerTicks = objetDealerTicks;
        this.loupeTenueTicks = loupeTenueTicks;
    }

    /**
     * Le flow d'entree : s'approcher d'une table libre suffit. Toutes les
     * 10 ticks, le premier joueur autorise a moins de {@code auto-start-radius}
     * blocs (6 par defaut) est assis et la partie demarre. L'hysteresis de
     * 2 blocs sur la sortie evite de rejouer sans l'avoir voulu.
     */
    public void surveillerApproche() {
        tacheApproche = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            // Table reservee par le duel : ni auto-start ni reparation (la
            // reconstruction ressusciterait DrDonutt au milieu du duel).
            if (tableReserveeAilleurs()) return;
            // Repare une table aux references mortes (chunks decharges)
            // avant le test : sans ca, l'approche redevient silencieuse a
            // jamais apres un simple aller-retour hors du monde.
            if (!partieEnCours()) scene.reparerSiNecessaire();
            if (!scene.configuree()) return;
            double rayon = plugin.getConfig().getDouble("staging.auto-start-radius", 6.0);
            java.util.List<Player> dansLaZone = scene.spectateurs(rayon + 2.0);
            attenteSortie.removeIf(id -> {
                Player joueur = Bukkit.getPlayer(id);
                return joueur == null || !dansLaZone.contains(joueur);
            });
            // Quitter la zone annule aussi l'invitation a miser : revenir
            // refait poser la question, proprement.
            attenteMise.removeIf(id -> {
                Player joueur = Bukkit.getPlayer(id);
                return joueur == null || !dansLaZone.contains(joueur);
            });
            if (partieEnCours()) return;
            for (Player joueur : scene.spectateurs(rayon)) {
                if (attenteSortie.contains(joueur.getUniqueId())) continue;
                if (!joueur.hasPermission("buckshot.play")) continue;
                // Deja en partie ailleurs : on passe SANS message, sinon la
                // boucle le repeterait toutes les 10 ticks.
                if (occupeAilleurs != null && occupeAilleurs.test(joueur.getUniqueId())) continue;
                if (demarrer(joueur)) return;
            }
        }, 40L, 10L);
    }

    public boolean demarrer(Player joueur) {
        if (occupeAilleurs != null && occupeAilleurs.test(joueur.getUniqueId())) {
            joueur.sendMessage(Component.text("You are already in a game at another table.",
                    NamedTextColor.RED));
            return false;
        }
        if (session != null || tableReserveeAilleurs()) {
            joueur.sendMessage(Component.text("The table is already taken.", NamedTextColor.RED));
            return false;
        }
        if (!scene.configuree()) {
            joueur.sendMessage(Component.text("The Buckshot table is not set up.", NamedTextColor.RED));
            return false;
        }
        if (!scene.aPortee(joueur)) {
            joueur.sendMessage(Component.text("Step closer to the table.", NamedTextColor.RED));
            return false;
        }
        // Le casino d'abord : la partie ne se lance qu'une mise en poche
        // (workflow Outmind, user 2026-08-27). Sans economie Vault, la table
        // reste jouable gratuitement.
        if (banque.disponible()) {
            if (attenteMise.add(joueur.getUniqueId())) {
                joueur.sendMessage(Component.text()
                        .append(Component.text("Place your bet to sit down: ", NamedTextColor.GOLD))
                        .append(Component.text("type an amount in chat (e.g. 500K, 2M). ",
                                NamedTextColor.YELLOW))
                        .append(Component.text("Min $" + net.thundranode.buckshot.Mises.formater(miseMin())
                                + ", max $" + net.thundranode.buckshot.Mises.formater(miseMax()) + ".",
                                NamedTextColor.GRAY))
                        .build());
            }
            return false;
        }
        return demarrerAvecMise(joueur, 0);
    }

    private long miseMin() {
        return plugin.getConfig().getLong("gains.mise-min", 100_000L);
    }

    private long miseMax() {
        return plugin.getConfig().getLong("gains.mise-max", 10_000_000L);
    }

    /** Vrai si ce joueur est invite a taper sa mise au chat. Thread-safe. */
    public boolean attendMise(UUID joueurId) {
        return attenteMise.contains(joueurId);
    }

    /**
     * Mise recue du chat (thread asynchrone) : bascule sur le thread serveur
     * puis valide, debite via Vault et lance la partie. Le debit precede le
     * lancement ; un lancement refuse rembourse aussitot.
     */
    public void placerMise(Player joueur, long montant) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!attenteMise.contains(joueur.getUniqueId()) || session != null) return;
            if (montant < miseMin() || montant > miseMax()) {
                joueur.sendMessage(Component.text("Bet must be between $"
                        + net.thundranode.buckshot.Mises.formater(miseMin()) + " and $"
                        + net.thundranode.buckshot.Mises.formater(miseMax()) + ".",
                        NamedTextColor.RED));
                return;
            }
            long solde = banque.solde(joueur);
            if (solde < montant) {
                joueur.sendMessage(Component.text("You only have $"
                        + net.thundranode.buckshot.Mises.formater(solde) + ".", NamedTextColor.RED));
                return;
            }
            if (!banque.debiter(joueur, montant)) {
                joueur.sendMessage(Component.text("Payment failed, try again.", NamedTextColor.RED));
                return;
            }
            attenteMise.remove(joueur.getUniqueId());
            if (!demarrerAvecMise(joueur, montant)) {
                banque.crediter(joueur, montant);
                joueur.sendMessage(Component.text("Game could not start, bet refunded.",
                        NamedTextColor.RED));
            }
        });
    }

    private boolean demarrerAvecMise(Player joueur, long mise) {
        if (session != null || tableReserveeAilleurs()
                || !scene.configuree() || !scene.aPortee(joueur)) return false;
        // Re-verifie ici et pas seulement a l'invitation : entre la mise
        // tapee au chat et son traitement, le joueur a pu s'asseoir ailleurs.
        if (occupeAilleurs != null && occupeAilleurs.test(joueur.getUniqueId())) return false;
        miseCourante = mise;
        if (mise > 0) {
            annoncerTemoins(joueur, Component.text()
                    .append(Component.text(joueur.getName() + " sits down with a ",
                            NamedTextColor.GOLD))
                    .append(Component.text("$" + net.thundranode.buckshot.Mises.formater(mise),
                            NamedTextColor.YELLOW))
                    .append(Component.text(" bet.", NamedTextColor.GOLD))
                    .build());
            joueur.sendMessage(Component.text()
                    .append(Component.text("Bet placed: ", NamedTextColor.GOLD))
                    .append(Component.text("$" + net.thundranode.buckshot.Mises.formater(mise),
                            NamedTextColor.YELLOW))
                    .append(Component.text(". Good luck.", NamedTextColor.GRAY))
                    .build());
        }

        MoteurPartie moteur = new MoteurPartie(regles, aleatoire);
        SessionPartie nouvelle = new SessionPartie(joueur.getUniqueId(), moteur);
        inventaire.sauvegarder(joueur, nouvelle.id());
        session = nouvelle;
        scene.installerJoueur(joueur);
        // Le corps d'une mort precedente cede la place, et la cage de
        // barrieres se referme sur le siege : elle remplace le gel de
        // position (demande user 2026-08-27), le joueur bouge librement
        // dans son enclos d'un bloc.
        scene.masquerChuteJoueur();
        scene.poserCage();
        barreVie.installer(joueur, moteur.participant(Acteur.JOUEUR).vies());
        scene.montrerViesJoueur(joueur, moteur.participant(Acteur.JOUEUR).vies(),
                moteur.participant(Acteur.JOUEUR).viesClope(), regles.viesPlafond());
        scene.montrerViesDealer(moteur.participant(Acteur.DEALER).vies(),
                moteur.participant(Acteur.DEALER).viesClope(), regles.viesPlafond());
        scene.demarrerMusique(joueur);
        cartouchesVisibles = 0;
        capaciteVisible = 0;
        roundsGagnes = 0;
        roundCourant = 1;
        reprisePendante = null;
        joueurToucheAuRound3 = false;
        attentePriseFusil = false;
        fusilEnMain = false;
        fusilEnMainDealer = false;
        suiviVisee = Bukkit.getScheduler().runTaskTimer(plugin,
                () -> rafraichirViseeJoueur(nouvelle), 1L, 1L);
        traiter(nouvelle, moteur.demarrer());
        return true;
    }

    public boolean tirer(Player joueur, Cible cible) {
        SessionPartie courante = sessionJoueur(joueur);
        if (courante == null || courante.verrouille()) return false;
        ResultatAction action = courante.moteur().tirer(Acteur.JOUEUR, cible);
        if (!action.acceptee()) {
            erreur(joueur, action.erreur());
            return false;
        }
        courante.verrouiller(true);
        courante.demanderPompe(Acteur.JOUEUR);
        String visee = cible == Cible.SOI ? "aim_self" : "aim_front";
        lancerTir(courante, Acteur.JOUEUR, chronologie(action, visee));
        return true;
    }

    public boolean utiliser(Player joueur, Objet objet) {
        SessionPartie courante = sessionJoueur(joueur);
        if (courante == null || courante.verrouille()) return false;
        ResultatAction action = courante.moteur().utiliser(Acteur.JOUEUR, objet);
        if (!action.acceptee()) {
            erreur(joueur, action.erreur());
            return false;
        }
        // La loupe a deja son animation et son verrou : lui ajouter la pompe
        // ferait deux secondes d'attente pour une simple lecture de chambre.
        if (objet != Objet.LOUPE) courante.demanderPompe(Acteur.JOUEUR);
        if (objet == Objet.LOUPE) {
            courante.verrouiller(true);
            joueur.getInventory().setItemInMainHand(Fusil.creer());
            // La cartouche visible dans le port porte la couleur de la charge :
            // c'est la loupe elle-meme qui devient la reponse, le message de
            // chat n'est plus qu'une confirmation.
            String vue = Animateur.inspection(chambreVue(action, Acteur.JOUEUR));
            programmer(courante, 1L, () -> animateur.jouerInspection(joueur, loupeTenueTicks, vue));
            // Ce que les autres voient : la meme pose calee que celle du
            // dealer. Elle est masquee au joueur lui-meme, qui garde son
            // animation en premiere personne. Ses bras se tendent (usage
            // force cote serveur) pour que le geste se lise de dehors.
            poseForcee = true;
            scene.montrerPose(joueur, "inspect");
            programmer(courante, 2L, () -> scene.brasInspection(joueur, true));
            programmer(courante, 1L + animateur.dureeInspection(loupeTenueTicks), () -> {
                poseForcee = false;
                scene.brasInspection(joueur, false);
                scene.cacherPose(joueur);
                traiter(courante, action);
            });
        } else if (objet == Objet.CIGARETTES) {
            // La vie ne remonte que quand le megot touche le feutre : l'effet
            // colle a l'image, comme pour la loupe.
            courante.verrouiller(true);
            // Verite terrain dans la trace : le soin est deja applique par le
            // moteur, seul l'AFFICHAGE attend le megot.
            var p = courante.moteur().participant(Acteur.JOUEUR);
            Bukkit.getLogger().info(String.format("[Buckshot] clope joueur : vies=%d dont noires=%d",
                    p.vies(), p.viesClope()));
            scene.fumerCigarette(joueur, true, () -> traiter(courante, action));
        } else if (objet == Objet.BIERE) {
            // Le goulot d'abord, la douille ensuite : l'ejection attend la
            // fin du geste, comme le soin attend le megot.
            courante.verrouiller(true);
            scene.boireBiere(joueur, plugin.getConfig().getInt("animations.biere-ticks", 22),
                    () -> traiter(courante, action));
        } else {
            traiter(courante, action);
        }
        return true;
    }

    private void lancerTir(SessionPartie courante, Acteur acteur, ChronologieTir chronologie) {
        Player humain = joueur(courante);
        if (humain == null) return;
        Player animation = acteur == Acteur.JOUEUR ? humain : scene.dealerEntite().orElse(null);
        if (acteur == Acteur.DEALER) {
            scene.finViseeDealer();
            scene.montrerFusil(Acteur.DEALER, humain);
        }
        // Un blackout configure veut dire cartouche reelle. Une blanche ne
        // detone pas : clic sec, sans flamme ni recul.
        boolean reelle = chronologie.blackoutTicks() > 0;
        dernierTireur = acteur;
        if (animation != null) {
            if (reelle) animateur.jouerForce(animation, "fire");
            else animateur.jouerTirABlanc(animation);
        }
        // La douille saute AU COUP, pas apres. Elle partait a la revelation,
        // soit deux secondes plus tard une fois l'ecran noir passe : le geste
        // et son ejection paraissaient decorreles.
        programmer(courante, 2L, () -> scene.ejecterCartouche(acteur, reelle, humain));
        // L'impact part avec l'animation de tir, pas a la resolution :
        // celle-ci arrive dix ticks plus tard, et le son tombait alors
        // visiblement apres le coup. Les spectateurs proches de la table
        // prennent le meme oof que le joueur assis.
        if (reelle) {
            // Un coup qui tue ne fait pas "oof" : la cartouche et les degats
            // sont deja tires a la declaration du tir, le moteur sait donc
            // des maintenant si la cible tombe -- seule sa REVELATION attend
            // l'ecran noir.
            var impact = courante.moteur().tirEnAttenteMortel() ? SON_MORT : SON_IMPACT;
            humain.playSound(impact);
            for (Player temoin : scene.spectateurs(rayonTemoins())) {
                if (!temoin.equals(humain)) temoin.playSound(impact);
            }
        }
        // Donut condamne : la cartouche est deja tiree, seul l'ecran noir
        // retarde sa revelation. Le tableau de mort (tete sur la table,
        // sang) s'installe SOUS le noir -- aucune animation a reussir, le
        // noir se leve sur le tableau fini (demande user 2026-08-27).
        // ROUND FINAL SEULEMENT : aux rounds 1 et 2, vider ses vies ne fait
        // que lui prendre la manche, il doit rester debout pour la suite.
        if (reelle && roundCourant >= 3 && !courante.moteur().tirEnAttenteViseJoueur()
                && courante.moteur().tirEnAttenteMortel()) {
            programmer(courante, chronologie.attenteAvantResolutionTicks() + 15L,
                    () -> scene.mortDealer());
        }
        programmer(courante, chronologie.attenteAvantResolutionTicks(),
                () -> resoudreTir(courante, chronologie));
    }

    private void resoudreTir(SessionPartie courante, ChronologieTir chronologie) {
        Player humain = joueur(courante);
        if (humain == null) return;
        if (chronologie.blackoutTicks() == 0) {
            traiter(courante, courante.moteur().reveler());
            return;
        }
        // Defibrillateur PAR-DESSUS le theme, sans coupure : la musique
        // joue en continu (decision finale user 2026-08-27), seuls les
        // CHANGEMENTS de piste la font repartir -- lastlife au dernier
        // coeur, via la majMusique du point de synchronisation.
        if (courante.moteur().tirEnAttenteViseJoueur()
                && !courante.moteur().tirEnAttenteMortel()) {
            humain.playSound(SON_DEFIB);
            // Les palettes attendent la levee du noir : dessous, elles ne se
            // verraient pas. Elles s'ecartent pendant les dernieres
            // impulsions du son.
            programmer(courante, chronologie.blackoutTicks(), () -> {
                Player vivant = joueur(courante);
                if (vivant != null) scene.defibrillation(vivant);
            });
        }
        ecranNoir.afficher(humain, chronologie.blackoutTicks(), () -> {
            if (active(courante)) traiter(courante, courante.moteur().reveler());
        });
        // Le flash noir touche aussi les spectateurs proches : eux n'ont
        // aucune suite de partie a derouler, juste le noir.
        for (Player temoin : scene.spectateurs(rayonTemoins())) {
            if (!temoin.equals(humain)) {
                ecranNoir.afficher(temoin, chronologie.blackoutTicks(), () -> { });
            }
        }
    }

    /** Rayon (blocs) dans lequel les spectateurs partagent oof et flash noir. */
    private double rayonTemoins() {
        return plugin.getConfig().getDouble("staging.witness-radius", 15.0);
    }

    /** Les spectateurs dans le rayon, joueur assis exclu. */
    private java.util.List<Player> temoins(Player humain) {
        java.util.List<Player> proches = scene.spectateurs(rayonTemoins());
        proches.remove(humain);
        return proches;
    }

    private void annoncerTemoins(Player humain, Component message) {
        for (Player temoin : temoins(humain)) temoin.sendMessage(message);
    }

    /**
     * Nom d'un acteur vu des spectateurs : les annonces du joueur assis sont
     * a la deuxieme personne ("You get 2 items"), illisibles pour un temoin.
     */
    private static String nomTemoin(Acteur acteur, Player humain) {
        return acteur == Acteur.JOUEUR ? humain.getName() : "DrDonutt";
    }

    /**
     * Gele la partie entre deux rounds : DrDonutt lance sa question de
     * relance, puis le chat affiche deux boutons cliquables. La suite des
     * evenements ne part qu'au clic sur CONTINUE (/rr continuer) ; GIVE UP
     * passe par /rr abandonner, qui nettoie aussi cette attente.
     */
    private void demanderContinuer(SessionPartie courante,
                                   java.util.List<EvenementPartie> suite) {
        Player joueur = joueur(courante);
        if (joueur == null) return;
        courante.verrouiller(true);
        annulerPriseFusil();
        // Filet de securite : si un bug a fait tomber le tableau de mort en
        // plein match (vu le 2026-08-27, tete sur la table des la fin du
        // round 1 et suite de la partie contre personne), le dealer est
        // remis debout avant de proposer la suite.
        if (scene.dealerMort()) scene.ranimerDealer();
        // Les affichages du point de synchronisation, que le return anticipe
        // du traitement vient de sauter : sans eux, les coeurs resteraient
        // sur l'etat d'avant le coup pendant toute l'attente.
        barreVie.afficher(joueur, courante.moteur().participant(Acteur.JOUEUR).vies(),
                courante.moteur().participant(Acteur.JOUEUR).viesClope());
        scene.montrerViesJoueur(joueur, courante.moteur().participant(Acteur.JOUEUR).vies(),
                courante.moteur().participant(Acteur.JOUEUR).viesClope(), regles.viesPlafond());
        scene.montrerViesDealer(courante.moteur().participant(Acteur.DEALER).vies(),
                courante.moteur().participant(Acteur.DEALER).viesClope(),
                regles.viesPlafond());
        scene.synchroniserMenottes(
                courante.moteur().participant(Acteur.DEALER).porteMenottes(),
                courante.moteur().participant(Acteur.JOUEUR).porteMenottes(),
                joueur);
        // Le sang ET la peau du round suivant apparaissent des la
        // proposition, pas a l'acceptation : il vient d'encaisser le coup
        // qui a fini le round, il doit deja etre amoche quand il pose la
        // question.
        scene.sangPourRound(roundCourant + 1);
        scene.peauDealerPourRound(roundCourant + 1);
        // Avant le round final, la salle retient son souffle : plus de
        // musique jusqu'au clic, le theme final repartira avec le round.
        if (roundCourant >= 2) scene.arreterMusique(joueur);
        jouerVoix(courante, "nextround", 15L, true);
        reprisePendante = () -> traiterEvenements(courante, suite);
        programmer(courante, 30L, () -> {
            if (reprisePendante == null) return;
            Player humain = joueur(courante);
            if (humain == null) return;
            // Le choix se prend en main, pas au chat : fusil en 1 pour
            // repartir, mallette en 2 pour encaisser.
            inventaire.preparerRelance(humain, courante.id(), gains());
            // Les deux objets sont AUSSI sur le feutre, fusil a gauche et
            // mallette a droite : la main part au milieu, donc les deux sont
            // visibles tant que rien n'est choisi.
            scene.montrerChoixRelance();
            scene.choixRelanceEnMain(null);
            scene.annoncer(humain, Component.text()
                    .append(Component.text("Wanna continue ? ", NamedTextColor.GOLD))
                    .append(Component.text("Your gain is $"
                            + net.thundranode.buckshot.Mises.formater(gains()), NamedTextColor.YELLOW))
                    .build());
        });
    }

    /**
     * Recalcule la piste voulue par l'etat courant. Ne fait rien pendant la
     * sequence defibrillateur : c'est sa fin qui rejoue, avec la bonne piste.
     * La bascule elle-meme est sans effet si la piste voulue joue deja.
     */
    private void majMusique(SessionPartie courante) {
        // A zero vie d'un cote ou de l'autre la partie est finie : la piste
        // en cours joue jusqu'au nettoyage d'annuler, sans rebasculer.
        if (courante.moteur().participant(Acteur.JOUEUR).vies() == 0
                || courante.moteur().participant(Acteur.DEALER).vies() == 0) {
            return;
        }
        scene.musiqueSituation(situationMusicale(courante));
    }

    private ScenePartie.Musique situationMusicale(SessionPartie courante) {
        boolean joueurAUnCoeur = courante.moteur().participant(Acteur.JOUEUR).vies() == 1;
        boolean dealerAUnCoeur = courante.moteur().participant(Acteur.DEALER).vies() == 1;
        // Au round final, le dernier coeur a SON OST, et celui de DrDonutt
        // compte autant que celui du joueur (demande user 2026-08-27) : la
        // mort est a un coup, peu importe de quel cote de la table.
        if (roundCourant >= 3 && (joueurAUnCoeur || dealerAUnCoeur)) {
            return ScenePartie.Musique.DERNIER_COEUR_FINAL;
        }
        if (joueurAUnCoeur) {
            return ScenePartie.Musique.DERNIER_COEUR;
        }
        if (roundCourant >= 3) {
            return joueurToucheAuRound3
                    ? ScenePartie.Musique.FINALE_TOUCHEE : ScenePartie.Musique.FINALE;
        }
        return ScenePartie.Musique.CALME;
    }

    /**
     * Fin de parcours victorieuse : retour au spawn du monde par teleport.
     * Apres annuler(), qui a deja rendu le mode de jeu et l'inventaire.
     */
    private void renvoyerAuSpawn(UUID joueurId) {
        Player joueur = Bukkit.getPlayer(joueurId);
        if (joueur != null && joueur.isOnline()) {
            joueur.teleport(joueur.getWorld().getSpawnLocation());
        }
    }

    /**
     * Defaite : une VRAIE mort (demande user 2026-08-27), ecran de respawn
     * compris. Apres annuler() -- il faut etre sorti du mode spectateur, et
     * la partie deja annulee fait de l'evenement de mort un no-op. Le
     * respawn est dirige vers le spawn du monde de la table par l'ecouteur
     * (le respawn vanilla renverrait au spawn de l'overworld principal).
     * Prerequis pose le 2026-08-27 : keep_inventory=true dans le monde
     * buckshot, sinon l'inventaire rendu par annuler se viderait au sol.
     */
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
     * Vrai pendant la mort infligee par {@link #tuerPourDeVrai} (l'evenement
     * de mort part de facon synchrone dans setHealth(0), avant le respawn) :
     * l'ecouteur garde l'inventaire deja restaure et vide les drops.
     */
    public boolean mortProgrammee(UUID joueurId) {
        return respawnsDiriges.containsKey(joueurId);
    }

    /**
     * Gain courant : mise x multiplicateur du dernier round GAGNE (quotas de
     * cashout par round, workflow Outmind user 2026-08-27). Le multiplicateur
     * rend le TOUT, mise comprise : encaisser apres le round 1 a x1.5 rend
     * 1,5 fois la mise, pas la mise plus 1,5.
     */
    private long gains() {
        if (roundsGagnes <= 0 || miseCourante <= 0) return 0;
        java.util.List<Double> multiplicateurs =
                plugin.getConfig().getDoubleList("gains.multiplicateurs");
        if (multiplicateurs.isEmpty()) return 0;
        double taux = multiplicateurs.get(
                Math.min(roundsGagnes, multiplicateurs.size()) - 1);
        return (long) Math.floor(miseCourante * taux);
    }

    /** Paye en dollars Vault : le pont Outmind mirror le delta tout seul. */
    private void payer(Player joueur, long montant) {
        if (montant <= 0 || !banque.disponible()) return;
        if (!banque.crediter(joueur, montant)) {
            plugin.getLogger().severe("[Buckshot] paiement de " + montant
                    + "$ a " + joueur.getName() + " refuse par Vault");
        }
    }

    /**
     * Abandon volontaire. A la question de relance, c'est un depart avec la
     * caisse : le gain accumule est paye. En plein round, on perd tout.
     */
    public void abandonner(Player joueur) {
        if (!estEnPartie(joueur.getUniqueId())) return;
        long gain = reprisePendante != null ? gains() : 0;
        if (gain > 0) {
            session.regler();
            payer(joueur, gain);
            annuler(joueur.getUniqueId(), "You walk away with $"
                    + net.thundranode.buckshot.Mises.formater(gain) + ".");
        } else {
            annuler(joueur.getUniqueId(), "You cashed out.");
        }
    }

    /** Clic sur CONTINUE : relance la suite gelee, s'il y a une attente. */
    public void reprendre(Player joueur) {
        if (!estEnPartie(joueur.getUniqueId())) return;
        Runnable suite = reprisePendante;
        if (suite == null) return;
        reprisePendante = null;
        scene.masquerChoixRelance();
        suite.run();
    }

    /**
     * Molette pendant la question de relance : l'objet passe en main quitte
     * le feutre, l'autre y reste. Sans attente en cours, sans effet.
     */
    public void choixRelanceEnMain(Player joueur, int slot) {
        if (reprisePendante == null || !estEnPartie(joueur.getUniqueId())) return;
        scene.choixRelanceEnMain(
                inventaire.type(joueur.getInventory().getItem(slot)));
    }

    /**
     * Replique vocale du dealer, jouee a sa position pour rester spatialisee.
     *
     * <p>Le choix de la variante est laisse au client : chaque evenement
     * {@code rr:voix.*} du pack liste toutes ses prises et le jeu en tire une
     * au hasard. Le serveur n'a donc ni tableau de durees ni tirage a faire.
     *
     * <p>Les repliques non prioritaires respectent une garde de 3,5 s apres
     * le depart de la precedente : le serveur ignore les durees reelles,
     * cette fenetre couvre la plupart des prises et evite que Donut se
     * parle par-dessus quand les declencheurs s'enchainent.
     */
    private void jouerVoix(SessionPartie courante, String nom, long delaiTicks,
                           boolean prioritaire) {
        programmer(courante, delaiTicks, () -> {
            Player humain = joueur(courante);
            if (humain == null) return;
            long maintenant = System.currentTimeMillis();
            if (!prioritaire && maintenant - derniereVoixMs < 3500) return;
            derniereVoixMs = maintenant;
            org.bukkit.Location bouche = scene.dealerEntite()
                    .map(Player::getEyeLocation)
                    .orElseGet(humain::getLocation);
            humain.getWorld().playSound(bouche, "rr:voix." + nom,
                    org.bukkit.SoundCategory.VOICE,
                    (float) plugin.getConfig().getDouble("sons.volume-voix", 1.6), 1.0f);
        });
    }

    /**
     * Seule la victoire porte encore un titre, sans sous-titre. La defaite se
     * raconte par la chute et la replique de DrDonutt : y coller un DEFAITE
     * cassait le plan (demande user 2026-08-24).
     */
    private net.kyori.adventure.title.Title titreVictoire() {
        return net.kyori.adventure.title.Title.title(
                Component.text("VICTORY", NamedTextColor.GREEN), Component.empty());
    }

    /**
     * Chute au sol en camera guidee : le joueur passe spectateur d'un display
     * invisible qui s'affaisse de hauteur d'yeux jusqu'au sol pendant que le
     * regard bascule vers le plafond, avec une derive de lacet pour le cote
     * sonne. Aucun roulis n'existe cote client : la chute se raconte par la
     * descente et le tangage.
     *
     * <p>La tache vit HORS de la session : quoi qu'il arrive (annulation,
     * erreur), elle rend la camera et le mode de jeu au joueur en moins de
     * trois secondes. Le cas du joueur qui se deconnecte en pleine chute est
     * couvert par {@link #annuler}, qui restaure le mode avant son depart.
     */
    private void cinematiqueChute(SessionPartie courante, Runnable apres) {
        Player joueur = joueur(courante);
        if (joueur == null) return;
        // Hauteur d'yeux DEBOUT, pas getEyeLocation : tue en sneakant, la
        // camera partait 35 cm trop bas et toute la chute frolait le sol.
        org.bukkit.Location depart = joueur.getLocation().add(0, 1.62, 0);
        depart.setYaw(joueur.getLocation().getYaw());
        depart.setPitch(joueur.getLocation().getPitch());
        org.bukkit.GameMode modeAvant = joueur.getGameMode();
        joueur.setSneaking(false);
        org.bukkit.entity.ItemDisplay camera = joueur.getWorld().spawn(depart,
                org.bukkit.entity.ItemDisplay.class, display -> {
                    // Interpolation de teleport : c'est elle qui lisse la
                    // trajectoire cote client, tick par tick.
                    display.setTeleportDuration(2);
                    display.setPersistent(false);
                });
        // Pour les spectateurs, qui perdent le joueur des le passage en mode
        // spectateur : un corps a son skin le remplace, debout pendant la
        // sentence, effondre au moment de la chute.
        org.bukkit.Location sol = joueur.getLocation().clone();
        scene.poserCorpsChute(joueur);
        joueur.setGameMode(org.bukkit.GameMode.SPECTATOR);
        // Bedrock : Geyser ne sait pas verrouiller la camera sur une entite
        // (et ne spawne meme pas l'ItemDisplay camera), le mourant restait en
        // spectateur libre (constat user 2026-08-29). A defaut, le joueur
        // lui-meme est teleporte le long de la trajectoire a chaque tick.
        boolean bedrock = EcouteurPartie.estBedrock(joueur);
        if (!bedrock) joueur.setSpectatorTarget(camera);
        cinematiqueSpectateur = joueur.getUniqueId();
        // Le dealer savoure sa victoire pendant que tu t'effondres. Coupe
        // toute replique en cours : ce moment-la lui appartient. La musique,
        // elle, continue (decision finale user 2026-08-27).
        joueur.stopSound(org.bukkit.SoundCategory.VOICE);
        jouerVoix(courante, "donutwins", 12L, true);
        // L'animation s'arrete a finAnim, la suite (title DEFAITE) part a
        // momentApres, mais la camera RESTE au sol jusqu'a finCinematique :
        // le retour en premiere personne pendant le title cassait toute
        // l'immobilite de la fin. L'annulation de la partie (programmee par
        // `apres`) restaure le mode de jeu avant ce terme ; le garde-fou ici
        // n'agit que si elle n'a pas eu lieu.
        final int finAnim = 36;
        final int momentApres = 50;
        // 155 et non 115 : deux secondes de plus au sol avant le respawn
        // (demande user 2026-08-27), l'annulation programmee tombant a 150.
        final int finCinematique = 155;
        final BukkitTask[] tache = new BukkitTask[1];
        tache[0] = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            int tick;
            /** Derniere position calculee, pour re-clouer un client Bedrock. */
            org.bukkit.Location derniere;

            @Override
            public void run() {
                if (!joueur.isOnline() || tick > finCinematique) {
                    // Deverrouiller AVANT de rendre la camera : le detachement
                    // ci-dessous emet lui aussi l'evenement que l'ecouteur
                    // annule pendant la cinematique.
                    cinematiqueSpectateur = null;
                    if (joueur.isOnline()
                            && joueur.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
                        joueur.setSpectatorTarget(null);
                        joueur.setGameMode(modeAvant);
                    }
                    camera.remove();
                    tache[0].cancel();
                    return;
                }
                // Le sneak du client demonte la camera malgre l'ecouteur si
                // un paquet passe entre deux ticks : re-arrimer au besoin.
                if (!bedrock && joueur.getSpectatorTarget() == null
                        && joueur.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
                    joueur.setSpectatorTarget(camera);
                }
                if (tick == momentApres) {
                    // Le verdict tombe pendant que la camera est au sol :
                    // YOU LOST, et la mise envolee en sous-titre (demande
                    // user 2026-08-27). Lu avant l'annulation, qui remet la
                    // mise a zero.
                    joueur.showTitle(net.kyori.adventure.title.Title.title(
                            Component.text("YOU LOST", NamedTextColor.RED),
                            miseCourante > 0
                                    ? Component.text("-$" + net.thundranode.buckshot.Mises
                                            .formater(miseCourante), NamedTextColor.DARK_RED)
                                    : Component.empty()));
                    annoncerTemoins(joueur, Component.text(joueur.getName() + " is dead."
                            + (miseCourante > 0 ? " -$" + net.thundranode.buckshot.Mises
                                    .formater(miseCourante) + "." : ""),
                            NamedTextColor.DARK_RED));
                    apres.run();
                }
                if (tick == finAnim - 8) {
                    // Le debut de la chute brutale : le corps s'effondre a
                    // l'instant ou la camera du mourant part au sol.
                    scene.coucherCorpsChute();
                }
                if (tick == finAnim) {
                    // L'impact : bruit de chute et sang au sol, pour la
                    // salle. Le mourant, lui, est deja par terre.
                    scene.sangChuteJoueur(sol);
                }
                if (tick > finAnim + 2) {
                    // Immobile au sol : plus aucun teleport, plus aucun
                    // micro-mouvement d'interpolation. Sauf Bedrock : sans
                    // attache, le client peut deriver -- re-cloue au sol.
                    if (bedrock && derniere != null && tick % 10 == 0) {
                        TeleportAutorise.pendant(joueur, () -> joueur.teleport(derniere));
                    }
                    tick++;
                    return;
                }
                // Trois temps : la tete part a gauche (0,7 s), revient et
                // depasse a droite (0,7 s), puis la chute BRUTALE (0,4 s) et
                // l'immobilite au sol, le dealer en contre-plongee.
                double radians = Math.toRadians(depart.getYaw());
                double droiteX = -Math.cos(radians), droiteZ = -Math.sin(radians);
                double lateral, baisse, deriveLacet, tangage;
                if (tick <= 14) {
                    double p = 0.5 - 0.5 * Math.cos(Math.PI * tick / 14.0);
                    lateral = -0.35 * p;
                    deriveLacet = -12 * p;
                    baisse = 0.08 * p;
                    tangage = depart.getPitch();
                } else if (tick <= 28) {
                    double p = 0.5 - 0.5 * Math.cos(Math.PI * (tick - 14) / 14.0);
                    lateral = -0.35 + 0.75 * p;
                    deriveLacet = -12 + 26 * p;
                    baisse = 0.08 + 0.12 * p;
                    tangage = depart.getPitch();
                } else {
                    double p = Math.min(1.0, (tick - 28) / 8.0);
                    double chute = p * p;
                    lateral = 0.40;
                    deriveLacet = 14 + 8 * chute;
                    baisse = 0.20 + 1.15 * chute;
                    tangage = depart.getPitch() + (-75 - depart.getPitch()) * chute;
                }
                org.bukkit.Location pos = depart.clone().add(
                        droiteX * lateral, -baisse, droiteZ * lateral);
                pos.setYaw((float) (depart.getYaw() + deriveLacet));
                pos.setPitch((float) tangage);
                camera.teleport(pos);
                if (bedrock) {
                    derniere = pos;
                    TeleportAutorise.pendant(joueur, () -> joueur.teleport(pos));
                }
                tick++;
            }
        }, 1L, 1L);
    }

    /** Charge que `acteur` vient de decouvrir, ou null si l'action ne l'a pas revelee. */
    private static TypeCartouche chambreVue(ResultatAction action, Acteur acteur) {
        for (EvenementPartie evenement : action.evenements()) {
            if (evenement instanceof EvenementPartie.ChambrePrivee e && e.acteur() == acteur) {
                return e.type();
            }
        }
        return null;
    }

    /**
     * Porte la pose reglee sur le joueur tant qu'il tient l'arme en joue.
     *
     * <p>La mise en joue est entierement pilotee par le client (use_duration) :
     * le serveur ne recoit aucun evenement en cours de visee, il ne peut que
     * lire l'etat d'usage. On attend la fin de la montee en joue, sans quoi la
     * pose finale apparaitrait pendant que l'arme monte encore.
     */
    /**
     * Cartouches encore dans l'arme : un carre par logement du chargeur,
     * blanc tant qu'il est charge, gris une fois brule.
     *
     * <p>Ne dit pas QUELLES cartouches restent -- le joueur ne le sait pas --
     * seulement combien. C'est un decompte, pas une revelation.
     */
    private Component jaugeChargeur() {
        int restantes = cartouchesVisibles;
        var ligne = Component.text().append(Component.text("[ ", NamedTextColor.DARK_GRAY));
        for (int i = 0; i < capaciteVisible; i++) {
            ligne.append(Component.text("\u25A0 ",
                    i < restantes ? NamedTextColor.WHITE : NamedTextColor.DARK_GRAY));
        }
        return ligne.append(Component.text("]", NamedTextColor.DARK_GRAY)).build();
    }

    private void rafraichirViseeJoueur(SessionPartie courante) {
        Player joueur = joueur(courante);
        if (!active(courante) || joueur == null) return;
        // Pousse a chaque tick, et pas seulement au changement : la barre
        // d'action est partagee avec les autres plugins du serveur, et le
        // dernier a ecrire gagne. A cadence plus basse, l'argent de Vegas
        // reprendrait la main entre deux envois et ca clignoterait.
        // Les spectateurs proches ont la meme jauge : etre a la table du
        // buckshot vaut adhesion, il n'y a rien d'autre a y faire.
        Component jauge = jaugeChargeur();
        joueur.sendActionBar(jauge);
        for (Player temoin : temoins(joueur)) temoin.sendActionBar(jauge);
        if (poseForcee) return;
        String type = inventaire.type(joueur.getInventory().getItemInMainHand());
        boolean enJoue = type != null && type.startsWith("shot:")
                && joueur.isHandRaised()
                && joueur.getItemInUseTicks() >= animateur.dureeTicks("aim_front");
        if (enJoue) scene.montrerPose(joueur, "shot:self".equals(type) ? "self" : "front");
        else scene.cacherPose(joueur);
    }

    private void jouerDealer(SessionPartie courante) {
        if (!active(courante) || courante.moteur().phase() != PhasePartie.TOUR_DEALER) return;
        ActionIA choix = strategie.choisir(courante.moteur().vueIA(), aleatoire);
        if (choix instanceof ActionIA.UtiliserObjet objet) {
            ResultatAction resultat = courante.moteur().utiliser(Acteur.DEALER, objet.objet());
            if (!resultat.acceptee()) {
                traiter(courante, resultat);
                return;
            }
            // L'objet passe dans sa main avant que son effet tombe : sans ce
            // temps, le message d'utilisation arrivait sans que rien ne se voie
            // a la table. Le fusil revient tout seul, le tour suivant repasse
            // par montrerFusil.
            scene.montrerObjetDealer(InventairePartie.vitrine(objet.objet()));
            programmer(courante, objetDealerTicks, () -> {
                if (!active(courante)) return;
                if (objet.objet() == Objet.CIGARETTES) {
                    // La clope quitte sa main pour sa bouche, puis le megot
                    // tombe sur le feutre avant que sa vie remonte.
                    scene.montrerObjetDealer(ItemStack.empty());
                    Player dealer = scene.dealerEntite().orElse(null);
                    if (dealer == null) {
                        traiter(courante, resultat);
                        return;
                    }
                    scene.fumerCigarette(dealer, false, () -> traiter(courante, resultat));
                    return;
                }
                if (objet.objet() != Objet.LOUPE) {
                    traiter(courante, resultat);
                    return;
                }
                // La loupe se regarde d'abord, puis il verifie la chambre.
                // Comme les deux visees, la pose passe par l'ItemDisplay
                // reglable a chaud (/rr pose inspect) et non par la troisieme
                // personne du pack, que personne n'a jamais reussi a caler
                // sans y passer la nuit. La cartouche affichee est la neutre :
                // ce que le dealer apprend ne se lit pas depuis l'autre bout
                // de la table.
                // Il ramasse d'abord le fusil pose sur la table (demande
                // user 2026-08-27), l'inspecte, puis le REPOSE le temps de
                // decider -- sa main reste vide jusqu'a la visee.
                scene.retirerFusilAPrendre();
                scene.viserDealer("inspect");
                programmer(courante, animateur.dureeInspection(loupeTenueTicks), () -> {
                    scene.finViseeDealer();
                    // Repose apres lecture : la main redevient vide, le
                    // ramassage pour de bon se fait a la visee.
                    fusilEnMainDealer = false;
                    scene.poserFusilDealer();
                    traiter(courante, resultat);
                });
            });
        } else if (choix instanceof ActionIA.Tirer tir) {
            Player dealer = scene.dealerEntite().orElse(null);
            ResultatAction resultat = courante.moteur().tirer(Acteur.DEALER, tir.cible());
            if (!resultat.acceptee()) {
                annuler(courante.joueurId(), "Dealer error: " + resultat.erreur());
                return;
            }
            courante.demanderPompe(Acteur.DEALER);
            // C'est ici que DrDonutt ramasse le fusil pose sur son cote de
            // la table : la visee prend le relais visuel, et une blanche sur
            // lui le laissera en main au tour garde.
            scene.retirerFusilAPrendre();
            fusilEnMainDealer = true;
            String visee = tir.cible() == Cible.SOI ? "aim_self" : "aim_front";
            ChronologieTir chronologie = chronologie(resultat, visee);
            // Le fusil vise est un ItemDisplay pilote serveur, regle en
            // direct par /rr pose : la pose en main de PNJ via le pack a
            // coute une nuit de calibrage sans converger.
            scene.viserDealer(tir.cible() == Cible.SOI ? "self" : "front");
            // Viser l'adversaire, c'est le regarder : la main ne s'incline pas
            // toute seule, c'est l'orientation du corps qui decide ou pointe le
            // canon.
            if (tir.cible() == Cible.ADVERSAIRE) {
                Player humain = joueur(courante);
                if (humain != null) scene.dealerRegarde(humain.getEyeLocation());
            }
            // Les deux poses de visee sont mesurees bras pendant : lever le
            // bras changerait le repere de la main et deferait la pose. Le
            // bras ne se leve donc plus jamais pendant une visee.
            programmer(courante, chronologie.viseeTicks() + viseeDealerTicks,
                    () -> lancerTir(courante, Acteur.DEALER, chronologie));
        }
    }

    private void traiter(SessionPartie courante, ResultatAction resultat) {
        if (!active(courante)) return;
        Player joueur = joueur(courante);
        if (joueur == null) return;
        if (!resultat.acceptee()) {
            erreur(joueur, resultat.erreur());
            return;
        }
        traiterEvenements(courante, resultat.evenements());
    }

    private void traiterEvenements(SessionPartie courante,
                                   java.util.List<EvenementPartie> evenements) {
        if (!active(courante)) return;
        Player joueur = joueur(courante);
        if (joueur == null) return;
        boolean partieSeTermine = evenements.stream()
                .anyMatch(EvenementPartie.PartieTerminee.class::isInstance);
        boolean victoireJoueur = evenements.stream().anyMatch(
                ev -> ev instanceof EvenementPartie.PartieTerminee p
                        && p.vainqueur() == Acteur.JOUEUR);
        boolean roundSeTermine = evenements.stream()
                .anyMatch(EvenementPartie.RoundTermine.class::isInstance);
        boolean fin = false;
        // Auteur d'une biere dans ce lot : c'est de sa main que la cartouche
        // ejectee doit voler.
        Acteur dernierBuveur = null;
        for (int rang = 0; rang < evenements.size(); rang++) {
            EvenementPartie evenement = evenements.get(rang);
            if (evenement instanceof EvenementPartie.RoundCommence e) {
                joueurToucheAuRound3 = false;
                scene.peauDealerPourRound(e.round());
            scene.musiquePourRound(e.round());
            scene.sangPourRound(e.round());
                joueur.showTitle(net.kyori.adventure.title.Title.title(
                        Component.text("ROUND " + e.round(), NamedTextColor.GOLD), Component.empty()));
                roundCourant = e.round();
                // Round 1 : accueil. Round final : la musique coupee a la
                // question de relance repart sur le theme final, Donut coupe
                // sa propre question et ouvre le round sur une replique du
                // pool "final". Le round 2 a deja parle a la relance.
                if (e.round() == 1) {
                    jouerVoix(courante, "welcome", 20L, true);
                } else if (e.round() >= 3) {
                    scene.demarrerMusique(joueur);
                    joueur.stopSound("rr:voix.nextround", org.bukkit.SoundCategory.VOICE);
                    jouerVoix(courante, "final", 20L, true);
                }
            } else if (evenement instanceof EvenementPartie.ViesChangees e && e.degats() > 0) {
                // Premier coup encaisse au round final : l'OST bascule (la
                // majMusique du point de synchronisation s'en charge).
                if (e.acteur() == Acteur.JOUEUR && roundCourant >= 3) {
                    joueurToucheAuRound3 = true;
                }
                // Reaction aux balles encaissees : silence si le joueur gagne
                // la partie, silence si le round se termine (la question de
                // relance parle deja), et deux fois sur trois -- le tirage a
                // un tiers rendait le dealer quasi muet, les coups qui
                // terminent un round etant deja silencieux. Au round final,
                // le pool "final" remplace shootsyou et gotshot.
                if (!victoireJoueur && !roundSeTermine) {
                    // L'auto-tir rate du dealer a son propre pool, garde meme
                    // au round final (le specifique bat le generique) et joue
                    // a coup sur : le moment est trop bon pour etre tire au
                    // sort. Les autres reactions gardent le tirage.
                    boolean autoTirDealer = e.acteur() == Acteur.DEALER
                            && dernierTireur == Acteur.DEALER;
                    if (autoTirDealer) {
                        jouerVoix(courante, "shoothimself", 10L, true);
                    } else if (aleatoire.nextInt(3) != 0) {
                        jouerVoix(courante, roundCourant >= 3 ? "final"
                                : e.acteur() == Acteur.JOUEUR ? "shootsyou" : "gotshot", 10L, false);
                    }
                }
            } else if (evenement instanceof EvenementPartie.ChargeurAnnonce e) {
            cartouchesVisibles = e.reelles() + e.blanches();
            capaciteVisible = cartouchesVisibles;
            var annonceReelles = Component.text(
                    e.reelles() + " live round" + (e.reelles() > 1 ? "s" : "") + ".",
                    NamedTextColor.RED);
            var annonceBlanches = Component.text(
                    e.blanches() + " blank" + (e.blanches() > 1 ? "s" : "") + ".",
                    NamedTextColor.WHITE);
            scene.annoncer(joueur, annonceReelles);
            scene.annoncer(joueur, annonceBlanches);
                // Seule annonce du chargeur : un title au chargement, puis
                // silence. L'UX d'origine repetait l'etat dans le chat a
                // chaque coup et le minijeu spammait.
                var titreChargeur = net.kyori.adventure.title.Title.title(
                        Component.text()
                                .append(Component.text(e.reelles() + " RED", NamedTextColor.RED))
                                .append(Component.text("   ", NamedTextColor.WHITE))
                                .append(Component.text(e.blanches() + " BLANK", NamedTextColor.GRAY))
                                .build(),
                        Component.empty());
                joueur.showTitle(titreChargeur);
                // Le chargeur est une info publique : les spectateurs ont le
                // meme title et les memes lignes que le joueur assis.
                for (Player temoin : temoins(joueur)) {
                    temoin.sendMessage(annonceReelles);
                    temoin.sendMessage(annonceBlanches);
                    temoin.showTitle(titreChargeur);
                }
            } else if (evenement instanceof EvenementPartie.ObjetsDistribues e) {
                scene.annoncer(joueur, Component.text(
                        (e.acteur() == Acteur.JOUEUR ? "You get " : "DrDonutt gets ")
                                + e.objets().size() + " item(s).", NamedTextColor.AQUA));
                annoncerTemoins(joueur, Component.text(
                        nomTemoin(e.acteur(), joueur) + " gets "
                                + e.objets().size() + " item(s).", NamedTextColor.AQUA));
            } else if (evenement instanceof EvenementPartie.ObjetUtilise e) {
                scene.annoncer(joueur, Component.text(
                        nom(e.acteur()) + " uses " + nomObjet(e.objet()) + ".",
                        NamedTextColor.GOLD));
                annoncerTemoins(joueur, Component.text(
                        nomTemoin(e.acteur(), joueur) + " uses " + nomObjet(e.objet()) + ".",
                        NamedTextColor.GOLD));
                if (e.objet() == Objet.BIERE) dernierBuveur = e.acteur();
                // Commentaires d'objets, deux fois sur trois comme les tirs.
                // Non prioritaires : quand le dealer enchaine loupe-biere-
                // clope, la garde anti-chevauchement n'en laisse passer qu'un.
                if (aleatoire.nextInt(3) != 0) {
                    if (e.objet() == Objet.LOUPE && e.acteur() == Acteur.DEALER) {
                        jouerVoix(courante, "inspect", 10L, false);
                    } else if (e.objet() == Objet.CIGARETTES) {
                        jouerVoix(courante, e.acteur() == Acteur.DEALER
                                ? "donutsmoke" : "playersmokes", 10L, false);
                    }
                }
            } else if (evenement instanceof EvenementPartie.ChambrePrivee e
                    && e.acteur() == Acteur.JOUEUR) {
                joueur.sendMessage(Component.text("Magnifier: " + nomCartouche(e.type())
                        + " round.", NamedTextColor.LIGHT_PURPLE));
                // Les spectateurs voient ce que voit la loupe, comme le
                // public d'un stream : l'info reste celle du joueur, elle ne
                // renseigne personne d'autre que la salle.
                annoncerTemoins(joueur, Component.text(joueur.getName() + "'s magnifier: "
                        + nomCartouche(e.type()) + " round.", NamedTextColor.LIGHT_PURPLE));
            } else if (evenement instanceof EvenementPartie.CartoucheRevelee e) {
                // La cartouche ne quitte la jauge qu'ici, quand le joueur
                // apprend son sort -- apres l'ecran noir pour un tir, a
                // l'instant meme pour une ejection a la biere.
                cartouchesVisibles = Math.max(0, cartouchesVisibles - 1);
                // Le PAN d'un tir ne disait rien que le coup lui-meme n'ait
                // deja dit : degats, ecran noir, son. L'ejection a la biere,
                // elle, est la seule facon d'apprendre cette cartouche-la.
                boolean cartoucheReelle = e.type() == TypeCartouche.REELLE;
                if (e.ejectee()) {
                    var annonceEjection = Component.text(
                            "Ejected: " + nomCartouche(e.type()),
                            cartoucheReelle ? NamedTextColor.RED : NamedTextColor.WHITE);
                    scene.annoncer(joueur, annonceEjection);
                    annoncerTemoins(joueur, annonceEjection);
                    scene.ejecterCartouche(dernierBuveur != null ? dernierBuveur
                            : Acteur.JOUEUR, cartoucheReelle, joueur);
                }
                // La douille d'un TIR est ejectee par lancerTir, au moment du
                // coup : rien a faire ici.
            } else if (evenement instanceof EvenementPartie.TourSaute e) {
                // e.restants() compte le tour saute a l'instant meme. Un
                // nombre nu se lisait de travers -- "1 restant" pendant que la
                // victime en perd encore deux -- donc on dit ce qui se passe.
                String suiteSaut = e.restants() > 1
                        ? " skips this turn and the next one."
                        : " skips this turn, then plays again.";
                scene.annoncer(joueur, Component.text(nom(e.acteur()) + suiteSaut,
                        NamedTextColor.GRAY));
                annoncerTemoins(joueur, Component.text(
                        nomTemoin(e.acteur(), joueur) + suiteSaut, NamedTextColor.GRAY));
            } else if (evenement instanceof EvenementPartie.RoundTermine e) {
                if (e.vainqueur() == Acteur.JOUEUR) roundsGagnes++;
                // Perdre un round, c'est perdre tout court : pas de question
                // de relance, mort immediate. Le moteur a deja prepare le
                // round suivant, on ne le deroule simplement jamais.
                if (!partieSeTermine && e.vainqueur() == Acteur.DEALER) {
                    // La mise est perdue des cet instant : une mort pendant
                    // la cinematique ne doit plus rien rembourser.
                    courante.regler();
                    scene.synchroniserMenottes(false, false, joueur);
                    scene.reposerFusil(joueur);
                    cinematiqueChute(courante,
                            () -> programmer(courante, 100L, () -> {
                                UUID id = courante.joueurId();
                                annuler(id, null);
                                tuerPourDeVrai(id);
                            }));
                    return;
                }
                // Plus de title de fin de round (demande user 2026-08-24) :
                // la question de relance de DrDonutt annonce deja la suite.
                // Round gagne : la partie se fige, DrDonutt demande si on
                // continue, et le reste des evenements (round suivant compris)
                // ne part qu'au clic sur CONTINUE.
                if (!partieSeTermine && rang + 1 < evenements.size()) {
                    demanderContinuer(courante, java.util.List.copyOf(
                            evenements.subList(rang + 1, evenements.size())));
                    return;
                }
            } else if (evenement instanceof EvenementPartie.PartieTerminee e) {
                fin = true;
                // Verdict tombe : gain paye ci-dessous ou mise perdue, plus
                // aucun remboursement possible pendant la fenetre de fin.
                courante.regler();
                if (e.vainqueur() == Acteur.JOUEUR) {
                    // Le tableau de mort est deja en place (installe sous le
                    // noir par lancerTir) ; la mallette des gains apparait
                    // quand le noir se leve.
                    scene.poserMalletteVictoire();
                    long gain = gains();
                    payer(joueur, gain);
                    if (gain > 0) {
                        scene.annoncer(joueur, Component.text("You walk away with $"
                                + net.thundranode.buckshot.Mises.formater(gain) + ".",
                                NamedTextColor.GOLD));
                        annoncerTemoins(joueur, Component.text(joueur.getName()
                                + " walks away with $"
                                + net.thundranode.buckshot.Mises.formater(gain) + ".",
                                NamedTextColor.GOLD));
                    }
                    programmer(courante, 22L, () -> {
                        Player vivant = joueur(courante);
                        if (vivant != null) vivant.showTitle(titreVictoire());
                    });
                }
                // La defaite n'affiche rien ici : le title tombe a la fin de
                // la cinematique de chute, lancee au point de fin.
            }
        }
        // Un seul point de synchronisation : degats, donut et remise a zero de
        // round passent tous par ici, la barre ne peut pas deriver de l'etat.
        barreVie.afficher(joueur, courante.moteur().participant(Acteur.JOUEUR).vies(),
                courante.moteur().participant(Acteur.JOUEUR).viesClope());
        scene.montrerViesJoueur(joueur, courante.moteur().participant(Acteur.JOUEUR).vies(),
                courante.moteur().participant(Acteur.JOUEUR).viesClope(), regles.viesPlafond());
        scene.montrerViesDealer(courante.moteur().participant(Acteur.DEALER).vies(),
                courante.moteur().participant(Acteur.DEALER).viesClope(),
                regles.viesPlafond());
        majMusique(courante);
        scene.synchroniserMenottes(
                courante.moteur().participant(Acteur.DEALER).porteMenottes(),
                courante.moteur().participant(Acteur.JOUEUR).porteMenottes(),
                joueur);
        if (fin) {
            scene.synchroniserMenottes(false, false, joueur);
            scene.reposerFusil(joueur);
            if (victoireJoueur) {
                // 120 et non 60 : le tableau (corps sur la table, mallette)
                // reste en place le temps que le title s'efface, puis le
                // joueur repart au spawn (demande user 2026-08-27).
                programmer(courante, 120L, () -> {
                    UUID id = courante.joueurId();
                    annuler(id, null);
                    renvoyerAuSpawn(id);
                });
            } else {
                cinematiqueChute(courante,
                        () -> programmer(courante, 100L, () -> {
                            UUID id = courante.joueurId();
                            annuler(id, null);
                            tuerPourDeVrai(id);
                        }));
            }
            return;
        }
        // La pompe se glisse entre la revelation et la suite du tour : le
        // message du resultat reste a l'heure, mais plus personne n'agit tant
        // que l'arme n'est pas rearmee. Sa duree est celle de l'animation, et
        // non un nombre a part, sinon reprendre la main pendant que l'animateur
        // pousse encore ses images ecraserait l'etat de visee de l'item.
        Acteur pompe = courante.consommerPompe();
        if (pompe == null) {
            synchroniserPhase(courante);
            return;
        }
        courante.verrouiller(true);
        Player porteur = pompe == Acteur.JOUEUR ? joueur : scene.dealerEntite().orElse(null);
        if (porteur != null) animateur.jouerForce(porteur, "reload");
        programmer(courante,
                ChronologieTir.animationApresClicAnnule(animateur.dureeTicks("reload")),
                () -> synchroniserPhase(courante));
    }

    private void synchroniserPhase(SessionPartie courante) {
        if (!active(courante)) return;
        Player joueur = joueur(courante);
        if (joueur == null) return;
        switch (courante.moteur().phase()) {
            case RECHARGEMENT -> lancerRechargement(courante);
            case TOUR_JOUEUR -> {
                courante.verrouiller(false);
                fusilEnMainDealer = false;
                scene.montrerFusil(Acteur.JOUEUR, joueur);
                if (fusilEnMain) {
                    // Fusil deja ramasse, la resynchro vient d'un objet :
                    // rafraichir les quantites sans reposer l'arme ni
                    // deplacer la main.
                    int slotTenu = joueur.getInventory().getHeldItemSlot();
                    inventaire.preparerHotbar(joueur, courante.id(),
                            courante.moteur().participant(Acteur.JOUEUR), true);
                    joueur.getInventory().setHeldItemSlot(slotTenu);
                    return;
                }
                // Le fusil ne saute plus dans la hotbar : il se pose sur la
                // table devant le joueur, qui doit le RAMASSER pour tirer
                // (demande user 2026-08-27). Les objets restent jouables des
                // maintenant, aux memes slots qu'une fois le fusil en main,
                // et la main n'est plus forcee vers un slot.
                attentePriseFusil = true;
                inventaire.preparerHotbarSansFusil(joueur, courante.id(),
                        courante.moteur().participant(Acteur.JOUEUR));
                scene.poserFusilAPrendre();
            }
            case TOUR_DEALER -> {
                courante.verrouiller(true);
                annulerPriseFusil();
                inventaire.preparerHotbar(joueur, courante.id(),
                        courante.moteur().participant(Acteur.JOUEUR), false);
                if (fusilEnMainDealer) {
                    // Blanche sur lui : il garde l'arme en main pour le tour
                    // qu'il vient de gagner (demande user 2026-08-27).
                    scene.montrerFusil(Acteur.DEALER, joueur);
                } else {
                    // Symetrie du rituel : le joueur a fini, le fusil se pose
                    // cote dealer -- main vide -- et il ne le "ramasse"
                    // qu'en visant.
                    scene.montrerFusil(Acteur.JOUEUR, joueur);
                    scene.poserFusilDealer();
                }
                long delai = aleatoire.nextInt(reflexionMin, reflexionMax + 1);
                programmer(courante, delai, () -> jouerDealer(courante));
            }
            default -> { }
        }
    }

    private void lancerRechargement(SessionPartie courante) {
        Player joueur = joueur(courante);
        if (joueur == null) return;
        courante.verrouiller(true);
        annulerPriseFusil();
        fusilEnMainDealer = false;
        inventaire.preparerAnimationFusil(joueur);
        scene.montrerFusil(Acteur.JOUEUR, joueur);
        animateur.jouer(joueur, "reload");
        programmer(courante, animateur.dureeTicks("reload") + 2L,
                () -> traiter(courante, courante.moteur().terminerRechargement()));
    }

    private void programmer(SessionPartie courante, long delai, Runnable action) {
        UUID id = courante.id();
        BukkitTask tache = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (session != null && session.id().equals(id) && !session.annulee()) {
                try {
                    action.run();
                } catch (RuntimeException erreur) {
                    plugin.getLogger().log(java.util.logging.Level.SEVERE,
                            "session " + id + " : " + erreur.getMessage(), erreur);
                    // JAMAIS de remboursement ici : un joueur peut provoquer
                    // l'exception (warp inter-mondes en plein tour) et
                    // annulerait ainsi une partie perdue sans rien payer.
                    annuler(courante.joueurId(), "The game was cancelled after an error.", false);
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

    public void annuler(UUID joueurId, String message) {
        annuler(joueurId, message, false);
    }

    /**
     * {@code rembourser} : vrai pour une fin TECHNIQUE (erreur, arret
     * serveur, mort vanilla hors jeu) -- la mise revient au joueur. Une
     * defaite ou un abandon en plein round la perd, un cashout est deja
     * paye par gains().
     */
    public void annuler(UUID joueurId, String message, boolean rembourser) {
        SessionPartie courante = session;
        if (courante == null || !courante.joueurId().equals(joueurId)) return;
        // Partie reglee (round perdu, gain paye) : plus aucun remboursement,
        // quelle que soit la raison de l'annulation.
        rembourser = courante.rembourserAutorise(rembourser);
        courante.annuler();
        if (suiviVisee != null) { suiviVisee.cancel(); suiviVisee = null; }
        poseForcee = false;
        reprisePendante = null;
        joueurToucheAuRound3 = false;
        annulerPriseFusil();
        fusilEnMainDealer = false;
        // Hors du bloc joueur : deconnecte en pleine question de relance, le
        // fusil et la mallette resteraient poses sur le feutre. Meme regle
        // pour la ligne de coeurs des spectateurs.
        scene.masquerChoixRelance();
        scene.masquerViesJoueur();
        scene.retirerCage();
        attenteSortie.add(joueurId);
        Player joueur = Bukkit.getPlayer(joueurId);
        if (joueur != null) {
            // Si la cinematique de chute tournait encore (deconnexion en
            // pleine chute), rendre le mode de jeu AVANT que le joueur parte,
            // sinon il se reconnecterait en spectateur.
            if (joueur.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
                cinematiqueSpectateur = null;
                joueur.setSpectatorTarget(null);
                joueur.setGameMode(org.bukkit.GameMode.SURVIVAL);
            }
            scene.cacherPose(joueur);
            animateur.annuler(joueur);
            ecranNoir.nettoyer(joueur);
            scene.arreterMusique(joueur);
            scene.reposerFusil(joueur);
            scene.finViseeDealer();
            scene.synchroniserMenottes(false, false, joueur);
            scene.masquerViesDealer();
            scene.ranimerDealer();
            scene.peauDealerPourRound(1);
            scene.musiquePourRound(1);
            scene.sangPourRound(1);
            // L'ambiance reprend aussitot sur le theme calme : la musique
            // joue en continu (decision finale user 2026-08-27), et
            // arreterMusique pose un verrou de sortie bien trop large (90
            // blocs) pour etre laisse en place apres une partie.
            if (joueur.isOnline()) scene.demarrerMusique(joueur);
            inventaire.restaurer(joueur);
            if (rembourser && miseCourante > 0 && banque.disponible()) {
                banque.crediter(joueur, miseCourante);
                joueur.sendMessage(Component.text("Your $"
                        + net.thundranode.buckshot.Mises.formater(miseCourante)
                        + " bet was refunded.", NamedTextColor.GOLD));
            }
            if (message != null) joueur.sendMessage(Component.text(message, NamedTextColor.RED));
        }
        miseCourante = 0;
        session = null;
    }

    public void arreter() {
        if (tacheApproche != null) {
            tacheApproche.cancel();
            tacheApproche = null;
        }
        if (session != null) annuler(session.joueurId(), "Game stopped by the server.", true);
    }

    /** Vrai des qu'une partie tourne, quel que soit le joueur assis a la table. */
    public boolean partieEnCours() {
        return session != null;
    }

    public boolean estEnPartie(UUID joueurId) {
        return session != null && session.joueurId().equals(joueurId);
    }

    public boolean estVerrouille(UUID joueurId) {
        // Pendant la question de relance le verrou de partie tient toujours
        // (ni tir ni objet), mais la molette doit rester libre : c'est elle
        // qui sert a choisir entre le fusil et la mallette.
        return estEnPartie(joueurId) && session.verrouille() && reprisePendante == null;
    }

    /** Le fusil du joueur quitte la table ET sa main ; l'etat dealer ne bouge pas. */
    private void annulerPriseFusil() {
        attentePriseFusil = false;
        fusilEnMain = false;
        scene.retirerFusilAPrendre();
    }

    /**
     * Clic sur une entite de la table. Seul le fusil a prendre reagit : le
     * ramasser remplit les slots 0 et 1 de la hotbar et met l'arme en main.
     */
    public void clicEntite(Player joueur, org.bukkit.entity.Entity entite) {
        if (!scene.estPriseFusil(entite)) return;
        SessionPartie courante = sessionJoueur(joueur);
        if (courante == null || courante.verrouille() || !attentePriseFusil) return;
        attentePriseFusil = false;
        fusilEnMain = true;
        scene.retirerFusilAPrendre();
        inventaire.preparerHotbar(joueur, courante.id(),
                courante.moteur().participant(Acteur.JOUEUR), true);
        armerGraceBedrock(joueur);
        joueur.playSound(net.kyori.adventure.sound.Sound.sound(
                org.bukkit.Sound.ITEM_ARMOR_EQUIP_IRON.key(),
                net.kyori.adventure.sound.Sound.Source.PLAYER, 0.9f, 0.9f),
                net.kyori.adventure.sound.Sound.Emitter.self());
    }

    /**
     * Grace de tir des joueurs Bedrock : sur tactile, le tap qui RAMASSE le
     * fusil envoie aussi un swing de bras, et le swing vaut tir chez eux --
     * le coup partait dans la foulee du ramassage (constat user 2026-08-29).
     * Quelques ticks de silence apres l'armement de la hotbar suffisent.
     */
    private final java.util.Map<UUID, Integer> graceTirBedrock = new java.util.HashMap<>();

    private void armerGraceBedrock(Player joueur) {
        graceTirBedrock.put(joueur.getUniqueId(), Bukkit.getCurrentTick() + 10);
    }

    public boolean tirBedrockAutorise(UUID joueurId) {
        Integer jusqu = graceTirBedrock.get(joueurId);
        return jusqu == null || Bukkit.getCurrentTick() >= jusqu;
    }

    /** Vrai si la camera de cinematique de ce joueur doit rester arrimee. */
    public boolean estEnCinematique(UUID joueurId) {
        return joueurId.equals(cinematiqueSpectateur);
    }

    public InventairePartie inventaire() {
        return inventaire;
    }

    private SessionPartie sessionJoueur(Player joueur) {
        return estEnPartie(joueur.getUniqueId()) ? session : null;
    }

    private Player joueur(SessionPartie courante) {
        return Bukkit.getPlayer(courante.joueurId());
    }

    private boolean active(SessionPartie courante) {
        return session == courante && !courante.annulee();
    }


    private static String nom(Acteur acteur) {
        return acteur == Acteur.JOUEUR ? "The player" : "DrDonutt";
    }

    /** Nom anglais d'un objet : les constantes du moteur restent en francais. */
    private static String nomObjet(Objet objet) {
        return switch (objet) {
            case CIGARETTES -> "cigarettes";
            case BIERE -> "beer";
            case MENOTTES -> "handcuffs";
            case COUTEAU -> "knife";
            case LOUPE -> "the magnifier";
        };
    }

    /** Idem pour le type de cartouche : REELLE/BLANCHE se disent live/blank. */
    private static String nomCartouche(TypeCartouche type) {
        return type == TypeCartouche.REELLE ? "live" : "blank";
    }

    private static void erreur(Player joueur, String texte) {
        joueur.sendMessage(Component.text(texte, NamedTextColor.RED));
    }
}
