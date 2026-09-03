package net.thundranode.buckshot.ia;

import net.thundranode.buckshot.jeu.Cible;
import net.thundranode.buckshot.jeu.Objet;
import net.thundranode.buckshot.jeu.TypeCartouche;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class StrategieDrDonuttTest {

    private final StrategieDrDonutt strategie = new StrategieDrDonutt();

    @Test
    void viseLeJoueurSiLaChambreEstReelle() {
        ActionIA action = strategie.choisir(vue(List.of(), 3, Optional.of(TypeCartouche.REELLE)), new Random(1));
        assertEquals(new ActionIA.Tirer(Cible.ADVERSAIRE), action);
    }

    @Test
    void seViseSiLaChambreEstBlanche() {
        ActionIA action = strategie.choisir(vue(List.of(), 3, Optional.of(TypeCartouche.BLANCHE)), new Random(1));
        assertEquals(new ActionIA.Tirer(Cible.SOI), action);
    }

    @Test
    void soigneAvantDePrendreUnRisque() {
        ActionIA action = strategie.choisir(vue(List.of(Objet.CIGARETTES), 1, Optional.empty()), new Random(1));
        assertEquals(new ActionIA.UtiliserObjet(Objet.CIGARETTES), action);
    }

    @Test
    void neFumePasAuDernierCoeurDuRoundFinal() {
        // La cigarette est interdite a une vie AU ROUND FINAL (regle user
        // 2026-08-27) : la strategie ne doit meme pas l'essayer, un refus
        // laisse le tour vide. Aux rounds 1-2 (vue() = round 2) elle fume.
        ActionIA action = strategie.choisir(
                vueAuRound(3, List.of(Objet.CIGARETTES), 1, Optional.empty()), new Random(1));
        assertNotEquals(new ActionIA.UtiliserObjet(Objet.CIGARETTES), action);
    }

    @Test
    void utiliseSaLoupeSansInformation() {
        ActionIA action = strategie.choisir(vue(List.of(Objet.LOUPE), 3, Optional.empty()), new Random(1));
        assertEquals(new ActionIA.UtiliserObjet(Objet.LOUPE), action);
    }

    @Test
    void raisonneSurLaCompositionPublique() {
        VueIA beaucoupDeBlanches = new VueIA(2, 1, 4, 3, 3, List.of(), 0, 0, true, false, Optional.empty());
        assertEquals(new ActionIA.Tirer(Cible.SOI), strategie.choisir(beaucoupDeBlanches, new Random(1)));
    }

    @Test
    void tireSurSoiUneBlancheConnueAuLieuDeBoire() {
        // Boire une blanche connue gaspillait la biere : le tir sur soi
        // garde la main pareil, sans consommer l'objet.
        ActionIA action = strategie.choisir(
                vue(List.of(Objet.BIERE), 3, Optional.of(TypeCartouche.BLANCHE)), new Random(1));
        assertEquals(new ActionIA.Tirer(Cible.SOI), action);
    }

    @Test
    void nUsePasSaLoupeSurUneChambreDeduite() {
        // Plus que des blanches : la composition publique donne la chambre,
        // la loupe serait gaspillee. Le tir sur soi gratuit est le bon coup.
        VueIA queDesBlanches = new VueIA(2, 0, 3, 3, 3,
                List.of(Objet.LOUPE), 0, 0, true, false, Optional.empty());
        assertEquals(new ActionIA.Tirer(Cible.SOI),
                strategie.choisir(queDesBlanches, new Random(1)));

        VueIA queDesReelles = new VueIA(2, 3, 0, 3, 3,
                List.of(Objet.LOUPE), 0, 0, true, false, Optional.empty());
        assertEquals(new ActionIA.Tirer(Cible.ADVERSAIRE),
                strategie.choisir(queDesReelles, new Random(1)));
    }

    private static VueIA vue(List<Objet> objets, int vies, Optional<TypeCartouche> connue) {
        return vueAuRound(2, objets, vies, connue);
    }

    private static VueIA vueAuRound(int round, List<Objet> objets, int vies,
                                    Optional<TypeCartouche> connue) {
        return new VueIA(round, 3, 3, vies, 3, objets, 0, 0, true, false, connue);
    }
}
