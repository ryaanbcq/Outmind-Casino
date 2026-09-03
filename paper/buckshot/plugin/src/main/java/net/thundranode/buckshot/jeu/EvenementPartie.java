package net.thundranode.buckshot.jeu;

import java.util.List;

public sealed interface EvenementPartie permits
        EvenementPartie.RoundCommence,
        EvenementPartie.ChargeurAnnonce,
        EvenementPartie.ObjetsDistribues,
        EvenementPartie.Visee,
        EvenementPartie.BlackoutDemande,
        EvenementPartie.CartoucheRevelee,
        EvenementPartie.ViesChangees,
        EvenementPartie.TourChange,
        EvenementPartie.TourSaute,
        EvenementPartie.RoundTermine,
        EvenementPartie.PartieTerminee,
        EvenementPartie.ObjetUtilise,
        EvenementPartie.ChambrePrivee {

    record RoundCommence(int round) implements EvenementPartie {}

    record ChargeurAnnonce(int reelles, int blanches) implements EvenementPartie {}

    record ObjetsDistribues(Acteur acteur, List<Objet> objets) implements EvenementPartie {
        public ObjetsDistribues {
            objets = List.copyOf(objets);
        }
    }

    record Visee(Acteur acteur, Cible cible) implements EvenementPartie {}

    record BlackoutDemande(int ticks) implements EvenementPartie {}

    record CartoucheRevelee(TypeCartouche type, boolean ejectee) implements EvenementPartie {}

    record ViesChangees(Acteur acteur, int vies, int degats) implements EvenementPartie {}

    record TourChange(Acteur acteur) implements EvenementPartie {}

    record TourSaute(Acteur acteur, int restants) implements EvenementPartie {}

    record RoundTermine(int round, Acteur vainqueur) implements EvenementPartie {}

    record PartieTerminee(Acteur vainqueur) implements EvenementPartie {}

    record ObjetUtilise(Acteur acteur, Objet objet) implements EvenementPartie {}

    record ChambrePrivee(Acteur acteur, TypeCartouche type) implements EvenementPartie {}
}
