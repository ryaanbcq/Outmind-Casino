package net.thundranode.buckshot.ia;

import net.thundranode.buckshot.jeu.Regles;
import net.thundranode.buckshot.jeu.TypeCartouche;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Probabilite exacte que DrDonutt gagne le round, par recurrence sur l'etat
 * PUBLIC (reelles et blanches restantes, vies, a qui le tour, menottes en
 * cours), les deux camps tirant au mieux et sans objets. L'ordre secret du
 * chargeur n'entre jamais : chaque tir est une loterie reelles/(total), et un
 * chargeur vide se recharge selon les fourchettes du round (memes tirages que
 * {@code Chargeur.creer}). Le joueur est suppose OPTIMAL (il minimise la
 * valeur) : c'est la lecture la plus prudente pour la maison.
 *
 * <p>Sert d'utilite a la strategie du dealer : une action vaut la probabilite
 * de gain de l'etat ou elle mene. Les objets (loupe, couteau, menottes, biere,
 * cigarette) sont evalues par leur effet immediat sur l'etat, puis le tir qui
 * suit. L'espace d'etats est minuscule (<= 8 cartouches, <= 5 vies) : tout est
 * memoise, le calcul est instantane.
 */
final class ValeurRound {

    /** Etat public d'un round. Les drapeaux « saute » = menottes non encore purgees. */
    record Etat(int round, int reelles, int blanches, int viesDealer, int viesJoueur,
                boolean tourDealer, boolean sauteDealer, boolean sauteJoueur) {

        Etat {
            if (reelles < 0 || blanches < 0) throw new IllegalArgumentException("composition negative");
        }

        int total() {
            return reelles + blanches;
        }

        double probabiliteReelle() {
            return total() == 0 ? 0 : (double) reelles / total();
        }

        Etat avec(int reelles, int blanches, int viesDealer, int viesJoueur,
                  boolean tourDealer, boolean sauteDealer, boolean sauteJoueur) {
            return new Etat(round, reelles, blanches, viesDealer, viesJoueur, tourDealer, sauteDealer, sauteJoueur);
        }
    }

    /**
     * Une table de valeurs par jeu de regles, partagee : les valeurs ne
     * dependent que des fourchettes de chargeur, et plusieurs strategies
     * (une par table, ou des milliers d'instances en simulation) n'ont aucune
     * raison de refaire le meme calcul.
     */
    private static final Map<Regles, ValeurRound> PAR_REGLES = new ConcurrentHashMap<>();

    static ValeurRound pour(Regles regles) {
        return PAR_REGLES.computeIfAbsent(Objects.requireNonNull(regles, "regles"), ValeurRound::new);
    }

    private final Regles regles;
    private final Map<Etat, Double> memo = new ConcurrentHashMap<>();

    private ValeurRound(Regles regles) {
        this.regles = regles;
    }

    /** P(dealer gagne) depuis l'etat, au debut du tour de l'acteur indique (menottes deja resolues). */
    double valeur(Etat e) {
        if (e.viesDealer() <= 0) return 0;
        if (e.viesJoueur() <= 0) return 1;
        Double connue = memo.get(e);
        if (connue != null) return connue;
        double v;
        if (e.total() == 0) {
            v = valeurRechargement(e);
        } else {
            double soi = tir(e, true, TypeCartouche.REELLE, TypeCartouche.BLANCHE, 1);
            double adversaire = tir(e, false, TypeCartouche.REELLE, TypeCartouche.BLANCHE, 1);
            v = e.tourDealer() ? Math.max(soi, adversaire) : Math.min(soi, adversaire);
        }
        memo.put(e, v);
        return v;
    }

    /**
     * Valeur d'un tir de l'acteur au tour, chambre inconnue : loterie sur la
     * composition. {@code degats} = 2 si le couteau est arme.
     */
    double tirInconnu(Etat e, boolean surSoi, int degats) {
        return tir(e, surSoi, TypeCartouche.REELLE, TypeCartouche.BLANCHE, degats);
    }

