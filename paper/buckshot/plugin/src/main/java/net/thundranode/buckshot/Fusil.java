package net.thundranode.buckshot;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Consumable;
import io.papermc.paper.datacomponent.item.CustomModelData;
import io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/** Fabrique le fusil et lui pose son etat d'animation. */
public final class Fusil {

    public static final Key MODELE = Key.key("rr", "shotgun");

    /**
     * Le composant consumable est ce qui rend l'item "utilisable".
     *
     * <p>Sans lui, {@code use_duration} ne compte pas et le clic droit ne
     * declenche rien : les animations pilotees par le client resteraient
     * figees. La duree est absurde a dessein pour que l'item ne se consomme
     * jamais, et l'animation crossbow donne au passage la pose de bras leves
     * en troisieme personne. Verifie en jeu le 2026-08-19.
     */
    private static void appliquerBase(ItemStack item) {
        item.setData(DataComponentTypes.ITEM_MODEL, MODELE);
        item.setData(DataComponentTypes.CONSUMABLE,
                Consumable.consumable()
                        .consumeSeconds(3600f)
                        .animation(ItemUseAnimation.CROSSBOW)
                        .hasConsumeParticles(false));
        item.setData(DataComponentTypes.ITEM_NAME, Component.text("Shotgun"));
    }

    public static ItemStack creer() {
        // Surtout pas un baton : c'est l'item-outil par defaut de Litematica,
        // qui intercepte alors le clic cote client. Le paquet n'atteint jamais
        // le serveur et le tir est silencieusement mort, sans la moindre trace
        // dans les logs. Le kelp ne se pose que dans l'eau, donc il ne risque
        // pas non plus d'etre plante par un clic droit sur la table.
        ItemStack item = new ItemStack(Material.KELP);
        appliquerBase(item);
        poser(item, "hold");
        return item;
    }

    /** Pose l'etat sur l'item. Le nom va tel quel dans custom_model_data. */
    public static void poser(ItemStack item, String etat) {
        appliquerBase(item);
        item.setData(DataComponentTypes.CUSTOM_MODEL_DATA,
                CustomModelData.customModelData().addString(etat));
    }

    private Fusil() {
    }
}
