package net.thundranode.buckshot.jeu;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static net.thundranode.buckshot.jeu.TypeCartouche.BLANCHE;
import static net.thundranode.buckshot.jeu.TypeCartouche.REELLE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Le moteur en mode duel : roundFinal = 1, six vies chacun. Le premier
 * participant a zero vie perd la PARTIE entiere - aucun round 2.
 */
class MoteurDuelSmokeTest {

    private static Regles reglesDuel() {
        return new Regles(List.of(6, 6, 6), 6, List.of(
                new Regles.PlageChargeur(3, 6, 1, 3),
                new Regles.PlageChargeur(3, 6, 1, 3),
                new Regles.PlageChargeur(3, 6, 1, 3)), List.of(2, 2, 2), 8, 40);
    }

    private static MoteurPartie pret(List<TypeCartouche> ordre) {
        MoteurPartie moteur = new MoteurPartie(reglesDuel(), new Random(1),
                i -> Chargeur.depuis(ordre), 1);
        assertTrue(moteur.demarrer().acceptee());
        assertTrue(moteur.terminerRechargement().acceptee());
        return moteur;
    }

    @Test
    void videRLesViesTermineLaPartieAuPremierRound() {
        // Douze reelles alternees : chacun tire l'adversaire jusqu'a la mort.
        List<TypeCartouche> quunDesReelles = List.of(
                REELLE, REELLE, REELLE, REELLE, REELLE, REELLE);
        MoteurPartie moteur = pret(quunDesReelles);
        ResultatAction dernier = null;
        // 6 vies a vider, chargeurs de 6 reelles : le joueur 1 tire, encaisse
        // le tour adverse, etc. Chacun descend l'autre - on tire toujours
        // l'adversaire, le premier a zero perd.
        for (int coup = 0; coup < 20 && moteur.phase() != PhasePartie.FIN_PARTIE; coup++) {
            if (moteur.phase() == PhasePartie.RECHARGEMENT) {
                assertTrue(moteur.terminerRechargement().acceptee());
                continue;
            }
            Acteur tireur = moteur.tour();
            assertTrue(moteur.tirer(tireur, Cible.ADVERSAIRE).acceptee());
            dernier = moteur.reveler();
            assertTrue(dernier.acceptee());
        }
        assertEquals(PhasePartie.FIN_PARTIE, moteur.phase());
        assertNotNull(dernier);
        assertTrue(dernier.evenements().stream()
                .anyMatch(EvenementPartie.PartieTerminee.class::isInstance),
                "la mort au round 1 doit terminer la PARTIE, pas ouvrir un round 2");
        assertTrue(dernier.evenements().stream()
                .noneMatch(e -> e instanceof EvenementPartie.RoundCommence),
                "aucun round 2 ne doit commencer en duel");
        assertEquals(1, moteur.round());
    }

    @Test
    void cigaretteInterditeAuDernierCoeurDesLeRoundUn() {
        MoteurPartie moteur = pret(List.of(REELLE, REELLE, REELLE, REELLE, REELLE, BLANCHE));
        // Descendre le joueur 1 a une vie : cinq reelles encaissees.
        for (int coup = 0; coup < 5; coup++) {
            Acteur tireur = moteur.tour();
            assertTrue(moteur.tirer(tireur, Cible.SOI).acceptee());
            assertTrue(moteur.reveler().acceptee());
            // Une reelle sur soi passe le tour : on revient toujours au
            // meme participant en re-tirant sur soi de l'autre cote.
            if (moteur.phase() == PhasePartie.RECHARGEMENT) {
                assertTrue(moteur.terminerRechargement().acceptee());
            }
        }
        // Trouve le participant au dernier coeur et verifie l'interdit.
        for (Acteur acteur : Acteur.values()) {
            if (moteur.participant(acteur).vies() != 1 || moteur.tour() != acteur) continue;
            moteur.participant(acteur).ajouterObjet(Objet.CIGARETTES);
            ResultatAction refus = moteur.utiliser(acteur, Objet.CIGARETTES);
            assertFalse(refus.acceptee(),
                    "au round final (round 1 en duel) plus de cigarette au dernier coeur");
        }
    }

    @Test
    void leSoloGardeSesTroisRounds() {
        // Garde-fou de regression : le constructeur historique doit toujours
        // valoir roundFinal = 3.
        MoteurPartie moteur = new MoteurPartie(Regles.standard(), new Random(1),
                i -> Chargeur.depuis(List.of(REELLE, REELLE, REELLE)));
        assertTrue(moteur.demarrer().acceptee());
        assertTrue(moteur.terminerRechargement().acceptee());
        // Trois reelles sur le dealer (3 vies au round 1) : joueur -> dealer,
        // dealer -> soi (le tour repasse au joueur), joueur -> dealer.
        assertTrue(moteur.tirer(Acteur.JOUEUR, Cible.ADVERSAIRE).acceptee());
        assertTrue(moteur.reveler().acceptee());
        assertTrue(moteur.tirer(Acteur.DEALER, Cible.SOI).acceptee());
        assertTrue(moteur.reveler().acceptee());
        assertTrue(moteur.tirer(Acteur.JOUEUR, Cible.ADVERSAIRE).acceptee());
        ResultatAction fin = moteur.reveler();
        assertTrue(fin.acceptee());
        // Vies du round 1 : 3. Le dealer vient de tomber : round 2 s'ouvre,
        // la partie continue.
        assertTrue(fin.evenements().stream()
                .anyMatch(e -> e instanceof EvenementPartie.RoundCommence r && r.round() == 2));
        assertEquals(PhasePartie.RECHARGEMENT, moteur.phase());
    }
}
