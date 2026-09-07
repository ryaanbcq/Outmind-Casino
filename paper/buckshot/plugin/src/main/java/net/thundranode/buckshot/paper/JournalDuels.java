package net.thundranode.buckshot.paper;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Journal des mises de duel par joueur et par jour (heure de Paris) : porte
 * le plafond journalier {@code duel.plafond-journalier}. Un seul fichier
 * ({@code duels-journal.yml}) partage par toutes les tables, relu a l'enable,
 * reecrit atomiquement (fichier temporaire puis renommage) a chaque duel
 * demarre. Ne compte que les duels DEMARRES, provocateur et accepteur
 * confondus : un defi rembourse ne laisse aucune trace.
 */
public final class JournalDuels {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    private final File fichier;
    private final Logger journal;
    /** Date du jour, injectable pour tester la bascule de minuit. */
    private final Supplier<LocalDate> aujourdhui;
    /** Jour (yyyy-MM-dd) auquel se rapportent les sommes en memoire. */
    private String jour = "";
    private final Map<UUID, Long> mises = new HashMap<>();

    public JournalDuels(File fichier, Logger journal) {
        this(fichier, journal, () -> LocalDate.now(PARIS));
    }

    JournalDuels(File fichier, Logger journal, Supplier<LocalDate> aujourdhui) {
        this.fichier = fichier;
        this.journal = journal;
        this.aujourdhui = aujourdhui;
    }

    /** Relit le fichier ; un journal d'un autre jour est ignore (les sommes repartent de zero). */
    public void charger() {
        mises.clear();
        jour = aujourdhui.get().toString();
        if (!fichier.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(fichier);
        if (!jour.equals(yaml.getString("jour", ""))) return;
        ConfigurationSection section = yaml.getConfigurationSection("mises");
        if (section == null) return;
        for (String cle : section.getKeys(false)) {
            try {
                mises.put(UUID.fromString(cle), Math.max(0L, section.getLong(cle)));
            } catch (IllegalArgumentException erreur) {
                journal.warning("[Buckshot] duels-journal.yml : cle ignoree " + cle);
            }
        }
    }

    /** Somme des mises de duel deja engagees aujourd'hui par ce joueur. */
    public long miseDuJour(UUID joueurId) {
        basculerJour();
        return mises.getOrDefault(joueurId, 0L);
    }

    /** Un duel demarre : la mise s'ajoute au jour courant et le fichier est reecrit. */
    public void enregistrer(UUID joueurId, long mise) {
        if (mise <= 0) return;
        basculerJour();
        mises.merge(joueurId, mise, Long::sum);
        sauver();
    }

    /** Minuit Paris : les sommes de la veille tombent. */
    private void basculerJour() {
        String courant = aujourdhui.get().toString();
        if (!courant.equals(jour)) {
            jour = courant;
            mises.clear();
        }
    }

    private void sauver() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("jour", jour);
        for (Map.Entry<UUID, Long> entree : mises.entrySet()) {
            yaml.set("mises." + entree.getKey(), entree.getValue());
        }
        try {
            File dossier = fichier.getAbsoluteFile().getParentFile();
            if (dossier != null && !dossier.exists() && !dossier.mkdirs()) {
                throw new IOException("creation du dossier " + dossier + " impossible");
            }
            Path cible = fichier.getAbsoluteFile().toPath();
            Path temporaire = cible.resolveSibling(fichier.getName() + ".tmp");
            Files.writeString(temporaire, yaml.saveToString(), StandardCharsets.UTF_8);
            Files.move(temporaire, cible, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException erreur) {
            // Le plafond continue de vivre en memoire : rater l'ecriture ne
            // doit ni bloquer les duels ni faire sauter le compteur du jour.
            journal.log(Level.SEVERE, "[Buckshot] ecriture de " + fichier.getName()
                    + " impossible", erreur);
        }
    }
}
