package net.thundranode.buckshot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MisesTest {

    @Test
    void litLesSuffixesEtLesDecimales() {
        assertEquals(5_000_000L, Mises.parser("5M"));
        assertEquals(1_500_000L, Mises.parser("1,5m"));
        assertEquals(1_500_000L, Mises.parser("1.5M"));
        assertEquals(500_000L, Mises.parser("500k"));
        assertEquals(2_000_000_000L, Mises.parser("2b"));
        assertEquals(5_000_000L, Mises.parser("5000000"));
        assertEquals(40L, Mises.parser("40$"));
        assertEquals(2_500_000L, Mises.parser("$2.5m"));
    }

    @Test
    void laisseLePasserLeChatNormal() {
        assertEquals(-1, Mises.parser("bonjour"));
        assertEquals(-1, Mises.parser("gg"));
        assertEquals(-1, Mises.parser(""));
        assertEquals(-1, Mises.parser(null));
        assertEquals(-1, Mises.parser("0"));
        assertEquals(-1, Mises.parser("-5m"));
        // Une decimale nue n'est pas un montant en dollars.
        assertEquals(-1, Mises.parser("1.5"));
        assertEquals(-1, Mises.parser("5 million"));
    }

    @Test
    void formateEnSuffixesLisibles() {
        assertEquals("5M", Mises.formater(5_000_000L));
        assertEquals("1.5M", Mises.formater(1_500_000L));
        assertEquals("500K", Mises.formater(500_000L));
        assertEquals("2B", Mises.formater(2_000_000_000L));
        assertEquals("750", Mises.formater(750L));
        assertEquals("1234567", Mises.formater(1_234_567L));
    }
}
