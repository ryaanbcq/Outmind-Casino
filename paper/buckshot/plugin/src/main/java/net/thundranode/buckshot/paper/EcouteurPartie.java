package net.thundranode.buckshot.paper;

import net.thundranode.buckshot.jeu.Cible;
import net.thundranode.buckshot.jeu.Objet;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.Location;
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

public final class EcouteurPartie implements Listener {

    private final ControleurPartie controleur;

    /**
     * Duree minimale de maintien avant qu'un tir parte, en ticks.
     *
     * <p>C'est le seuil ou le pack bascule de la montee en joue vers la boucle
     * de tremblement. En dessous, l'arme n'est pas encore epaulee : un clic
     * involontaire ne doit pas bruler une cartouche.
     */
    private static final int TICKS_MIN_VISEE = 7;

    public EcouteurPartie(ControleurPartie controleur) {
        this.controleur = controleur;
    }

    @EventHandler
    public void clic(PlayerInteractEvent evenement) {
        if (evenement.getHand() != EquipmentSlot.HAND
                || !controleur.estEnPartie(evenement.getPlayer().getUniqueId())) return;
        ItemStack item = evenement.getItem();
        boolean clicDroit = evenement.getAction() == Action.RIGHT_CLICK_AIR
                || evenement.getAction() == Action.RIGHT_CLICK_BLOCK;
        // La seule arbalete qu'un joueur en partie peut tenir est l'item
        // d'attente du menotte : chargee UNIQUEMENT pour la pose des bras,
        // jamais pour tirer une vraie fleche sur le serveur.
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
        // Question de relance : fusil = round suivant, mallette = encaisser.
        if (usageObjet && "relance:continuer".equals(type)) {
            controleur.reprendre(joueur);
            return true;
        }
        if (usageObjet && "relance:cashout".equals(type)) {
            controleur.abandonner(joueur);
            return true;
        }
        if (type != null && type.startsWith("shot:")) {
            // Joueur Bedrock (via Floodgate, comptes lies SANS point compris) :
            // le composant consumable qui pilote la montee en joue ne traverse
            // pas Geyser, le relachement n'arrive jamais et le tir etait
            // impossible (constat user 2026-08-29). Pour eux, n'importe quel
            // clic tire directement -- sur tactile le tap est un clic gauche.
            if (estBedrock(joueur)) {
                if (controleur.tirBedrockAutorise(joueur.getUniqueId())) {
                    controleur.tirer(joueur,
                            "shot:self".equals(type) ? Cible.SOI : Cible.ADVERSAIRE);
                }
                return true;
            }
            // Le tir part au relachement, pas au clic. Annuler l'evenement ici
            // tuerait l'usage avant qu'il commence, et sans usage il n'y a ni
            // montee en joue ni tremblement : les deux sont pilotes par le
            // client via use_duration et use_cycle.
            return false;
        }
        if (usageObjet && type != null && type.startsWith("item:")) {
            Objet objet = controleur.inventaire().objet(item);
            if (objet != null) controleur.utiliser(joueur, objet);
            return true;
        }
        return false;
    }

    /**
     * Detection Bedrock par l'API Floodgate, en reflexion pour ne pas
     * dependre du jar : le prefixe point ne suffit pas, un compte LIE garde
     * son pseudo Java sans point (cas de l'utilisateur lui-meme).
     */
    private static java.lang.reflect.Method estFloodgate;
    private static Object apiFloodgate;

    static boolean estBedrock(org.bukkit.entity.Player joueur) {
        try {
            if (estFloodgate == null) {
                Class<?> api = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
                apiFloodgate = api.getMethod("getInstance").invoke(null);
                estFloodgate = api.getMethod("isFloodgatePlayer", java.util.UUID.class);
            }
            return (boolean) estFloodgate.invoke(apiFloodgate, joueur.getUniqueId());
        } catch (ReflectiveOperationException | RuntimeException erreur) {
            return joueur.getName().startsWith(".");
        }
    }

