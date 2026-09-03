package net.thundranode.buckshot;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

/**
 * Table des etats d'animation, lue depuis etats.json.
 *
 * <p>Ce fichier est produit par tools/gen_transforms.py en meme temps que les
 * modeles de pose. Le plugin ne recopie donc aucun nom d'etat ni aucun nombre
 * d'images : ajouter une animation cote Python la rend disponible ici sans
 * toucher au Java. Les deux listes avaient deja diverge deux fois.
 */
public final class Etats {

    /**
     * Un état : nombre d'images, lecture temporelle, cadence et longueur du
     * cycle de tremblement (0 si l'état ne tremble pas une fois sa pose atteinte).
     */
    public record Etat(int frames, boolean client, int ticksParFrame, int tremblement) {

        /** Durée pendant laquelle le plugin maintient l'usage actif. */
        public int dureeTicks() {
            return frames * ticksParFrame;
        }
    }

    private final Map<String, Etat> table;

    private Etats(Map<String, Etat> table) {
        this.table = table;
    }

    public static Etats charger() {
        var flux = Etats.class.getResourceAsStream("/etats.json");
        if (flux == null) {
            throw new IllegalStateException(
                    "etats.json absent du jar : lancer tools/gen_transforms.py avant de compiler");
        }
        var type = new TypeToken<Map<String, Etat>>() { }.getType();
        Map<String, Etat> lu = new Gson().fromJson(
                new InputStreamReader(flux, StandardCharsets.UTF_8), type);
        return new Etats(Collections.unmodifiableMap(lu));
    }

    public Etat get(String nom) {
        return table.get(nom);
    }

    public boolean existe(String nom) {
        return table.containsKey(nom);
    }

    public java.util.Set<String> noms() {
        return table.keySet();
    }
}
