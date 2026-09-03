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
    /**
     * Vrai des que le sort de la mise est tranche : round perdu (mise
     * perdue) ou gain paye. Plus aucun remboursement ne doit passer ensuite,
     * sinon une mort volontaire pendant la cinematique ou la fenetre de fin
     * rendrait la mise PAR-DESSUS le gain deja verse.
     */
    private boolean reglee;
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
    boolean reglee() { return reglee; }
    void regler() { reglee = true; }

    /** Remboursement effectif : jamais une fois la partie reglee. */
    boolean rembourserAutorise(boolean demande) {
        return demande && !reglee;
    }

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
