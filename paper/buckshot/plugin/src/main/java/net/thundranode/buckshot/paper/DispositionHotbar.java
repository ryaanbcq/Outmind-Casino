package net.thundranode.buckshot.paper;

import net.thundranode.buckshot.jeu.Objet;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

record DispositionHotbar(List<Entree> entrees) {

    enum Type { TIR_DRDONUTT, TIR_SOI, OBJET, ATTENTE }

    record Entree(int slot, Type type, Objet objet, int quantite) { }

    static DispositionHotbar creer(List<Objet> objets, boolean tourJoueur) {
        if (!tourJoueur) {
            return new DispositionHotbar(List.of(new Entree(4, Type.ATTENTE, null, 1)));
        }
        List<Entree> entrees = new ArrayList<>();
        entrees.add(new Entree(0, Type.TIR_DRDONUTT, null, 1));
        entrees.add(new Entree(1, Type.TIR_SOI, null, 1));
        entrees.addAll(entreesObjets(objets));
        return new DispositionHotbar(List.copyOf(entrees));
    }

    /**
     * Tour du joueur, fusil encore pose sur la table : les objets seuls, aux
     * MEMES slots que la disposition complete -- quand le fusil est ramasse,
     * seuls les slots 0 et 1 se remplissent, rien ne saute.
     */
    static DispositionHotbar sansFusil(List<Objet> objets) {
        return new DispositionHotbar(List.copyOf(entreesObjets(objets)));
    }

    private static List<Entree> entreesObjets(List<Objet> objets) {
        List<Entree> entrees = new ArrayList<>();
        Map<Objet, Integer> quantites = new EnumMap<>(Objet.class);
        for (Objet objet : objets) quantites.merge(objet, 1, Integer::sum);
        int slot = 2;
        for (Objet objet : Objet.values()) {
            Integer quantite = quantites.get(objet);
            if (quantite != null) {
                entrees.add(new Entree(slot++, Type.OBJET, objet, quantite));
            }
        }
        return entrees;
    }
}
