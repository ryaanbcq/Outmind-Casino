package net.thundranode.buckshot.jeu;

public enum Acteur {
    JOUEUR,
    DEALER;

    public Acteur oppose() {
        return this == JOUEUR ? DEALER : JOUEUR;
    }
}
