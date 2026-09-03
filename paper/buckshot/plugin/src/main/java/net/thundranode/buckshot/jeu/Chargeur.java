package net.thundranode.buckshot.jeu;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.random.RandomGenerator;

/** Contient l'ordre secret. Aucune API ne permet de l'énumérer. */
public final class Chargeur {

    public record Composition(int reelles, int blanches) {
        public Composition {
            if (reelles < 0 || blanches < 0) {
                throw new IllegalArgumentException("composition negative");
            }
        }

        public int total() {
            return reelles + blanches;
        }
    }

    private final Deque<TypeCartouche> cartouches;
    private int reelles;
    private int blanches;

    private Chargeur(List<TypeCartouche> ordre) {
        if (ordre.isEmpty()) {
            throw new IllegalArgumentException("chargeur vide");
        }
        cartouches = new ArrayDeque<>(ordre);
        reelles = (int) ordre.stream().filter(t -> t == TypeCartouche.REELLE).count();
        blanches = ordre.size() - reelles;
    }

    public static Chargeur creer(Regles.PlageChargeur plage, RandomGenerator aleatoire) {
        Objects.requireNonNull(aleatoire, "aleatoire");
        int taille = aleatoire.nextInt(plage.totalMin(), plage.totalMax() + 1);
        // Clamp au total tire : une fourchette de reelles large (round 3 :
        // 3 a 4) doit rester compatible avec un petit total (5), et il faut
        // toujours au moins une blanche pour que compter serve a quelque
        // chose.
        int reellesMax = Math.min(plage.reellesMax(), taille - 1);
        int nbReelles = aleatoire.nextInt(plage.reellesMin(), reellesMax + 1);
        List<TypeCartouche> ordre = new ArrayList<>(taille);
        for (int i = 0; i < nbReelles; i++) {
            ordre.add(TypeCartouche.REELLE);
        }
        while (ordre.size() < taille) {
            ordre.add(TypeCartouche.BLANCHE);
        }
        Collections.shuffle(ordre, new java.util.Random(aleatoire.nextLong()));
        return new Chargeur(ordre);
    }

    /** Constructeur déterministe réservé aux smoke tests et scénarios contrôlés. */
    public static Chargeur depuis(List<TypeCartouche> ordre) {
        return new Chargeur(List.copyOf(ordre));
    }

    public Composition composition() {
        return new Composition(reelles, blanches);
    }

    public boolean estVide() {
        return cartouches.isEmpty();
    }

    public TypeCartouche retirerChambre() {
        TypeCartouche type = cartouches.removeFirst();
        if (type == TypeCartouche.REELLE) {
            reelles--;
        } else {
            blanches--;
        }
        return type;
    }

    TypeCartouche observerChambre() {
        return cartouches.getFirst();
    }
}
