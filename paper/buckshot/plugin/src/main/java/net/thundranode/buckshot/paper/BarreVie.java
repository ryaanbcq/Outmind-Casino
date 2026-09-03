package net.thundranode.buckshot.paper;

import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;

/**
 * Affiche les vies de la partie dans la barre de vie vanilla.
 *
 * <p>Une vie vaut un coeur, soit deux points de vie. La barre est donc recalee
 * sur le plafond de vies du round et non sur les vingt points habituels : a
 * plafond cinq, le joueur voit cinq coeurs pleins et en perd un par cartouche
 * reelle encaissee.
 *
 * <p>Les valeurs d'origine sont sauvegardees par {@link InventairePartie}, en
 * meme temps que l'inventaire et la position, et rendues a la fin de la partie.
 */
public final class BarreVie {

    private final int plafond;

    public BarreVie(int plafond) {
        if (plafond <= 0) {
            throw new IllegalArgumentException("plafond de vies invalide");
        }
        this.plafond = plafond;
    }

    /** Recale la barre sur le plafond du jeu et coupe faim et degats vanilla. */
    public void installer(Player joueur, int vies) {
        AttributeInstance maximum = joueur.getAttribute(Attribute.MAX_HEALTH);
        if (maximum != null) {
            maximum.setBaseValue(plafond * 2.0);
        }
        // Depuis la 1.20.5 l'absorption est plafonnee par max_absorption,
        // base 0 par defaut : sans lever ce plafond, setAbsorptionAmount est
        // ignore en silence et les coeurs wither de la cigarette
        // n'apparaissent jamais (constate en jeu le 2026-08-23 : le moteur
        // disait "vies=4 dont noires=1", la barre n'affichait rien).
        AttributeInstance absorption = joueur.getAttribute(Attribute.MAX_ABSORPTION);
        if (absorption != null) {
            absorption.setBaseValue(plafond * 2.0);
        }
        // Faim pleine : sans ca la regeneration naturelle remonterait les coeurs
        // toute seule, et la barre cesserait de dire les vies de la partie.
        joueur.setFoodLevel(20);
        joueur.setSaturation(20f);
        joueur.setInvulnerable(true);
        afficher(joueur, vies, 0);
    }

    /**
     * Met la barre au nombre de vies courant.
     *
     * <p>Les vies regagnees a la cigarette s'affichent en coeurs NOIRS : ce
     * sont des coeurs d'absorption (le seul type de coeur supplementaire que
     * le client sait dessiner), retextures en noir dans le resource pack.
     * L'absorption se dessinant toujours apres les conteneurs, la barre se
     * recale sur les vies rouges pour que les coeurs noirs collent aux
     * rouges -- les vies perdues n'affichent donc plus de conteneur vide.
     */
    public void afficher(Player joueur, int vies, int viesClope) {
        int rouges = Math.max(0, vies - Math.max(0, viesClope));
        AttributeInstance maximum = joueur.getAttribute(Attribute.MAX_HEALTH);
        // L'absorption se dessine toujours APRES les conteneurs : pour que
        // les coeurs wither collent aux rouges, la rangee se resserre sur
        // les vies rouges (choix utilisateur du 2026-08-23, apres avoir vu
        // le wither expedie a l'oppose derriere les conteneurs vides --
        // l'intercaler est impossible cote client).
        if (maximum != null) {
            maximum.setBaseValue(Math.max(2.0, rouges * 2.0));
        }
        double haut = maximum == null ? plafond * 2.0 : maximum.getValue();
        // Zero point de vie tue. A zero vie le round se termine dans le tick qui
        // suit, derriere l'ecran noir : on laisse un demi-coeur plutot que de
        // declencher une mort vanilla en pleine partie.
        joueur.setHealth(Math.max(1.0, Math.min(rouges * 2.0, haut)));
        joueur.setAbsorptionAmount(Math.max(0, viesClope) * 2.0f);
    }
}
