package net.thundranode.buckshot.paper;

import net.thundranode.buckshot.jeu.Cible;
import net.thundranode.buckshot.jeu.Objet;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import io.papermc.paper.event.player.PlayerStopUsingItemEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Entrees des deux joueurs d'un duel. Frere d'{@link EcouteurPartie}, sans le
 * rituel de prise du fusil ni la mise au chat (la mise vient de la commande) :
 * chaque ecouteur ne reagit qu'a SES joueurs via estEnPartie, les deux peuvent
 * cohabiter sur la meme table sans se marcher dessus.
 */
public final class EcouteurDuel implements Listener {

    private final ControleurDuel controleur;

    /** Meme seuil de mise en joue que le solo : sous 7 ticks, clic involontaire. */
    private static final int TICKS_MIN_VISEE = 7;

    public EcouteurDuel(ControleurDuel controleur) {
        this.controleur = controleur;
    }

    @EventHandler
    public void clic(PlayerInteractEvent evenement) {
        if (evenement.getHand() != EquipmentSlot.HAND
                || !controleur.estEnPartie(evenement.getPlayer().getUniqueId())) return;
        ItemStack item = evenement.getItem();
        boolean clicDroit = evenement.getAction() == Action.RIGHT_CLICK_AIR
                || evenement.getAction() == Action.RIGHT_CLICK_BLOCK;
        if (clicDroit && item != null && item.getType() == org.bukkit.Material.CROSSBOW) {
            evenement.setCancelled(true);
            return;
        }
        if (evenement.getAction() != Action.PHYSICAL
                && declencher(evenement.getPlayer(), item, clicDroit)) {
            evenement.setCancelled(true);
        }
    }

    @EventHandler
    public void clicEntite(PlayerInteractEntityEvent evenement) {
        if (evenement.getHand() != EquipmentSlot.HAND
                || !controleur.estEnPartie(evenement.getPlayer().getUniqueId())) return;
        if (declencher(evenement.getPlayer(),
                evenement.getPlayer().getInventory().getItemInMainHand(), true)) {
            evenement.setCancelled(true);
        }
    }

    @EventHandler
    public void frappeEntite(EntityDamageByEntityEvent evenement) {
        if (!(evenement.getDamager() instanceof org.bukkit.entity.Player joueur)
                || !controleur.estEnPartie(joueur.getUniqueId())) return;
        if (declencher(joueur, joueur.getInventory().getItemInMainHand(), false)) {
            evenement.setCancelled(true);
        }
    }

    private boolean declencher(org.bukkit.entity.Player joueur, ItemStack item, boolean usageObjet) {
        String type = controleur.inventaire().type(item);
        if (type != null && type.startsWith("shot:")) {
            // Meme adaptation Bedrock que le solo : la montee en joue pilotee
            // par use_duration ne traverse pas Geyser, le clic tire direct.
            if (EcouteurPartie.estBedrock(joueur)) {
                if (controleur.tirBedrockAutorise(joueur.getUniqueId())) {
                    controleur.tirer(joueur,
                            "shot:self".equals(type) ? Cible.SOI : Cible.ADVERSAIRE);
                }
                return true;
            }
            return false;
        }
        if (usageObjet && type != null && type.startsWith("item:")) {
            Objet objet = controleur.inventaire().objet(item);
            if (objet != null) controleur.utiliser(joueur, objet);
            return true;
        }
        return false;
    }

    /** Tir tactile Bedrock : un swing avec l'item de tir en main vaut un tir. */
    @EventHandler
    public void balancerBras(org.bukkit.event.player.PlayerAnimationEvent evenement) {
        org.bukkit.entity.Player joueur = evenement.getPlayer();
        if (!controleur.estEnPartie(joueur.getUniqueId())
                || !EcouteurPartie.estBedrock(joueur)) return;
        String type = controleur.inventaire().type(
                joueur.getInventory().getItemInMainHand());
        if (type == null || !type.startsWith("shot:")) return;
        if (!controleur.tirBedrockAutorise(joueur.getUniqueId())) return;
        controleur.tirer(joueur,
                "shot:self".equals(type) ? Cible.SOI : Cible.ADVERSAIRE);
    }

