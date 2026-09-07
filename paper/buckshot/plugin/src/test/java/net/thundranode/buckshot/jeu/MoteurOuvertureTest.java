package net.thundranode.buckshot.jeu;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static net.thundranode.buckshot.jeu.TypeCartouche.REELLE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Ouvreur du premier round : le duel le tire a pile ou face (demarrer(Acteur)),
 * le solo garde l'humain premier (demarrer()). Les rounds suivants repartent
 * toujours du JOUEUR, quel que soit l'ouvreur du round 1.
 */
class MoteurOuvertureTest {

    private static Regles reglesDuel() {
        return new Regles(List.of(6, 6, 6), 6, List.of(
                new Regles.PlageChargeur(3, 6, 1, 3),
                new Regles.PlageChargeur(3, 6, 1, 3),
                new Regles.PlageChargeur(3, 6, 1, 3)), List.of(0, 0, 0), 8, 40);
    }

    @Test
    void leSoloOuvreToujoursAvecLeJoueur() {
        MoteurPartie moteur = new MoteurPartie(Regles.standard(), new Random(1),
                i -> Chargeur.depuis(List.of(REELLE, REELLE, REELLE)));
        assertTrue(moteur.demarrer().acceptee());
        assertTrue(moteur.terminerRechargement().acceptee());
        assertEquals(Acteur.JOUEUR, moteur.tour());
        assertEquals(PhasePartie.TOUR_JOUEUR, moteur.phase());
        assertFalse(moteur.tirer(Acteur.DEALER, Cible.ADVERSAIRE).acceptee(),
                "en solo le dealer ne tire jamais le premier");
    }

    @Test
    void lOuvreurJoueurEstHonore() {
        MoteurPartie moteur = new MoteurPartie(reglesDuel(), new Random(1),
                i -> Chargeur.depuis(List.of(REELLE, REELLE, REELLE)), 1);
        assertTrue(moteur.demarrer(Acteur.JOUEUR).acceptee());
        assertTrue(moteur.terminerRechargement().acceptee());
        assertEquals(Acteur.JOUEUR, moteur.tour());
        assertEquals(PhasePartie.TOUR_JOUEUR, moteur.phase());
    }

    @Test
    void lOuvreurDealerEstHonore() {
        MoteurPartie moteur = new MoteurPartie(reglesDuel(), new Random(1),
                i -> Chargeur.depuis(List.of(REELLE, REELLE, REELLE)), 1);
        assertTrue(moteur.demarrer(Acteur.DEALER).acceptee());
        assertTrue(moteur.terminerRechargement().acceptee());
        assertEquals(Acteur.DEALER, moteur.tour());
        assertEquals(PhasePartie.TOUR_DEALER, moteur.phase());
        assertFalse(moteur.tirer(Acteur.JOUEUR, Cible.ADVERSAIRE).acceptee(),
                "le second joueur (role DEALER) a gagne le tirage : l'autre attend");
        assertTrue(moteur.tirer(Acteur.DEALER, Cible.ADVERSAIRE).acceptee());
    }

    @Test
    void lOuvreurNeVautQuePourLePremierRound() {
        // Trois rounds, ouvreur DEALER au round 1 : le round 2 repart du JOUEUR.
        MoteurPartie moteur = new MoteurPartie(Regles.standard(), new Random(1),
                i -> Chargeur.depuis(List.of(REELLE, REELLE, REELLE)));
        assertTrue(moteur.demarrer(Acteur.DEALER).acceptee());
        assertTrue(moteur.terminerRechargement().acceptee());
        assertEquals(Acteur.DEALER, moteur.tour());
        // Round 1, 3 vies chacun : dealer -> joueur, joueur -> dealer,
        // dealer -> joueur, joueur -> dealer, dealer -> joueur (le joueur tombe).
        for (int coup = 0; coup < 5; coup++) {
            if (moteur.phase() == PhasePartie.RECHARGEMENT) {
                assertTrue(moteur.terminerRechargement().acceptee());
            }
            assertTrue(moteur.tirer(moteur.tour(), Cible.ADVERSAIRE).acceptee());
            assertTrue(moteur.reveler().acceptee());
        }
        assertEquals(2, moteur.round(), "le round 2 doit s'ouvrir");
        assertTrue(moteur.terminerRechargement().acceptee());
        assertEquals(Acteur.JOUEUR, moteur.tour(), "le round 2 repart du JOUEUR");
    }

    @Test
    void demarrerAvecOuvreurRefuseUnePartieDejaLancee() {
        MoteurPartie moteur = new MoteurPartie(reglesDuel(), new Random(1),
                i -> Chargeur.depuis(List.of(REELLE, REELLE, REELLE)), 1);
        assertTrue(moteur.demarrer(Acteur.DEALER).acceptee());
        assertFalse(moteur.demarrer(Acteur.JOUEUR).acceptee());
        assertThrows(NullPointerException.class, () -> new MoteurPartie(reglesDuel(),
                new Random(1), i -> Chargeur.depuis(List.of(REELLE, REELLE, REELLE)), 1)
                .demarrer((Acteur) null));
    }
}
