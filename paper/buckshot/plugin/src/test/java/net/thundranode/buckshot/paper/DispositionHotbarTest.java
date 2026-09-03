package net.thundranode.buckshot.paper;

import net.thundranode.buckshot.jeu.Objet;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DispositionHotbarTest {

    @Test
    void deuxActionsPuisHuitObjetsEmpilesParType() {
        Objet cigarettes = Objet.valueOf("CIGARETTES");
        DispositionHotbar plan = DispositionHotbar.creer(List.of(
                cigarettes, cigarettes, Objet.LOUPE, Objet.LOUPE,
                Objet.MENOTTES, Objet.COUTEAU, Objet.BIERE, Objet.BIERE), true);

        assertEquals(DispositionHotbar.Type.TIR_DRDONUTT, plan.entrees().get(0).type());
        assertEquals(0, plan.entrees().get(0).slot());
        assertEquals(DispositionHotbar.Type.TIR_SOI, plan.entrees().get(1).type());
        assertEquals(1, plan.entrees().get(1).slot());
        assertEquals(8, plan.entrees().stream()
                .filter(e -> e.type() == DispositionHotbar.Type.OBJET)
                .mapToInt(DispositionHotbar.Entree::quantite).sum());
        assertTrue(plan.entrees().stream().allMatch(e -> e.slot() <= 8));
    }

    @Test
    void tourDealerMasqueActionsEtObjets() {
        DispositionHotbar plan = DispositionHotbar.creer(List.of(Objet.LOUPE), false);

        assertEquals(1, plan.entrees().size());
        assertEquals(DispositionHotbar.Type.ATTENTE, plan.entrees().getFirst().type());
        assertEquals(4, plan.entrees().getFirst().slot());
    }
}
