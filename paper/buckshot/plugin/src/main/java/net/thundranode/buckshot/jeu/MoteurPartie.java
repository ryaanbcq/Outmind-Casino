package net.thundranode.buckshot.jeu;

import net.thundranode.buckshot.ia.VueIA;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntFunction;
import java.util.random.RandomGenerator;

/** Machine à états pure. Toutes ses méthodes sont appelées séquentiellement. */
public final class MoteurPartie {

    private record TirEnAttente(Acteur tireur, Acteur cible, TypeCartouche cartouche, int degats) {}

    private final Regles regles;
    private final RandomGenerator aleatoire;
    private final IntFunction<Chargeur> fabriqueChargeur;
    /** Dernier round de la partie : 3 en solo, 1 en duel joueur contre joueur. */
    private final int roundFinal;
    private final Map<Acteur, Participant> participants = new EnumMap<>(Acteur.class);
    private PhasePartie phase = PhasePartie.LIBRE;
    private int round;
    private int numeroChargeur;
    private Acteur tour = Acteur.JOUEUR;
    private Chargeur chargeur;
    private TirEnAttente tirEnAttente;

    public MoteurPartie(Regles regles, RandomGenerator aleatoire) {
        this(regles, aleatoire, (IntFunction<Chargeur>) null);
    }

    public MoteurPartie(Regles regles, RandomGenerator aleatoire,
                         IntFunction<Chargeur> fabriqueChargeur) {
        this(regles, aleatoire, fabriqueChargeur, 3);
    }

    /** Variante a nombre de rounds choisi (1 pour le duel PvP). */
    public MoteurPartie(Regles regles, RandomGenerator aleatoire,
                         IntFunction<Chargeur> fabriqueChargeur, int roundFinal) {
        this.regles = Objects.requireNonNull(regles, "regles");
        this.aleatoire = Objects.requireNonNull(aleatoire, "aleatoire");
        if (roundFinal < 1) throw new IllegalArgumentException("roundFinal < 1");
        this.roundFinal = roundFinal;
        // Nulle en jeu normal : la fabrique injectee ne sert qu'aux tests,
        // la generation reelle depend du round courant, inconnu d'une lambda
        // construite avant la partie.
        this.fabriqueChargeur = fabriqueChargeur;
        participants.put(Acteur.JOUEUR, new Participant(regles.viesPourRound(1), regles.viesPlafond(), regles.objetsMax()));
        participants.put(Acteur.DEALER, new Participant(regles.viesPourRound(1), regles.viesPlafond(), regles.objetsMax()));
    }

    public ResultatAction demarrer() {
        if (phase != PhasePartie.LIBRE) {
            return ResultatAction.refuse("the game has already started");
        }
        round = 1;
        tour = Acteur.JOUEUR;
        List<EvenementPartie> evenements = new ArrayList<>();
        commencerRound(evenements);
        return ResultatAction.ok(evenements);
    }

    public ResultatAction terminerRechargement() {
        if (phase != PhasePartie.RECHARGEMENT) {
            return ResultatAction.refuse("no reload in progress");
        }
        List<EvenementPartie> evenements = new ArrayList<>();
        definirTourDisponible(tour, evenements);
        return ResultatAction.ok(evenements);
    }

    public ResultatAction tirer(Acteur acteur, Cible cible) {
        if (!estTour(acteur)) {
            return ResultatAction.refuse("it is not that player's turn");
        }
        if (chargeur == null || chargeur.estVide()) {
            return ResultatAction.refuse("the magazine is empty");
        }
        TypeCartouche cartouche = chargeur.retirerChambre();
        Participant tireur = participant(acteur);
        int degats = tireur.consommerDegatsProchainTir();
        Acteur acteurCible = cible == Cible.SOI ? acteur : acteur.oppose();
        participants.values().forEach(Participant::oublierChambre);
        tirEnAttente = new TirEnAttente(acteur, acteurCible, cartouche, degats);
        phase = PhasePartie.ECRAN_NOIR;
        List<EvenementPartie> evenements = new ArrayList<>();
        evenements.add(new EvenementPartie.Visee(acteur, cible));
        if (cartouche == TypeCartouche.REELLE) {
            evenements.add(new EvenementPartie.BlackoutDemande(regles.blackoutTicks()));
        }
        return ResultatAction.ok(evenements);
    }

