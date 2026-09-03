package net.thundranode.buckshot.jeu;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static net.thundranode.buckshot.jeu.TypeCartouche.BLANCHE;
import static net.thundranode.buckshot.jeu.TypeCartouche.REELLE;
import static org.junit.jupiter.api.Assertions.*;

class ObjetsSmokeTest {

    private static MoteurPartie pret(List<TypeCartouche> ordre) {
        MoteurPartie moteur = new MoteurPartie(Regles.standard(), new Random(2),
                i -> Chargeur.depuis(ordre));
        moteur.demarrer();
        moteur.terminerRechargement();
        return moteur;
    }

    @Test
    void cigarettesSoignentJusquAuPlafondPuisNeSeConsommentPlus() {
        MoteurPartie moteur = pret(List.of(BLANCHE, REELLE, BLANCHE, REELLE, BLANCHE, REELLE));
        Participant joueur = moteur.participant(Acteur.JOUEUR);
        assertEquals(3, joueur.vies());

        // Depart a trois vies, plafond a cinq : deux donuts passent.
        joueur.ajouterObjet(Objet.CIGARETTES);
        assertTrue(moteur.utiliser(Acteur.JOUEUR, Objet.CIGARETTES).acceptee());
        assertEquals(4, joueur.vies());
        joueur.ajouterObjet(Objet.CIGARETTES);
        assertTrue(moteur.utiliser(Acteur.JOUEUR, Objet.CIGARETTES).acceptee());
        assertEquals(5, joueur.vies());

        // Au plafond le donut est refuse, et surtout il n'est pas consomme.
        joueur.ajouterObjet(Objet.CIGARETTES);
        assertFalse(moteur.utiliser(Acteur.JOUEUR, Objet.CIGARETTES).acceptee());
        assertTrue(joueur.possede(Objet.CIGARETTES));
        assertEquals(5, joueur.vies());
    }

    @Test
    void biereEjecteEtLoupeEstPriveePuisInvalidee() {
        MoteurPartie moteur = pret(List.of(REELLE, BLANCHE, BLANCHE, BLANCHE, BLANCHE, BLANCHE));
        Participant joueur = moteur.participant(Acteur.JOUEUR);
        joueur.ajouterObjet(Objet.LOUPE);
        ResultatAction loupe = moteur.utiliser(Acteur.JOUEUR, Objet.LOUPE);
        assertTrue(loupe.acceptee());
        assertEquals(REELLE, joueur.chambreConnue().orElseThrow());
        assertTrue(loupe.evenements().stream().anyMatch(EvenementPartie.ChambrePrivee.class::isInstance));

        joueur.ajouterObjet(Objet.BIERE);
        ResultatAction biere = moteur.utiliser(Acteur.JOUEUR, Objet.BIERE);
        assertTrue(biere.acceptee());
        assertTrue(joueur.chambreConnue().isEmpty());
        assertEquals(5, moteur.composition().total());
    }

    @Test
    void couteauDoubleLeProchainTirEtMenottesSautentUnTour() {
        MoteurPartie moteur = pret(List.of(REELLE, BLANCHE, BLANCHE, BLANCHE, BLANCHE, BLANCHE));
        Participant joueur = moteur.participant(Acteur.JOUEUR);
        joueur.ajouterObjet(Objet.COUTEAU);
        joueur.ajouterObjet(Objet.MENOTTES);
        assertTrue(moteur.utiliser(Acteur.JOUEUR, Objet.COUTEAU).acceptee());
        assertTrue(moteur.utiliser(Acteur.JOUEUR, Objet.MENOTTES).acceptee());

        moteur.tirer(Acteur.JOUEUR, Cible.ADVERSAIRE);
        moteur.reveler();
        assertEquals(1, moteur.participant(Acteur.DEALER).vies());
        // Le seul tour saute est consomme des la fin de ce tir : le joueur
        // rejoue une fois, puis la main repart au dealer.
        assertEquals(Acteur.JOUEUR, moteur.tour());
        assertEquals(0, moteur.participant(Acteur.DEALER).toursASauter());

        moteur.tirer(Acteur.JOUEUR, Cible.ADVERSAIRE);
        moteur.reveler();
        assertEquals(Acteur.DEALER, moteur.tour());
    }

    @Test
    void menottesNeSeChainentPasEtAnnoncentLeTourEnCours() {
        MoteurPartie moteur = pret(List.of(BLANCHE, BLANCHE, BLANCHE, BLANCHE, BLANCHE, BLANCHE));
        Participant joueur = moteur.participant(Acteur.JOUEUR);
        joueur.ajouterObjet(Objet.MENOTTES);
        assertTrue(moteur.utiliser(Acteur.JOUEUR, Objet.MENOTTES).acceptee());

        // Le tour saute est annonce en comptant celui qu'on perd a
        // l'instant meme, donc un. Le compteur retombe a zero ici, mais le
        // dealer n'a toujours pas joue : le remenotter maintenant le
        // priverait de tour indefiniment.
        moteur.tirer(Acteur.JOUEUR, Cible.ADVERSAIRE);
        assertEquals(1, sautesAnnonces(moteur.reveler()));
        assertEquals(0, moteur.participant(Acteur.DEALER).toursASauter());
        assertFalse(moteur.participant(Acteur.DEALER).menottable());
        joueur.ajouterObjet(Objet.MENOTTES);
        assertFalse(moteur.utiliser(Acteur.JOUEUR, Objet.MENOTTES).acceptee());
        assertTrue(joueur.possede(Objet.MENOTTES), "un objet refuse n'est pas consomme");

        // Le dealer reprend la main : les menottes redeviennent legales.
        moteur.tirer(Acteur.JOUEUR, Cible.ADVERSAIRE);
        moteur.reveler();
        assertEquals(Acteur.DEALER, moteur.tour());
        assertTrue(moteur.participant(Acteur.DEALER).menottable());
    }

