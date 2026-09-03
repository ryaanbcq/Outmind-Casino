package net.thundranode.buckshot.paper;

import net.thundranode.buckshot.jeu.Acteur;
import net.thundranode.buckshot.jeu.MoteurPartie;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Session d'un duel joueur contre joueur : le pendant de {@link SessionPartie}
 * avec deux humains. Le premier joueur (le provocateur) occupe le role
 * {@link Acteur#JOUEUR}, le second le role {@link Acteur#DEALER} — le moteur
 * ne connait que ces deux roles, la table ne change pas.
 */
final class SessionDuel {

    private final UUID id = UUID.randomUUID();
    private final UUID joueur1;
    private final UUID joueur2;
    private final MoteurPartie moteur;
    private final Set<BukkitTask> taches = new HashSet<>();
    private boolean verrouille = true;
    private boolean annulee;
    /** Vrai des que le pot est paye : plus aucun forfait ne doit repayer. */
    private boolean reglee;
    private Acteur pompe;

    SessionDuel(UUID joueur1, UUID joueur2, MoteurPartie moteur) {
        this.joueur1 = joueur1;
        this.joueur2 = joueur2;
        this.moteur = moteur;
    }

    UUID id() { return id; }
    MoteurPartie moteur() { return moteur; }
    boolean verrouille() { return verrouille; }
    void verrouiller(boolean valeur) { verrouille = valeur; }
    boolean annulee() { return annulee; }
    boolean reglee() { return reglee; }
    void regler() { reglee = true; }

    UUID joueurId(Acteur acteur) {
        return acteur == Acteur.JOUEUR ? joueur1 : joueur2;
    }

    /** Role de ce joueur dans le duel, ou null s'il n'y participe pas. */
    Acteur acteurDe(UUID joueurId) {
        if (joueur1.equals(joueurId)) return Acteur.JOUEUR;
        if (joueur2.equals(joueurId)) return Acteur.DEALER;
        return null;
    }

    boolean participe(UUID joueurId) {
        return joueur1.equals(joueurId) || joueur2.equals(joueurId);
    }

    List<UUID> joueurs() {
        return List.of(joueur1, joueur2);
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