    /**
     * Vrai si le tir deja declare va vider les vies de sa cible.
     *
     * <p>La cartouche et les degats sont tires des la declaration du tir :
     * seule leur REVELATION attend l'ecran noir. La mise en scene peut donc
     * savoir, des le coup de feu, si elle doit jouer le son de mort plutot
     * que celui d'un simple impact.
     */
    public boolean tirEnAttenteMortel() {
        return tirEnAttente != null
                && tirEnAttente.cartouche() == TypeCartouche.REELLE
                && participant(tirEnAttente.cible()).vies() <= tirEnAttente.degats();
    }

    /**
     * Vrai si le tir deja declare vise le joueur humain.
     *
     * <p>Se lit pendant l'ecran noir, avant {@link #reveler()} : c'est la
     * seule fenetre ou la mise en scene sait qui encaisse sans que le coup
     * soit encore resolu.
     */
    public boolean tirEnAttenteViseJoueur() {
        return tirEnAttente != null && tirEnAttente.cible() == Acteur.JOUEUR;
    }

    public ResultatAction reveler() {
        if (phase != PhasePartie.ECRAN_NOIR || tirEnAttente == null) {
            return ResultatAction.refuse("no shot to reveal");
        }
        TirEnAttente tir = tirEnAttente;
        tirEnAttente = null;
        List<EvenementPartie> evenements = new ArrayList<>();
        evenements.add(new EvenementPartie.CartoucheRevelee(tir.cartouche(), false));
        if (tir.cartouche() == TypeCartouche.REELLE) {
            Participant cible = participant(tir.cible());
            cible.subir(tir.degats());
            evenements.add(new EvenementPartie.ViesChangees(
                    tir.cible(), cible.vies(), tir.degats()));
            if (cible.vies() == 0) {
                // Le vainqueur du round est le SURVIVANT, pas le tireur :
                // sur un auto-tir mortel, les deux different, et crediter le
                // tireur declarait le mort vainqueur de son propre round.
                terminerRound(tir.cible().oppose(), evenements);
                return ResultatAction.ok(evenements);
            }
        }

        Acteur prochain = tir.cartouche() == TypeCartouche.BLANCHE && tir.cible() == tir.tireur()
                ? tir.tireur() : tir.tireur().oppose();
        tour = prochain;
        if (chargeur.estVide()) {
            recharger(evenements);
        } else {
            definirTourDisponible(prochain, evenements);
        }
        return ResultatAction.ok(evenements);
    }

    public ResultatAction utiliser(Acteur acteur, Objet objet) {
        if (!estTour(acteur)) {
            return ResultatAction.refuse("items can only be used on your turn");
        }
        Participant utilisateur = participant(acteur);
        if (!utilisateur.possede(objet)) {
            return ResultatAction.refuse("you do not have that item");
        }
        // Au dernier coeur du ROUND FINAL, plus de cigarette (regle user
        // 2026-08-27) : la mort a un coup ne s'y rachete pas. Aux rounds 1
        // et 2 elle reste jouable. La strategie du dealer connait la regle
        // et n'essaie plus dans cette situation.
        if (objet == Objet.CIGARETTES && round >= roundFinal && utilisateur.vies() <= 1) {
            return ResultatAction.refuse("no smoking on your last life");
        }

        List<EvenementPartie> evenements = new ArrayList<>();
        boolean applique = switch (objet) {
            case CIGARETTES -> {
                boolean soigne = utilisateur.soigner();
                if (soigne) {
                    evenements.add(new EvenementPartie.ViesChangees(acteur, utilisateur.vies(), -1));
                }
                yield soigne;
            }
            case BIERE -> {
                if (chargeur == null || chargeur.estVide()) {
                    yield false;
                }
                TypeCartouche ejectee = chargeur.retirerChambre();
                participants.values().forEach(Participant::oublierChambre);
                evenements.add(new EvenementPartie.CartoucheRevelee(ejectee, true));
                yield true;
            }
            case MENOTTES -> participant(acteur.oppose()).menotterPourUnTour();
            case COUTEAU -> utilisateur.activerTirDouble();
            case LOUPE -> {
                if (chargeur == null || chargeur.estVide()) {
                    yield false;
                }
                TypeCartouche connue = chargeur.observerChambre();
                utilisateur.memoriserChambre(connue);
                evenements.add(new EvenementPartie.ChambrePrivee(acteur, connue));
                yield true;
            }
        };

        if (!applique) {
            return ResultatAction.refuse("that item has no effect right now");
        }
        utilisateur.retirerObjet(objet);
        evenements.add(0, new EvenementPartie.ObjetUtilise(acteur, objet));
        if (objet == Objet.BIERE && chargeur.estVide()) {
            recharger(evenements);
        }
        return ResultatAction.ok(evenements);
    }

