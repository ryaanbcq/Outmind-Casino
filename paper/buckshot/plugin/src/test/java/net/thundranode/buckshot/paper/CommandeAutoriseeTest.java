package net.thundranode.buckshot.paper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Liste blanche des commandes d'un joueur assis : /rr et /leave, rien d'autre. */
class CommandeAutoriseeTest {

    @Test
    void rrEtLeaveSontAutorisees() {
        assertTrue(EcouteurPartie.commandeAutorisee("/rr abandonner"));
        assertTrue(EcouteurPartie.commandeAutorisee("/RR duel accepter Bob"));
        assertTrue(EcouteurPartie.commandeAutorisee("/leave"));
        assertTrue(EcouteurPartie.commandeAutorisee("/Leave "));
        assertTrue(EcouteurPartie.commandeAutorisee("/buckshot:rr jouer"));
    }

    @Test
    void lesSortiesDeTableSontBloquees() {
        assertFalse(EcouteurPartie.commandeAutorisee("/spawn"));
        assertFalse(EcouteurPartie.commandeAutorisee("/warp casino"));
        assertFalse(EcouteurPartie.commandeAutorisee("/rrx"));
        assertFalse(EcouteurPartie.commandeAutorisee("/leaves"));
        assertFalse(EcouteurPartie.commandeAutorisee("/essentials:spawn"));
    }
}
