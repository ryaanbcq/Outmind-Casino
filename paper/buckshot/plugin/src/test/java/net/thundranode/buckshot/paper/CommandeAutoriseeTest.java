package net.thundranode.buckshot.paper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Liste blanche des commandes d'un joueur assis : /buckshot (alias /rr) et /leave, rien d'autre. */
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
    void buckshotLaCommandePrincipaleEstAutorisee() {
        // La commande a ete renommee buckshot (rr n'est plus qu'un alias) :
        // les boutons GIVE UP / CONTINUE et le JOIN du duel passent par elle.
        assertTrue(EcouteurPartie.commandeAutorisee("/buckshot abandonner"));
        assertTrue(EcouteurPartie.commandeAutorisee("/buckshot continuer"));
        assertTrue(EcouteurPartie.commandeAutorisee("/Buckshot duel accepter Bob 500000"));
        assertTrue(EcouteurPartie.commandeAutorisee("/buckshot:buckshot abandonner"));
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