    private void commencerRound(List<EvenementPartie> evenements) {
        participants.values().forEach(p -> p.reinitialiserRound(regles.viesPourRound(round)));
        tirEnAttente = null;
        tour = Acteur.JOUEUR;
        evenements.add(new EvenementPartie.RoundCommence(round));
        recharger(evenements);
    }

    private void recharger(List<EvenementPartie> evenements) {
        phase = PhasePartie.RECHARGEMENT;
        numeroChargeur++;
        chargeur = fabriqueChargeur != null
                ? fabriqueChargeur.apply(numeroChargeur)
                : Chargeur.creer(regles.chargeurPourRound(round), aleatoire);
        if (chargeur.composition().total() < 2) {
            throw new IllegalStateException("chargeur produit avec moins de deux cartouches");
        }
        participants.values().forEach(Participant::oublierChambre);
        Chargeur.Composition composition = chargeur.composition();
        evenements.add(new EvenementPartie.ChargeurAnnonce(composition.reelles(), composition.blanches()));
        distribuerObjets(evenements);
    }

    private void distribuerObjets(List<EvenementPartie> evenements) {
        int demandes = regles.objetsPourRound(round);
        for (Acteur acteur : Acteur.values()) {
            Participant participant = participant(acteur);
            List<Objet> recus = new ArrayList<>();
            for (int i = 0; i < demandes && participant.placesLibres() > 0; i++) {
                Objet objet = Objet.values()[aleatoire.nextInt(Objet.values().length)];
                participant.ajouterObjet(objet);
                recus.add(objet);
            }
            if (!recus.isEmpty()) {
                evenements.add(new EvenementPartie.ObjetsDistribues(acteur, recus));
            }
        }
    }

    private void terminerRound(Acteur vainqueur, List<EvenementPartie> evenements) {
        phase = PhasePartie.FIN_ROUND;
        evenements.add(new EvenementPartie.RoundTermine(round, vainqueur));
        if (round == roundFinal) {
            phase = PhasePartie.FIN_PARTIE;
            evenements.add(new EvenementPartie.PartieTerminee(vainqueur));
            return;
        }
        round++;
        commencerRound(evenements);
    }

    private void definirTourDisponible(Acteur candidat, List<EvenementPartie> evenements) {
        Acteur choisi = candidat;
        int garde = 0;
        while (participant(choisi).toursASauter() > 0) {
            // Le compte annonce inclut le tour qu'on saute a l'instant. En
            // decrementant d'abord, le dernier tour saute s'annoncait
            // "0 restant" alors que la victime etait encore bloquee.
            evenements.add(new EvenementPartie.TourSaute(choisi, participant(choisi).toursASauter()));
            participant(choisi).consommerTourSaute();
            choisi = choisi.oppose();
            if (++garde > 4) {
                throw new IllegalStateException("boucle de tours sautes");
            }
        }
        // Prendre la main lave les menottes : c'est ce qui rouvre le droit de
        // menotter l'adversaire, et non la seule retombee du compteur.
        participant(choisi).prendreLaMain();
        tour = choisi;
        phase = choisi == Acteur.JOUEUR ? PhasePartie.TOUR_JOUEUR : PhasePartie.TOUR_DEALER;
        evenements.add(new EvenementPartie.TourChange(choisi));
    }

    private boolean estTour(Acteur acteur) {
        return tour == acteur && ((acteur == Acteur.JOUEUR && phase == PhasePartie.TOUR_JOUEUR)
                || (acteur == Acteur.DEALER && phase == PhasePartie.TOUR_DEALER));
    }

    public Participant participant(Acteur acteur) {
        return participants.get(acteur);
    }

    public PhasePartie phase() {
        return phase;
    }

    public int round() {
        return round;
    }

    public Acteur tour() {
        return tour;
    }

    public Chargeur.Composition composition() {
        return chargeur == null ? new Chargeur.Composition(0, 0) : chargeur.composition();
    }

    public VueIA vueIA() {
        Chargeur.Composition publique = composition();
        Participant dealer = participant(Acteur.DEALER);
        Participant joueur = participant(Acteur.JOUEUR);
        return new VueIA(
                round,
                publique.reelles(),
                publique.blanches(),
                dealer.vies(),
                joueur.vies(),
                dealer.objets(),
                dealer.toursASauter(),
                joueur.toursASauter(),
                joueur.menottable(),
                dealer.prochainTirDouble(),
                dealer.chambreConnue());
    }
}
