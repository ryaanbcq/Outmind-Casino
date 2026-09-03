package net.thundranode.buckshot;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SequenceAnimationTest {

    @Test
    void uneViseeEnvoieToutesLesPosesAuClient() {
        SequenceAnimation sequence = SequenceAnimation.creer(
                "aim_front", new Etats.Etat(8, true, 1, 32));

        assertEquals(List.of("aim_front_0", "aim_front_1", "aim_front_2", "aim_front_3",
                "aim_front_4", "aim_front_5", "aim_front_6", "aim_front_7"), sequence.images());
        assertEquals(8, sequence.dureeTicks());
    }

    @Test
    void inspectionUtiliseSaDernierePose() {
        SequenceAnimation sequence = SequenceAnimation.creer(
                "inspect", new Etats.Etat(8, true, 1, 0));

        assertEquals("inspect_0", sequence.images().getFirst());
        assertEquals("inspect_7", sequence.images().getLast());
    }
}
