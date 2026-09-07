package net.thundranode.buckshot.ia;

import net.thundranode.buckshot.jeu.Cible;
import net.thundranode.buckshot.jeu.Objet;
import net.thundranode.buckshot.jeu.TypeCartouche;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * La strategie est MIXTE : on teste des invariants (ce qu'elle ne fait
 * jamais) et des frequences (ce qu'elle ne fait pas toujours), sur des
 * milliers de tirages avec des humeurs redessinees.
 */
class StrategieDrDonuttTest {

    private static final int TIRAGES = 3000;

    private static Map<ActionIA, Integer> distribution(VueIA vue) {
        StrategieDrDonutt strategie = new StrategieDrDonutt();
        Random aleatoire = new Random(42);
        Map<ActionIA, Integer> compte = new HashMap<>();
        for (int i = 0; i < TIRAGES; i++) {
            if (i % 50 == 0) strategie.nouvelleManche(aleatoire);
            compte.merge(strategie.choisir(vue, aleatoire), 1, Integer::sum);
        }
        return compte;
    }

    private static final ActionIA TIR_JOUEUR = new ActionIA.Tirer(Cible.ADVERSAIRE);
    private static final ActionIA TIR_SOI = new ActionIA.Tirer(Cible.SOI);

    @Test
    void neSeTireJamaisDessusAvecUneReelleConnue() {
        Map<ActionIA, Integer> d = distribution(vue(List.of(Objet.BIERE, Objet.LOUPE, Objet.MENOTTES), 3,
                Optional.of(TypeCartouche.REELLE)));
        assertFalse(d.containsKey(TIR_SOI), d.toString());
        assertFalse(d.containsKey(new ActionIA.UtiliserObjet(Objet.LOUPE)), "loupe inutile : " + d);
        // Menotter puis tirer, ou tirer tout de suite : les deux portent la reelle sur le joueur.
        assertTrue(d.getOrDefault(TIR_JOUEUR, 0) + d.getOrDefault(new ActionIA.UtiliserObjet(Objet.MENOTTES), 0)
                > TIRAGES * 0.9, d.toString());
    }

    @Test
    void neTireJamaisSurLeJoueurAvecUneBlancheConnue() {
        Map<ActionIA, Integer> d = distribution(vue(List.of(Objet.BIERE, Objet.COUTEAU), 3,
                Optional.of(TypeCartouche.BLANCHE)));
        assertFalse(d.containsKey(TIR_JOUEUR), d.toString());
        assertFalse(d.containsKey(new ActionIA.UtiliserObjet(Objet.COUTEAU)), d.toString());
        assertFalse(d.containsKey(new ActionIA.UtiliserObjet(Objet.BIERE)), "biere gaspillee : " + d);
        assertEquals(TIRAGES, d.getOrDefault(TIR_SOI, 0));
    }

    @Test
    void neFumeJamaisQuandLeMoteurRefuserait() {
        // Round final a une vie : interdit. Vies au plafond : sans effet.
        Map<ActionIA, Integer> derniereVie = distribution(
                vueAuRound(3, List.of(Objet.CIGARETTES), 1, Optional.empty()));
        assertFalse(derniereVie.containsKey(new ActionIA.UtiliserObjet(Objet.CIGARETTES)), derniereVie.toString());
        Map<ActionIA, Integer> plafond = distribution(
                new VueIA(2, 3, 3, 5, 3, 5, List.of(Objet.CIGARETTES), 0, 0, true, false, Optional.empty()));
        assertFalse(plafond.containsKey(new ActionIA.UtiliserObjet(Objet.CIGARETTES)), plafond.toString());
    }

    @Test
    void fumePresqueToujoursQuandIlEstBlesse() {
        Map<ActionIA, Integer> d = distribution(vue(List.of(Objet.CIGARETTES), 1, Optional.empty()));
        assertTrue(d.getOrDefault(new ActionIA.UtiliserObjet(Objet.CIGARETTES), 0) > TIRAGES * 0.85, d.toString());
    }

    @Test
    void neMenotteJamaisUnJoueurNonMenottable() {
        Map<ActionIA, Integer> d = distribution(
                new VueIA(2, 3, 3, 3, 3, 5, List.of(Objet.MENOTTES), 0, 0, false, false, Optional.empty()));
        assertFalse(d.containsKey(new ActionIA.UtiliserObjet(Objet.MENOTTES)), d.toString());
    }

    @Test
    void nArmeJamaisLeCouteauDeuxFois() {
        Map<ActionIA, Integer> d = distribution(
                new VueIA(2, 3, 3, 3, 3, 5, List.of(Objet.COUTEAU), 0, 0, true, true, Optional.empty()));
        assertFalse(d.containsKey(new ActionIA.UtiliserObjet(Objet.COUTEAU)), d.toString());
    }

    @Test
    void nUsePasSaLoupeSurUneChambreDeduite() {
        Map<ActionIA, Integer> blanches = distribution(
                new VueIA(2, 0, 3, 3, 3, 5, List.of(Objet.LOUPE), 0, 0, true, false, Optional.empty()));
        assertEquals(TIRAGES, blanches.getOrDefault(TIR_SOI, 0), blanches.toString());
        Map<ActionIA, Integer> reelles = distribution(
                new VueIA(2, 3, 0, 3, 3, 5, List.of(Objet.LOUPE), 0, 0, true, false, Optional.empty()));
        assertEquals(TIRAGES, reelles.getOrDefault(TIR_JOUEUR, 0), reelles.toString());
    }

    @Test
    void aUneVieNeSeTireJamaisDessusSansBlancheConnue() {
        Map<ActionIA, Integer> d = distribution(
                new VueIA(2, 1, 4, 1, 3, 5, List.of(), 0, 0, true, false, Optional.empty()));
        assertFalse(d.containsKey(TIR_SOI), d.toString());
    }

    @Test
    void uneSeuleReelleRestanteIlNeSeTirePlusDessusParReflexe() {
        // L'ancien pattern (p < 0.5 => tir sur soi certain) faisait chercher
        // les blanches au dealer et lui faisait manger la seule reelle : le
        // joueur qui le savait tirait sur lui a chaque tour et gagnait a
        // l'esperance. La valeur exacte dit : tirer sur le joueur.
        Map<ActionIA, Integer> d = distribution(
                new VueIA(1, 1, 2, 3, 3, 5, List.of(), 0, 0, true, false, Optional.empty()));
        int soi = d.getOrDefault(TIR_SOI, 0);
        int joueur = d.getOrDefault(TIR_JOUEUR, 0);
        assertTrue(joueur > soi, d.toString());
        assertTrue(soi > 0, "aucune exploration : " + d);
    }

    @Test
    void surUnCoupSerreLesDeuxCiblesSePartagentLaMise() {
        // 1 reelle 3 blanches, 3 vies chacun : les deux tirs se valent
        // presque, le dealer doit rester illisible.
        Map<ActionIA, Integer> d = distribution(
                new VueIA(1, 1, 3, 3, 3, 5, List.of(), 0, 0, true, false, Optional.empty()));
        int soi = d.getOrDefault(TIR_SOI, 0);
        int joueur = d.getOrDefault(TIR_JOUEUR, 0);
        assertTrue(soi > TIRAGES * 0.2 && joueur > TIRAGES * 0.2, d.toString());
        // Et a 50/50, jamais une cible a coup sur.
        Map<ActionIA, Integer> moitie = distribution(
                new VueIA(1, 2, 2, 3, 3, 5, List.of(), 0, 0, true, false, Optional.empty()));
        assertTrue(moitie.getOrDefault(TIR_SOI, 0) > TIRAGES * 0.05
                && moitie.getOrDefault(TIR_JOUEUR, 0) > TIRAGES * 0.05, moitie.toString());
    }

    @Test
    void avecUneLoupeEtDeLIncertitudeIlNeLUtilisePasToujours() {
        Map<ActionIA, Integer> d = distribution(vue(List.of(Objet.LOUPE), 3, Optional.empty()));
        int loupe = d.getOrDefault(new ActionIA.UtiliserObjet(Objet.LOUPE), 0);
        assertTrue(loupe > TIRAGES * 0.4, "la loupe reste le coup naturel : " + d);
        assertTrue(loupe < TIRAGES * 0.97, "loupe systematique = pattern : " + d);
    }

    @Test
    void leTirDeRepliEstToujoursLegal() {
        StrategieDrDonutt strategie = new StrategieDrDonutt();
        assertEquals(TIR_JOUEUR, strategie.tirDeRepli(vue(List.of(), 1, Optional.empty())));
        assertEquals(TIR_SOI, strategie.tirDeRepli(vue(List.of(), 3, Optional.of(TypeCartouche.BLANCHE))));
        assertEquals(TIR_JOUEUR, strategie.tirDeRepli(vue(List.of(), 3, Optional.of(TypeCartouche.REELLE))));
        assertEquals(TIR_SOI, strategie.tirDeRepli(
                new VueIA(2, 1, 3, 3, 3, 5, List.of(), 0, 0, true, false, Optional.empty())));
    }

    @Test
    void lHumeurChangeLaLoiSansCasserLesInvariants() {
        // Deux humeurs extremes, meme etat : les frequences different, les
        // interdits tiennent.
        VueIA vue = new VueIA(2, 1, 2, 3, 3, 5, List.of(Objet.COUTEAU), 0, 0, true, false, Optional.of(TypeCartouche.REELLE));
        Map<ActionIA, Integer> total = new EnumMap<>(Cible.class).isEmpty() ? new HashMap<>() : new HashMap<>();
        StrategieDrDonutt strategie = new StrategieDrDonutt();
        Random aleatoire = new Random(7);
        for (int manche = 0; manche < 40; manche++) {
            strategie.nouvelleManche(aleatoire);
            for (int i = 0; i < 100; i++) total.merge(strategie.choisir(vue, aleatoire), 1, Integer::sum);
        }
        assertFalse(total.containsKey(TIR_SOI), total.toString());
        assertTrue(total.containsKey(TIR_JOUEUR) && total.containsKey(new ActionIA.UtiliserObjet(Objet.COUTEAU)), total.toString());
    }

    private static VueIA vue(List<Objet> objets, int vies, Optional<TypeCartouche> connue) {
        return vueAuRound(2, objets, vies, connue);
    }

    private static VueIA vueAuRound(int round, List<Objet> objets, int vies,
                                    Optional<TypeCartouche> connue) {
        return new VueIA(round, 3, 3, vies, 3, 5, objets, 0, 0, true, false, connue);
    }
}
