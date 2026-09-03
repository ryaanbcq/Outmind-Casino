package net.thundranode.buckshot.paper;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Consumable;
import io.papermc.paper.datacomponent.item.CustomModelData;
import io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Gestes de bras VANILLA forces par le serveur : fumer, boire, inspecter.
 *
 * <p>Le seul moyen de faire bouger les bras d'un joueur pour les autres est
 * l'animation d'usage d'un item ({@code consumable.animation}) : toot_horn
 * porte la main a la bouche et la tient, drink/eat la font aller et venir,
 * spyglass leve le bras a l'oeil, crossbow tend les deux bras. Le serveur
 * peut lancer cet usage lui-meme ({@code startUsingItem}) : tous les clients,
 * Bedrock compris, rendent alors la pose -- contrairement aux ItemDisplay,
 * invisibles sur Bedrock et caches au porteur.
 *
 * <p>Le geste remplace l'item en main par un item porteur de l'animation
 * (invisible, ou un vrai modele comme la biere) puis le restaure a la fin,
 * UNIQUEMENT s'il est encore la : la hotbar est reappliquee a chaque phase
 * et ne doit pas etre ecrasee par une restauration tardive.
 */
final class Geste {
    private final JavaPlugin plugin;
    private final NamespacedKey cle;
    private final Map<UUID, EnCours> enCours = new HashMap<>();

    private record EnCours(ItemStack avant, BukkitTask fin) { }

    Geste(JavaPlugin plugin) {
        this.plugin = plugin;
        this.cle = new NamespacedKey(plugin, "geste");
    }

    /** Main a la bouche, tenue : la taffe. */
    void fumer(Player porteur, int ticks) {
        tenir(porteur, ItemUseAnimation.TOOT_HORN, null, ticks);
    }

    /** Boire au goulot avec la bouteille visible en main. */
    void boire(Player porteur, ItemStack bouteille, int ticks) {
        tenir(porteur, ItemUseAnimation.DRINK, bouteille, ticks);
    }

    /**
     * Fait tenir a {@code porteur} la pose de bras de {@code animation}
     * pendant {@code ticks}. {@code visible} est l'item montre en main
     * (null = item invisible : seule la pose compte).
     */
    void tenir(Player porteur, ItemUseAnimation animation, ItemStack visible, int ticks) {
        if (porteur == null || !porteur.isValid()) return;
        relacher(porteur);
        ItemStack avant = porteur.getInventory().getItemInMainHand().clone();
        ItemStack item = visible != null ? visible.clone() : invisible();
        item.setData(DataComponentTypes.CONSUMABLE, Consumable.consumable()
                .consumeSeconds(3600f)
                .animation(animation)
                .hasConsumeParticles(false));
        item.editMeta(meta -> meta.getPersistentDataContainer().set(cle, PersistentDataType.BYTE, (byte) 1));
        porteur.getInventory().setItemInMainHand(item);
        try {
            porteur.startUsingItem(EquipmentSlot.HAND);
        } catch (RuntimeException erreur) {
            // Cosmetique : un PNJ recalcitrant ne doit pas figer la partie.
            Bukkit.getLogger().warning("[Buckshot] geste impossible pour " + porteur.getName() + " : " + erreur);
        }
        BukkitTask fin = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            enCours.remove(porteur.getUniqueId());
            terminer(porteur, avant);
        }, Math.max(1, ticks));
        enCours.put(porteur.getUniqueId(), new EnCours(avant, fin));
    }

    /** Coupe un geste en cours et rend la main telle qu'elle etait. */
    void relacher(Player porteur) {
        EnCours g = enCours.remove(porteur.getUniqueId());
        if (g == null) return;
        g.fin().cancel();
        terminer(porteur, g.avant());
    }

    private void terminer(Player porteur, ItemStack avant) {
        if (!porteur.isValid()) return;
        porteur.clearActiveItem();
        if (estGeste(porteur.getInventory().getItemInMainHand())) {
            porteur.getInventory().setItemInMainHand(avant);
        }
    }

    boolean estGeste(ItemStack item) {
        return item != null && !item.isEmpty() && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(cle, PersistentDataType.BYTE);
    }

    /** Meme habillage que la main vide du dealer : modele fusil en etat "cache". */
    private static ItemStack invisible() {
        ItemStack item = new ItemStack(Material.PAPER);
        item.setData(DataComponentTypes.ITEM_MODEL, Key.key("rr", "shotgun"));
        item.setData(DataComponentTypes.CUSTOM_MODEL_DATA,
                CustomModelData.customModelData().addString("cache"));
        return item;
    }
}
