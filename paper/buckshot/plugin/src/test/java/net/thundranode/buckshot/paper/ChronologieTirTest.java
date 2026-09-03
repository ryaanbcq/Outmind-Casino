package net.thundranode.buckshot.paper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChronologieTirTest {

    @Test
    void balleReelleAttendLeTirPuisBlackout() {
        ChronologieTir chronologie = ChronologieTir.creer(8, true, 40);

        assertEquals(8, chronologie.viseeTicks());
        assertEquals(10, chronologie.attenteAvantResolutionTicks());
        assertEquals(40, chronologie.blackoutTicks());
        assertEquals(9, ChronologieTir.animationApresClicAnnule(8));
    }

    @Test
    void balleBlancheNDeclencheJamaisLeBlackout() {
        ChronologieTir chronologie = ChronologieTir.creer(8, false, 40);

        assertEquals(10, chronologie.attenteAvantResolutionTicks());
        assertEquals(0, chronologie.blackoutTicks());
    }
}
