package net.thundranode.buckshot.ia;

import net.thundranode.buckshot.jeu.Cible;
import net.thundranode.buckshot.jeu.Objet;
import net.thundranode.buckshot.jeu.TypeCartouche;

import java.util.Objects;
import java.util.random.RandomGenerator;

public final class StrategieDrDonutt {

    public ActionIA choisir(VueIA vue, RandomGenerator aleatoire) {
        Objects.requireNonNull(vue, "vue");
        Objects.requireNonNull(aleatoire, "aleatoire");

        // Jamais a une vie au round final : le moteur y refuse la cigarette
        // (regle user 2026-08-27), et un refus laisse le tour sans action.
        boolean fumerInterdit = vue.round() >= 3 && vue.viesDealer() <= 1;
        if (vue.viesDealer() < 3 && !fumerInterdit && possede(vue, Objet.CIGARETTES)) {
            return new ActionIA.UtiliserObjet(Objet.CIGARETTES);
        }
        double pReelle = vue.probabiliteReelle();
        // La loupe ne s'use pas sur une chambre deja deduite : quand il ne
        // reste que des reelles ou que des blanches, la composition publique
        // donne la reponse gratuitement.
        if (vue.chambreConnueParDealer().isEmpty() && possede(vue, Objet.LOUPE)
                && pReelle > 0 && pReelle < 1) {
            return new ActionIA.UtiliserObjet(Objet.LOUPE);
        }
        if (!vue.couteauDealerActif() && possede(vue, Objet.COUTEAU) && pReelle >= 2.0 / 3.0) {
            return new ActionIA.UtiliserObjet(Objet.COUTEAU);
        }
        // Tester le compteur seul faisait choisir des menottes que le moteur
        // refusait, et un refus laisse le tour du dealer sans action.
        if (vue.joueurMenottable() && possede(vue, Objet.MENOTTES)) {
            return new ActionIA.UtiliserObjet(Objet.MENOTTES);
        }
        // Chambre connue : on tire, AVANT de considerer la biere. Boire une
        // blanche connue gaspillait l'objet -- le tir sur soi garde la main
        // exactement pareil, gratuitement.
        if (vue.chambreConnueParDealer().isPresent()) {
            return new ActionIA.Tirer(vue.chambreConnueParDealer().get() == TypeCartouche.REELLE
                    ? Cible.ADVERSAIRE : Cible.SOI);
        }
        if (possede(vue, Objet.BIERE) && pReelle < 0.34) {
            return new ActionIA.UtiliserObjet(Objet.BIERE);
        }

        // À une vie, le dealer ne prend pas le risque d'un tir sur soi inconnu.
        if (vue.viesDealer() == 1) {
            return new ActionIA.Tirer(Cible.ADVERSAIRE);
        }
        if (pReelle < 0.5) {
            return new ActionIA.Tirer(Cible.SOI);
        }
        if (pReelle == 0.5 && vue.viesDealer() > vue.viesJoueur()) {
            return new ActionIA.Tirer(aleatoire.nextBoolean() ? Cible.SOI : Cible.ADVERSAIRE);
        }
        return new ActionIA.Tirer(Cible.ADVERSAIRE);
    }

    private static boolean possede(VueIA vue, Objet objet) {
        return vue.objetsDealer().contains(objet);
    }
}
