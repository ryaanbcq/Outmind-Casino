package net.thundranode.buckshot.jeu;

import java.util.List;

public record ResultatAction(boolean acceptee, String erreur, List<EvenementPartie> evenements) {

    public ResultatAction {
        erreur = erreur == null ? "" : erreur;
        evenements = List.copyOf(evenements);
    }

    public static ResultatAction ok(List<EvenementPartie> evenements) {
        return new ResultatAction(true, "", evenements);
    }

    public static ResultatAction refuse(String erreur) {
        return new ResultatAction(false, erreur, List.of());
    }
}
