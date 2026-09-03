package net.thundranode.buckshot.jeu;

import java.util.List;

public record Regles(List<Integer> viesParRound,
                     int viesPlafond,
                     List<PlageChargeur> chargeursParRound,
                     List<Integer> objetsParChargeur,
                     int objetsMax,
                     int blackoutTicks,
                     List<Integer> poidsObjets) {

    /** Retro-compatible : sans poids, tirage uniforme des objets. */
    public Regles(List<Integer> viesParRound, int viesPlafond,
                  List<PlageChargeur> chargeursParRound, List<Integer> objetsParChargeur,
                  int objetsMax, int blackoutTicks) {
        this(viesParRound, viesPlafond, chargeursParRound, objetsParChargeur,
                objetsMax, blackoutTicks, java.util.Collections.nCopies(Objet.values().length, 1));
    }

    /**
     * Fourchette de generation d'un chargeur : le total est tire d'abord,
     * puis le nombre de reelles dans sa propre fourchette, clampee pour
     * garder au moins une reelle et une blanche. Deux tirages plutot qu'un
     * ratio fixe : c'est ce qui rend chaque rechargement illisible.
     */
    public record PlageChargeur(int totalMin, int totalMax, int reellesMin, int reellesMax) {
        public PlageChargeur {
            if (totalMin < 2 || totalMax < totalMin) {
                throw new IllegalArgumentException("fourchette de total invalide");
            }
            if (reellesMin < 1 || reellesMax < reellesMin || reellesMin > totalMin - 1) {
                throw new IllegalArgumentException("fourchette de reelles invalide");
            }
        }
    }

    public Regles {
        objetsParChargeur = List.copyOf(objetsParChargeur);
        chargeursParRound = List.copyOf(chargeursParRound);
        viesParRound = List.copyOf(viesParRound);
        if (viesParRound.size() != 3 || viesParRound.stream().anyMatch(v -> v <= 0)) {
            throw new IllegalArgumentException("viesParRound doit contenir trois valeurs positives");
        }
        if (viesParRound.stream().anyMatch(v -> v > viesPlafond)) {
            throw new IllegalArgumentException("viesPlafond ne peut pas etre sous les vies d'un round");
        }
        if (chargeursParRound.size() != 3) {
            throw new IllegalArgumentException("chargeursParRound doit contenir trois fourchettes");
        }
        if (objetsParChargeur.size() != 3 || objetsParChargeur.stream().anyMatch(n -> n < 0)) {
            throw new IllegalArgumentException("objetsParChargeur doit contenir trois valeurs positives");
        }
        if (objetsMax < 0 || blackoutTicks <= 0) {
            throw new IllegalArgumentException("objetsMax et blackoutTicks invalides");
        }
        poidsObjets = List.copyOf(poidsObjets);
        if (poidsObjets.size() != Objet.values().length
                || poidsObjets.stream().anyMatch(w -> w < 0)
                || poidsObjets.stream().mapToInt(Integer::intValue).sum() <= 0) {
            throw new IllegalArgumentException("poidsObjets doit donner un poids >= 0 par objet, somme > 0");
        }
    }

    /**
     * Escalade : initiation courte au round 1, asymetries au round 2,
     * chargeurs pleins au round final.
     */
    public static Regles standard() {
        return new Regles(List.of(3, 4, 5), 5, List.of(
                new PlageChargeur(2, 4, 1, 2),
                new PlageChargeur(4, 6, 1, 3),
                new PlageChargeur(5, 8, 3, 4)), List.of(0, 2, 4), 8, 40);
    }

    /**
     * Vies au depart d'un round. L'escalade (3, 4, 5) allonge les rounds a
     * mesure que les chargeurs grossissent : le round final se joue en
     * pleine sante, avec la marge qu'exigent ses cinq a huit cartouches.
     */
    public int viesPourRound(int round) {
        if (round < 1 || round > viesParRound.size()) {
            throw new IllegalArgumentException("round invalide : " + round);
        }
        return viesParRound.get(round - 1);
    }

    public PlageChargeur chargeurPourRound(int round) {
        if (round < 1 || round > chargeursParRound.size()) {
            throw new IllegalArgumentException("round invalide : " + round);
        }
        return chargeursParRound.get(round - 1);
    }

    public int objetsPourRound(int round) {
        if (round < 1 || round > objetsParChargeur.size()) {
            throw new IllegalArgumentException("round invalide : " + round);
        }
        return objetsParChargeur.get(round - 1);
    }
}
