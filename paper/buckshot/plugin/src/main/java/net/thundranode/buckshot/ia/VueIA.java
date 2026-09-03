package net.thundranode.buckshot.ia;

import net.thundranode.buckshot.jeu.Objet;
import net.thundranode.buckshot.jeu.TypeCartouche;

import java.util.List;
import java.util.Optional;

/** Seule donnée que la stratégie du dealer est autorisée à recevoir. */
public record VueIA(int round,
                    int ballesReellesRestantes,
                    int ballesBlanchesRestantes,
                    int viesDealer,
                    int viesJoueur,
                    List<Objet> objetsDealer,
                    int toursSautesDealer,
                    int toursSautesJoueur,
                    /** Faux tant que le joueur n'a pas repris la main depuis ses dernieres menottes. */
                    boolean joueurMenottable,
                    boolean couteauDealerActif,
                    Optional<TypeCartouche> chambreConnueParDealer) {

    public VueIA {
        objetsDealer = List.copyOf(objetsDealer);
        chambreConnueParDealer = chambreConnueParDealer == null
                ? Optional.empty() : chambreConnueParDealer;
        if (ballesReellesRestantes < 0 || ballesBlanchesRestantes < 0) {
            throw new IllegalArgumentException("composition negative");
        }
    }

    public double probabiliteReelle() {
        if (chambreConnueParDealer.isPresent()) {
            return chambreConnueParDealer.get() == TypeCartouche.REELLE ? 1.0 : 0.0;
        }
        int total = ballesReellesRestantes + ballesBlanchesRestantes;
        return total == 0 ? 0.0 : (double) ballesReellesRestantes / total;
    }
}
