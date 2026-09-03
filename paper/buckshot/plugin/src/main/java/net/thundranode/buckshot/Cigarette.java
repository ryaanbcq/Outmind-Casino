package net.thundranode.buckshot;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import net.kyori.adventure.key.Key;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * Fabrique la cigarette affichee par l'ItemDisplay de la fumette.
 *
 * <p>Contrairement au {@link Fusil}, elle n'est jamais tenue ni cliquee :
 * pas de composant consumable, l'item ne sert qu'a porter le modele.
 */
public final class Cigarette {

    public static final Key MODELE = Key.key("rr", "cigarette");

    /** Etapes de combustion, dans l'ordre du sélecteur du pack. */
    public static final String[] ETAPES = {"s0", "s1", "s2", "s3"};

    public static ItemStack creer(String etape) {
        ItemStack item = new ItemStack(Material.PAPER);
        item.setData(DataComponentTypes.ITEM_MODEL, MODELE);
        item.setData(DataComponentTypes.CUSTOM_MODEL_DATA,
                CustomModelData.customModelData().addString(etape));
        return item;
    }

    private Cigarette() {
    }
}
