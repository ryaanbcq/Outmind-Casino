package net.thundranode.buckshot.jeu;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static net.thundranode.buckshot.jeu.TypeCartouche.BLANCHE;
import static net.thundranode.buckshot.jeu.TypeCartouche.REELLE;
import static org.junit.jupiter.api.Assertions.*;

class MoteurPartieSmokeTest {

    private static MoteurPartie pret(List<TypeCartouche> ordre) {
        MoteurPartie moteur = new MoteurPartie(Regles.standard(), new Random(1),
                i -> Chargeur.depuis(ordre));
        assertTrue(moteur.demarrer().acceptee());
        assertTrue(moteur.terminerRechargement().acceptee());
        return moteur;
    }

    @Test
    void matriceDesTirsEtPassageDeTour() {
        MoteurPartie adversaireReelle = pret(List.of(REELLE, BLANCHE, BLANCHE, BLANCHE, BLANCHE, BLANCHE));
        adversaireReelle.tirer(Acteur.JOUEUR, Cible.ADVERSAIRE);
        adversaireReelle.reveler();
        assertEquals(2, adversaireReelle.participant(Acteur.DEALER).vies());
        assertEquals(Acteur.DEALER, adversaireReelle.tour());

        MoteurPartie adversaireBlanche = pret(List.of(BLANCHE, REELLE, BLANCHE, BLANCHE, BLANCHE, BLANCHE));
        adversaireBlanche.tirer(Acteur.JOUEUR, Cible.ADVERSAIRE);
        adversaireBlanche.reveler();
        assertEquals(3, adversaireBlanche.participant(Acteur.DEALER).vies());
        assertEquals(Acteur.DEALER, adversaireBlanche.tour());

        MoteurPartie soiBlanche = pret(List.of(BLANCHE, REELLE, BLANCHE, BLANCHE, BLANCHE, BLANCHE));
        soiBlanche.tirer(Acteur.JOUEUR, Cible.SOI);
        soiBlanche.reveler();
        assertEquals(3, soiBlanche.participant(Acteur.JOUEUR).vies());
        assertEquals(Acteur.JOUEUR, soiBlanche.tour());

        MoteurPartie soiReelle = pret(List.of(REELLE, BLANCHE, BLANCHE, BLANCHE, BLANCHE, BLANCHE));
        soiReelle.tirer(Acteur.JOUEUR, Cible.SOI);
        soiReelle.reveler();
        assertEquals(2, soiReelle.participant(Acteur.JOUEUR).vies());
        assertEquals(Acteur.DEALER, soiReelle.tour());
    }

    @Test
    void blackoutUniquementPourUneCartoucheReelle() {
        MoteurPartie blanche = pret(List.of(BLANCHE, REELLE, BLANCHE, BLANCHE, BLANCHE, BLANCHE));
        ResultatAction tirBlanc = blanche.tirer(Acteur.JOUEUR, Cible.ADVERSAIRE);
        assertTrue(tirBlanc.evenements().stream()
                .noneMatch(EvenementPartie.BlackoutDemande.class::isInstance));

        MoteurPartie reelle = pret(List.of(REELLE, BLANCHE, BLANCHE, BLANCHE, BLANCHE, BLANCHE));
        ResultatAction tirReel = reelle.tirer(Acteur.JOUEUR, Cible.ADVERSAIRE);
        assertTrue(tirReel.evenements().stream()
                .anyMatch(EvenementPartie.BlackoutDemande.class::isInstance));
    }

    @Test
    void chargeurVideRechargeSansChangerDeRound() {
        MoteurPartie moteur = pret(List.of(BLANCHE, BLANCHE, BLANCHE, BLANCHE, BLANCHE, BLANCHE));
        for (int i = 0; i < 6; i++) {
            Acteur acteur = moteur.tour();
            assertTrue(moteur.tirer(acteur, Cible.ADVERSAIRE).acceptee());
            assertTrue(moteur.reveler().acceptee());
        }
        assertEquals(1, moteur.round());
        assertEquals(PhasePartie.RECHARGEMENT, moteur.phase());
        assertEquals(6, moteur.composition().total());
    }

    @Test
    void troisRoundsSontJouesEtSeulLeTroisiemeDecide() {
        // Un chargeur par round, taille sur les vies de CE round (3, 4, 5) :
        // seul le tireur qui ouvre le round place les reelles, une sur deux.
        List<TypeCartouche> dealerPerdEnTrois = List.of(
                REELLE, BLANCHE, REELLE, BLANCHE, REELLE, BLANCHE);
        List<TypeCartouche> dealerPerdEnQuatre = List.of(
                REELLE, BLANCHE, REELLE, BLANCHE, REELLE, BLANCHE, REELLE, BLANCHE);
        List<TypeCartouche> joueurPerdEnCinq = List.of(
                BLANCHE, REELLE, BLANCHE, REELLE, BLANCHE, REELLE, BLANCHE, REELLE, BLANCHE, REELLE);
        List<List<TypeCartouche>> rounds =
                List.of(dealerPerdEnTrois, dealerPerdEnQuatre, joueurPerdEnCinq);
        AtomicInteger index = new AtomicInteger();
        MoteurPartie moteur = new MoteurPartie(Regles.standard(), new Random(4),
                i -> Chargeur.depuis(rounds.get(index.getAndIncrement())));
        moteur.demarrer();

        // Escalade des vies : 3 au round 1, 4 au round 2, 5 au round final.
        gagnerRound(moteur, Acteur.JOUEUR);
        assertEquals(2, moteur.round());
        assertEquals(4, moteur.participant(Acteur.JOUEUR).vies());
        assertEquals(4, moteur.participant(Acteur.DEALER).vies());

        gagnerRound(moteur, Acteur.JOUEUR);
        assertEquals(3, moteur.round());
        assertEquals(5, moteur.participant(Acteur.JOUEUR).vies());
        assertEquals(5, moteur.participant(Acteur.DEALER).vies());

        ResultatAction fin = gagnerRound(moteur, Acteur.DEALER);
        assertEquals(PhasePartie.FIN_PARTIE, moteur.phase());
        EvenementPartie.PartieTerminee partie = fin.evenements().stream()
                .filter(EvenementPartie.PartieTerminee.class::isInstance)
                .map(EvenementPartie.PartieTerminee.class::cast)
                .findFirst().orElseThrow();
        assertEquals(Acteur.DEALER, partie.vainqueur());
    }

    private static ResultatAction gagnerRound(MoteurPartie moteur, Acteur vainqueur) {
        assertEquals(PhasePartie.RECHARGEMENT, moteur.phase());
        moteur.terminerRechargement();
        ResultatAction dernier = null;
        while (moteur.phase() != PhasePartie.RECHARGEMENT
                && moteur.phase() != PhasePartie.FIN_PARTIE) {
            Acteur acteur = moteur.tour();
            Cible cible = acteur == vainqueur ? Cible.ADVERSAIRE : Cible.ADVERSAIRE;
            moteur.tirer(acteur, cible);
            dernier = moteur.reveler();
            if (dernier.evenements().stream().anyMatch(EvenementPartie.RoundTermine.class::isInstance)) {
                return dernier;
            }
        }
        fail("round non termine");
        return dernier;
    }
}