    /** Nombre annonce par le premier evenement de tour saute, 0 s'il n'y en a pas. */
    private static int sautesAnnonces(ResultatAction resultat) {
        return resultat.evenements().stream()
                .filter(e -> e instanceof EvenementPartie.TourSaute)
                .map(e -> ((EvenementPartie.TourSaute) e).restants())
                .findFirst().orElse(0);
    }

    @Test
    void inventaireEstStrictementLimiteA8() {
        Participant participant = new Participant(3, 5, 8);
        for (int i = 0; i < 8; i++) {
            assertTrue(participant.ajouterObjet(Objet.CIGARETTES));
        }
        assertFalse(participant.ajouterObjet(Objet.BIERE));
        assertEquals(8, participant.objets().size());
    }

    @Test
    void cigarettesSoignentExactementUneVie() {
        Objet cigarettes = Objet.valueOf("CIGARETTES");
        MoteurPartie moteur = pret(List.of(REELLE, BLANCHE, BLANCHE, BLANCHE, BLANCHE, BLANCHE));
        Participant joueur = moteur.participant(Acteur.JOUEUR);
        joueur.subir(1);
        joueur.ajouterObjet(cigarettes);

        assertTrue(moteur.utiliser(Acteur.JOUEUR, cigarettes).acceptee());
        assertEquals(3, joueur.vies());
        assertFalse(joueur.possede(cigarettes));
    }

    @Test
    void cigarettesJouablesAuDernierCoeurAvantLeRoundFinal() {
        // L'interdiction du dernier coeur ne vaut qu'au round 3 (regle user
        // 2026-08-27) : au round 1, a une vie, la cigarette soigne.
        Objet cigarettes = Objet.valueOf("CIGARETTES");
        MoteurPartie moteur = pret(List.of(REELLE, BLANCHE, BLANCHE, BLANCHE, BLANCHE, BLANCHE));
        Participant joueur = moteur.participant(Acteur.JOUEUR);
        joueur.subir(2);
        joueur.ajouterObjet(cigarettes);

        assertTrue(moteur.utiliser(Acteur.JOUEUR, cigarettes).acceptee());
        assertEquals(2, joueur.vies());
    }

    @Test
    void cigarettesInterditesAuDernierCoeurDuRoundFinal() {
        // Regle user 2026-08-27 : au round final, la mort a un coup ne se
        // rachete pas. Le refus ne consomme pas l'objet.
        List<List<TypeCartouche>> rounds = List.of(
                List.of(REELLE, BLANCHE, REELLE, BLANCHE, REELLE, BLANCHE),
                List.of(REELLE, BLANCHE, REELLE, BLANCHE, REELLE, BLANCHE, REELLE, BLANCHE),
                List.of(BLANCHE, REELLE, BLANCHE, REELLE, BLANCHE, REELLE, BLANCHE, REELLE,
                        BLANCHE, REELLE));
        java.util.concurrent.atomic.AtomicInteger index = new java.util.concurrent.atomic.AtomicInteger();
        MoteurPartie moteur = new MoteurPartie(Regles.standard(), new java.util.Random(4),
                i -> Chargeur.depuis(rounds.get(index.getAndIncrement())));
        moteur.demarrer();
        gagnerRoundEnTirantSurLeDealer(moteur);
        gagnerRoundEnTirantSurLeDealer(moteur);
        assertEquals(3, moteur.round());
        moteur.terminerRechargement();

        Objet cigarettes = Objet.valueOf("CIGARETTES");
        Participant joueur = moteur.participant(Acteur.JOUEUR);
        joueur.subir(4);
        joueur.ajouterObjet(cigarettes);
        assertFalse(moteur.utiliser(Acteur.JOUEUR, cigarettes).acceptee());
        assertEquals(1, joueur.vies());
        assertTrue(joueur.possede(cigarettes));
    }

    /** Deroule un round entier ou chacun tire sur le dealer a son tour. */
    private static void gagnerRoundEnTirantSurLeDealer(MoteurPartie moteur) {
        assertEquals(PhasePartie.RECHARGEMENT, moteur.phase());
        moteur.terminerRechargement();
        while (moteur.phase() != PhasePartie.RECHARGEMENT
                && moteur.phase() != PhasePartie.FIN_PARTIE) {
            moteur.tirer(moteur.tour(), Cible.ADVERSAIRE);
            if (moteur.reveler().evenements().stream()
                    .anyMatch(EvenementPartie.RoundTermine.class::isInstance)) {
                return;
            }
        }
    }
}
