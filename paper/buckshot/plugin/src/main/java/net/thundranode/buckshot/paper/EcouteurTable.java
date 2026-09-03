package net.thundranode.buckshot.paper;

import net.citizensnpcs.api.event.NPCRightClickEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public final class EcouteurTable implements Listener {

    private final MiseEnScene scene;
    private final ControleurPartie controleur;

    public EcouteurTable(MiseEnScene scene, ControleurPartie controleur) {
        this.scene = scene;
        this.controleur = controleur;
    }

    @EventHandler
    public void cliquerDealer(NPCRightClickEvent evenement) {
        if (scene.dealer().est(evenement.getNPC())) {
            evenement.setCancelled(true);
            if (controleur.estEnPartie(evenement.getClicker().getUniqueId())) return;
            // Meme garde que /rr jouer : sans la permission, pas de partie.
            if (!evenement.getClicker().hasPermission("buckshot.play")) {
                evenement.getClicker().sendMessage(net.kyori.adventure.text.Component.text(
                        "You do not have permission.",
                        net.kyori.adventure.text.format.NamedTextColor.RED));
                return;
            }
            controleur.demarrer(evenement.getClicker());
        }
    }

    @EventHandler
    public void rejoindre(org.bukkit.event.player.PlayerJoinEvent evenement) {
        // Miroir Bedrock : cacher la doublure du fusil aux clients Java qui
        // arrivent (la table en blocs, elle, part du timer de la scene).
        scene.masquerMiroirPour(evenement.getPlayer());
    }
}