    /**
     * Tir tactile : sur telephone Bedrock, taper dans le vide n'envoie AUCUN
     * clic droit, seulement un swing de bras. Un swing avec l'item de tir en
     * main vaut donc un tir pour un joueur Bedrock. tirer() est verrouille
     * cote controleur, un swing double d'un vrai clic ne tire pas deux fois.
     */
    @EventHandler
    public void balancerBras(org.bukkit.event.player.PlayerAnimationEvent evenement) {
        org.bukkit.entity.Player joueur = evenement.getPlayer();
        if (!controleur.estEnPartie(joueur.getUniqueId()) || !estBedrock(joueur)) return;
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

    /**
     * Mise tapee au chat pendant l'invitation : "5M", "500k", "2.5m"...
     * Un texte qui n'est pas un montant passe au chat normalement -- le
     * joueur peut parler. La validation et le debit se font sur le thread
     * serveur, dans placerMise.
     */
    @EventHandler(ignoreCancelled = true)
    public void miseAuChat(io.papermc.paper.event.player.AsyncChatEvent evenement) {
        org.bukkit.entity.Player joueur = evenement.getPlayer();
        if (!controleur.attendMise(joueur.getUniqueId())) return;
        String texte = net.kyori.adventure.text.serializer.plain
                .PlainTextComponentSerializer.plainText().serialize(evenement.message());
        long montant = net.thundranode.buckshot.Mises.parser(texte);
        if (montant <= 0) return;
        evenement.setCancelled(true);
        controleur.placerMise(joueur, montant);
    }

    /** Clic droit sur la zone du fusil pose : le ramasser. */
    @EventHandler
    public void ramasserFusil(org.bukkit.event.player.PlayerInteractEntityEvent evenement) {
        controleur.clicEntite(evenement.getPlayer(), evenement.getRightClicked());
    }

    /** Le clic gauche (attaque) sur la zone ramasse aussi : un clic est un clic. */
    @EventHandler
    public void frapperFusil(org.bukkit.event.entity.EntityDamageByEntityEvent evenement) {
        if (evenement.getDamager() instanceof org.bukkit.entity.Player joueur) {
            controleur.clicEntite(joueur, evenement.getEntity());
        }
    }

    /**
     * La mort de fin de partie est un vrai setHealth(0) : le respawn vanilla
     * renverrait le joueur au spawn de l'overworld principal, pas a celui du
     * monde de la table. On le dirige, une seule fois, vers le spawn pose
     * par le controleur a l'instant du kill.
     */
    @EventHandler
    public void dirigerRespawn(org.bukkit.event.player.PlayerRespawnEvent evenement) {
        var destination = controleur.consommerRespawnDirige(
                evenement.getPlayer().getUniqueId());
        if (destination != null) evenement.setRespawnLocation(destination);
    }

    /**
     * Sneaker en mode spectateur demonte la camera : pendant la cinematique
     * de mort, un joueur qui mourait accroupi se retrouvait en camera libre
     * des le premier tick. On refuse le detachement tant que la cinematique
     * tient la camera ; elle se deverrouille avant de la rendre.
     */
    @EventHandler
    public void garderCameraCinematique(
            com.destroystokyo.paper.event.player.PlayerStopSpectatingEntityEvent evenement) {
        if (controleur.estEnCinematique(evenement.getPlayer().getUniqueId())) {
            evenement.setCancelled(true);
        }
    }

    @EventHandler
    public void changerSlot(PlayerItemHeldEvent evenement) {
        if (controleur.estVerrouille(evenement.getPlayer().getUniqueId())) {
            evenement.setCancelled(true);
            return;
        }
        controleur.choixRelanceEnMain(evenement.getPlayer(), evenement.getNewSlot());
    }

    // Plus de gel de position (demande user 2026-08-27) : la cage de
    // barrieres posee par la scene retient le joueur a la table, il bouge
    // librement dans son enclos d'un bloc.

    @EventHandler
    public void ramasser(EntityPickupItemEvent evenement) {
        if (evenement.getEntity() instanceof org.bukkit.entity.Player joueur
                && controleur.estEnPartie(joueur.getUniqueId())) {
            evenement.setCancelled(true);
        }
    }

    // Pendant une partie la barre de vie appartient au jeu : ni la regeneration
    // naturelle ni la faim n'ont le droit d'y toucher.
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

    /**
     * Mort d'un joueur assis, ou mort de fin de partie infligee par le
     * controleur : l'inventaire (deja rendu, ou rendu ici par annuler) est
     * garde et rien ne tombe au sol -- l'evenement de mort vide l'inventaire
     * APRES les ecouteurs, et tous les mondes n'ont pas keepInventory.
     */
    @EventHandler
    public void mort(PlayerDeathEvent evenement) {
        java.util.UUID id = evenement.getEntity().getUniqueId();
        if (!controleur.estEnPartie(id) && !controleur.mortProgrammee(id)) return;
        evenement.setKeepInventory(true);
        evenement.setKeepLevel(true);
        evenement.getDrops().clear();
        evenement.setDroppedExp(0);
        if (controleur.estEnPartie(id)) {
            // Forfait, jamais un remboursement : avant le verdict la mise
            // est perdue comme en duel, apres le verdict elle est deja
            // reglee (gain paye ou mise perdue) et annuler la retient.
            controleur.annuler(id, "Game forfeited on your death.", false);
        }
    }

    @EventHandler
    public void quitter(PlayerQuitEvent evenement) {
        controleur.annuler(evenement.getPlayer().getUniqueId(), null);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void expulse(PlayerKickEvent evenement) {
        controleur.annuler(evenement.getPlayer().getUniqueId(), null);
    }

    /**
     * Aucune teleportation exterieure d'un joueur assis : un warp vers un
     * autre monde en plein tour faisait planter l'arithmetique de Location
     * de la scene et annulait la partie -- avec remboursement, donc une
     * sortie gratuite pour un joueur en train de perdre. Les teleports du
     * plugin passent par {@link TeleportAutorise} ; ceux du mode spectateur
     * (cinematique de chute) viennent du serveur lui-meme.
     */
    @EventHandler(ignoreCancelled = true)
    public void teleporter(PlayerTeleportEvent evenement) {
        if (!controleur.estEnPartie(evenement.getPlayer().getUniqueId())) return;
        if (TeleportAutorise.estAutorise(evenement.getPlayer().getUniqueId())) return;
        if (evenement.getCause() == PlayerTeleportEvent.TeleportCause.SPECTATE) return;
        evenement.setCancelled(true);
    }

    /**
     * Seules /rr et /leave restent ouvertes a un joueur assis (les admins
     * gardent tout) : /spawn, /warp, /home et consorts sont des sorties de
     * table hors circuit.
     */
    @EventHandler(ignoreCancelled = true)
    public void commande(PlayerCommandPreprocessEvent evenement) {
        if (!controleur.estEnPartie(evenement.getPlayer().getUniqueId())) return;
        if (evenement.getPlayer().hasPermission("buckshot.admin")) return;
        if (commandeAutorisee(evenement.getMessage())) return;
        evenement.setCancelled(true);
        evenement.getPlayer().sendMessage(net.kyori.adventure.text.Component.text(
                "Only /rr and /leave are allowed during a game.",
                net.kyori.adventure.text.format.NamedTextColor.RED));
    }

    /** /rr, /leave, avec ou sans prefixe de plugin (buckshot:rr), sans casse. */
    static boolean commandeAutorisee(String message) {
        String texte = message.startsWith("/") ? message.substring(1) : message;
        String premier = texte.trim().split("\\s+", 2)[0].toLowerCase(java.util.Locale.ROOT);
        int deuxPoints = premier.indexOf(':');
        if (deuxPoints >= 0) premier = premier.substring(deuxPoints + 1);
        return premier.equals("rr") || premier.equals("leave");
    }

    @EventHandler
    public void rejoindre(PlayerJoinEvent evenement) {
        if (controleur.inventaire().aRecuperer(evenement.getPlayer().getUniqueId())) {
            controleur.inventaire().restaurer(evenement.getPlayer());
        }
    }
}