    @EventHandler
    public void relacher(PlayerStopUsingItemEvent evenement) {
        if (!controleur.estEnPartie(evenement.getPlayer().getUniqueId())) return;
        String type = controleur.inventaire().type(evenement.getItem());
        if (type == null || !type.startsWith("shot:")) return;
        if (evenement.getTicksHeldFor() < TICKS_MIN_VISEE) return;
        controleur.tirer(evenement.getPlayer(),
                "shot:self".equals(type) ? Cible.SOI : Cible.ADVERSAIRE);
    }

    @EventHandler
    public void inventaire(InventoryClickEvent evenement) {
        if (controleur.estEnPartie(evenement.getWhoClicked().getUniqueId())) {
            evenement.setCancelled(true);
        }
    }

    @EventHandler
    public void glisser(InventoryDragEvent evenement) {
        if (controleur.estEnPartie(evenement.getWhoClicked().getUniqueId())) {
            evenement.setCancelled(true);
        }
    }

    @EventHandler
    public void jeter(PlayerDropItemEvent evenement) {
        if (controleur.estEnPartie(evenement.getPlayer().getUniqueId())) {
            evenement.setCancelled(true);
        }
    }

    @EventHandler
    public void echanger(PlayerSwapHandItemsEvent evenement) {
        if (controleur.estEnPartie(evenement.getPlayer().getUniqueId())) {
            evenement.setCancelled(true);
        }
    }

    @EventHandler
    public void changerSlot(PlayerItemHeldEvent evenement) {
        if (controleur.estVerrouille(evenement.getPlayer().getUniqueId())) {
            evenement.setCancelled(true);
        }
    }

    @EventHandler
    public void ramasser(EntityPickupItemEvent evenement) {
        if (evenement.getEntity() instanceof org.bukkit.entity.Player joueur
                && controleur.estEnPartie(joueur.getUniqueId())) {
            evenement.setCancelled(true);
        }
    }

    @EventHandler
    public void regenerer(EntityRegainHealthEvent evenement) {
        if (evenement.getEntity() instanceof org.bukkit.entity.Player joueur
                && controleur.estEnPartie(joueur.getUniqueId())) {
            evenement.setCancelled(true);
        }
    }

    @EventHandler
    public void faim(FoodLevelChangeEvent evenement) {
        if (controleur.estEnPartie(evenement.getEntity().getUniqueId())) {
            evenement.setCancelled(true);
        }
    }

    /** La mort de fin de duel est un vrai setHealth(0) : diriger le respawn. */
    @EventHandler
    public void dirigerRespawn(org.bukkit.event.player.PlayerRespawnEvent evenement) {
        var destination = controleur.consommerRespawnDirige(
                evenement.getPlayer().getUniqueId());
        if (destination != null) evenement.setRespawnLocation(destination);
    }

    /**
     * Mort vanilla en plein duel (hors du kill de fin, qui annule d'abord) :
     * forfait, l'adversaire prend le pot.
     */
    @EventHandler
    public void mort(PlayerDeathEvent evenement) {
        if (controleur.estEnPartie(evenement.getEntity().getUniqueId())) {
            evenement.getDrops().removeIf(controleur.inventaire()::estItemDePartie);
            controleur.forfait(evenement.getEntity().getUniqueId(),
                    evenement.getEntity().getName() + " died mid-duel.");
        }
    }

    @EventHandler
    public void quitter(PlayerQuitEvent evenement) {
        controleur.challengerParti(evenement.getPlayer());
        controleur.forfait(evenement.getPlayer().getUniqueId(),
                evenement.getPlayer().getName() + " left the duel.");
    }

    @EventHandler
    public void expulse(PlayerKickEvent evenement) {
        controleur.challengerParti(evenement.getPlayer());
        controleur.forfait(evenement.getPlayer().getUniqueId(),
                evenement.getPlayer().getName() + " left the duel.");
    }

    @EventHandler
    public void rejoindre(PlayerJoinEvent evenement) {
        if (controleur.inventaire().aRecuperer(evenement.getPlayer().getUniqueId())) {
            controleur.inventaire().restaurer(evenement.getPlayer());
        }
    }
}
