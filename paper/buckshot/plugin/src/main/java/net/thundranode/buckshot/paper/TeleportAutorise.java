package net.thundranode.buckshot.paper;

import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Teleportations declenchees par le plugin LUI-MEME sur un joueur assis a
 * une table (installation, restauration d'inventaire, cinematique Bedrock).
 *
 * <p>Les ecouteurs annulent toute teleportation d'un joueur en partie
 * (warp inter-mondes en plein tour = arithmetique de Location entre deux
 * mondes = exception = partie annulee) ; celles du plugin passent par
 * {@link #pendant}, qui pose le drapeau le temps de l'appel. L'evenement
 * de teleportation est emis de facon synchrone dans {@code teleport()}, le
 * drapeau est donc toujours leve au moment ou l'ecouteur le lit.
 */
final class TeleportAutorise {

    private static final Set<UUID> EN_COURS = new HashSet<>();

    private TeleportAutorise() { }

    static void pendant(Player joueur, Runnable action) {
        UUID id = joueur.getUniqueId();
        boolean pose = EN_COURS.add(id);
        try {
            action.run();
        } finally {
            if (pose) EN_COURS.remove(id);
        }
    }

    static boolean estAutorise(UUID joueurId) {
        return EN_COURS.contains(joueurId);
    }
}
