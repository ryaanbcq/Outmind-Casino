package net.thundranode.buckshot.paper;

import net.thundranode.buckshot.jeu.Acteur;
import net.thundranode.buckshot.jeu.MoteurPartie;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

final class SessionPartie {

    private final UUID id = UUID.randomUUID();
    private final UUID joueurId;
    private final MoteurPartie moteur;
    private final Set<BukkitTask> taches = new HashSet<>();
    private boolean verrouille = true;
    private boolean annulee;
    private Acteur pompe;

    SessionPartie(UUID joueurId, MoteurPartie moteur) {
        this.joueurId = joueurId;
        this.moteur = moteur;
    }

    UUID id() { return id; }
    UUID joueurId() { return joueurId; }
    MoteurPartie moteur() { return moteur; }
    boolean verrouille() { return verrouille; }
    void verrouiller(boolean valeur) { verrouille = valeur; }
    boolean annulee() { return annulee; }

    /** Demande une pompe avant que la suite du tour reprenne. */
    void demanderPompe(Acteur acteur) { pompe = acteur; }

    /** Rend l'acteur qui doit pomper, ou null, et remet le drapeau a zero. */
    Acteur consommerPompe() {
        Acteur attendu = pompe;
        pompe = null;
        return attendu;
    }

    void suivre(BukkitTask tache) {
        taches.add(tache);
    }

    void annuler() {
        annulee = true;
        for (BukkitTask tache : taches) {
            tache.cancel();
        }
        taches.clear();
    }
}