    private double tir(Etat e, boolean surSoi, TypeCartouche siReelle, TypeCartouche siBlanche, int degats) {
        double p = e.probabiliteReelle();
        double v = 0;
        if (p > 0) v += p * apresTir(e, surSoi, siReelle, degats);
        if (p < 1) v += (1 - p) * apresTir(e, surSoi, siBlanche, degats);
        return v;
    }

    /** Valeur apres un tir dont la cartouche est connue (loupe, ou deduite). */
    double apresTir(Etat e, boolean surSoi, TypeCartouche cartouche, int degats) {
        int reelles = e.reelles(), blanches = e.blanches();
        int viesDealer = e.viesDealer(), viesJoueur = e.viesJoueur();
        if (cartouche == TypeCartouche.REELLE) reelles--; else blanches--;
        boolean tireurDealer = e.tourDealer();
        boolean cibleDealer = surSoi == tireurDealer;
        if (cartouche == TypeCartouche.REELLE) {
            if (cibleDealer) viesDealer -= degats; else viesJoueur -= degats;
        }
        if (viesDealer <= 0) return 0;
        if (viesJoueur <= 0) return 1;
        // Une blanche sur soi garde la main ; tout le reste rend la main.
        boolean prochainDealer = cartouche == TypeCartouche.BLANCHE && surSoi ? tireurDealer : !tireurDealer;
        return debutDeTour(e.avec(reelles, blanches, viesDealer, viesJoueur,
                prochainDealer, e.sauteDealer(), e.sauteJoueur()));
    }

    /** Ejection d'une cartouche (biere) : la main reste au tireur. */
    double apresEjection(Etat e, TypeCartouche cartouche) {
        int reelles = e.reelles(), blanches = e.blanches();
        if (cartouche == TypeCartouche.REELLE) reelles--; else blanches--;
        return debutDeTour(e.avec(reelles, blanches, e.viesDealer(), e.viesJoueur(),
                e.tourDealer(), e.sauteDealer(), e.sauteJoueur()));
    }

    /**
     * Applique les menottes comme {@code MoteurPartie.definirTourDisponible} :
     * un acteur qui doit sauter rend la main (drapeau purge), puis on evalue.
     */
    double debutDeTour(Etat e) {
        boolean tourDealer = e.tourDealer();
        boolean sauteDealer = e.sauteDealer(), sauteJoueur = e.sauteJoueur();
        for (int garde = 0; garde < 3; garde++) {
            if (tourDealer && sauteDealer) {
                sauteDealer = false;
                tourDealer = false;
            } else if (!tourDealer && sauteJoueur) {
                sauteJoueur = false;
                tourDealer = true;
            } else {
                break;
            }
        }
        return valeur(e.avec(e.reelles(), e.blanches(), e.viesDealer(), e.viesJoueur(),
                tourDealer, sauteDealer, sauteJoueur));
    }

    /** Chargeur vide : esperance sur les chargeurs possibles du round, la main ne change pas. */
    private double valeurRechargement(Etat e) {
        Regles.PlageChargeur plage = regles.chargeurPourRound(Math.min(e.round(), 3));
        double v = 0;
        int nbTotaux = plage.totalMax() - plage.totalMin() + 1;
        for (int total = plage.totalMin(); total <= plage.totalMax(); total++) {
            int reellesMax = Math.min(plage.reellesMax(), total - 1);
            int nbReelles = reellesMax - plage.reellesMin() + 1;
            for (int reelles = plage.reellesMin(); reelles <= reellesMax; reelles++) {
                v += valeur(e.avec(reelles, total - reelles, e.viesDealer(), e.viesJoueur(),
                        e.tourDealer(), e.sauteDealer(), e.sauteJoueur())) / (nbTotaux * nbReelles);
            }
        }
        return v;
    }
}
