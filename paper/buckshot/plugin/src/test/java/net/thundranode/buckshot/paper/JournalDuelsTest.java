package net.thundranode.buckshot.paper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plafond journalier des duels : les mises s'additionnent par joueur et par
 * jour, survivent a un redemarrage (fichier YAML) et tombent a minuit Paris.
 */
class JournalDuelsTest {

    private static final Logger LOG = Logger.getLogger("JournalDuelsTest");

    @Test
    void additionneLesMisesDuJourEtLesRelitAuRedemarrage(@TempDir Path dossier) {
        File fichier = dossier.resolve("duels-journal.yml").toFile();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        LocalDate jour = LocalDate.of(2026, 9, 7);

        JournalDuels journal = new JournalDuels(fichier, LOG, () -> jour);
        journal.charger();
        assertEquals(0, journal.miseDuJour(alice));
        journal.enregistrer(alice, 5_000_000L);
        journal.enregistrer(bob, 5_000_000L);
        journal.enregistrer(alice, 2_000_000L);
        journal.enregistrer(alice, 0L);
        assertEquals(7_000_000L, journal.miseDuJour(alice));
        assertEquals(5_000_000L, journal.miseDuJour(bob));
        assertTrue(fichier.exists(), "le journal doit etre ecrit sur disque");
        assertFalse(dossier.resolve("duels-journal.yml.tmp").toFile().exists(),
                "le fichier temporaire est renomme, jamais laisse derriere");

        // Redemarrage le meme jour : les sommes reviennent.
        JournalDuels relu = new JournalDuels(fichier, LOG, () -> jour);
        relu.charger();
        assertEquals(7_000_000L, relu.miseDuJour(alice));
        assertEquals(5_000_000L, relu.miseDuJour(bob));
        assertEquals(0, relu.miseDuJour(UUID.randomUUID()));
    }

    @Test
    void lesSommesTombentAuChangementDeJour(@TempDir Path dossier) {
        File fichier = dossier.resolve("duels-journal.yml").toFile();
        UUID alice = UUID.randomUUID();
        LocalDate[] jour = {LocalDate.of(2026, 9, 7)};

        JournalDuels journal = new JournalDuels(fichier, LOG, () -> jour[0]);
        journal.charger();
        journal.enregistrer(alice, 90_000_000L);
        assertEquals(90_000_000L, journal.miseDuJour(alice));

        // Minuit : le compteur en memoire repart de zero.
        jour[0] = jour[0].plusDays(1);
        assertEquals(0, journal.miseDuJour(alice));
        journal.enregistrer(alice, 1_000_000L);
        assertEquals(1_000_000L, journal.miseDuJour(alice));

        // Et un journal de la veille n'est pas relu le lendemain.
        JournalDuels lendemain = new JournalDuels(fichier, LOG, () -> LocalDate.of(2026, 9, 9));
        lendemain.charger();
        assertEquals(0, lendemain.miseDuJour(alice));
    }

    @Test
    void unFichierAbsentOuIllisibleNeBloqueRien(@TempDir Path dossier) throws Exception {
        File fichier = dossier.resolve("sous/dossier/duels-journal.yml").toFile();
        LocalDate jour = LocalDate.of(2026, 9, 7);
        JournalDuels journal = new JournalDuels(fichier, LOG, () -> jour);
        journal.charger();
        assertEquals(0, journal.miseDuJour(UUID.randomUUID()));
        // Le dossier manquant est cree a la premiere ecriture.
        journal.enregistrer(UUID.randomUUID(), 1L);
        assertTrue(fichier.exists());

        // Une cle qui n'est pas un UUID est ignoree, le reste est lu.
        UUID alice = UUID.randomUUID();
        Files.writeString(fichier.toPath(), "jour: '2026-09-07'\nmises:\n  pas-un-uuid: 5\n  "
                + alice + ": 42\n");
        JournalDuels relu = new JournalDuels(fichier, LOG, () -> jour);
        relu.charger();
        assertEquals(42L, relu.miseDuJour(alice));
    }
}
