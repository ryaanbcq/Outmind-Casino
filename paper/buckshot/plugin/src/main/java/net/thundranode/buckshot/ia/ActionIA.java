package net.thundranode.buckshot.ia;

import net.thundranode.buckshot.jeu.Cible;
import net.thundranode.buckshot.jeu.Objet;

public sealed interface ActionIA permits ActionIA.UtiliserObjet, ActionIA.Tirer {

    record UtiliserObjet(Objet objet) implements ActionIA {}

    record Tirer(Cible cible) implements ActionIA {}
}
