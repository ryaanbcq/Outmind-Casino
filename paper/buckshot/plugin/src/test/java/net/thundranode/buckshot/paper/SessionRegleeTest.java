package net.thundranode.buckshot.paper;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Invariant argent : une session REGLEE (round perdu, gain paye, pot de duel
 * verse) ne rembourse plus jamais, quelle que soit la raison de l'annulation
 * (mort, erreur, arret serveur).
 */
class SessionRegleeTest {

    @Test
    void soloRembourseTantQueNonReglee() {
        SessionPartie session = new SessionPartie(UUID.randomUUID(), null);
        assertFalse(session.reglee());
        assertTrue(session.rembourserAutorise(true));
        assertFalse(session.rembourserAutorise(false));
    }

    @Test
    void soloNeRembourseJamaisUneFoisReglee() {
        SessionPartie session = new SessionPartie(UUID.randomUUID(), null);
        session.regler();
        assertTrue(session.reglee());
        assertFalse(session.rembourserAutorise(true));
        assertFalse(session.rembourserAutorise(false));
    }

    @Test
    void duelNeRembourseJamaisUneFoisLePotPaye() {
        SessionDuel session = new SessionDuel(UUID.randomUUID(), UUID.randomUUID(), null);
        assertTrue(session.rembourserAutorise(true));
        session.regler();
        assertFalse(session.rembourserAutorise(true));
    }
}
