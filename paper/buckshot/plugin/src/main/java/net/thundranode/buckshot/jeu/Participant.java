package net.thundranode.buckshot.jeu;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public final class Participant {

    private final int viesInitiales;
    private final int viesPlafond;
    private final int objetsMax;
    private final List<Objet> objets = new ArrayList<>();
    private int vies;
    /** Vies regagnees a la cigarette encore en jeu : affichees en noir. */
    private int viesClope;
    private int toursASauter;
    /** Faux entre la pose des menottes et le tour ou la victime rejoue. */
    private boolean aJoueDepuisMenottes = true;
    private boolean prochainTirDouble;
    private TypeCartouche chambreConnue;

    public Participant(int viesInitiales, int viesPlafond, int objetsMax) {
        if (viesInitiales <= 0 || viesPlafond < viesInitiales || objetsMax < 0) {
            throw new IllegalArgumentException("limites participant invalides");
        }
        this.viesInitiales = viesInitiales;
        this.viesPlafond = viesPlafond;
        this.objetsMax = objetsMax;
        this.vies = viesInitiales;
    }

    /** Plafond de vies du round : le donut ne peut pas faire monter au-dela. */
    public int viesPlafond() {
        return viesPlafond;
    }

    public int vies() {
        return vies;
    }

    public int viesClope() {
        return viesClope;
    }

    public List<Objet> objets() {
        return Collections.unmodifiableList(objets);
    }

    public int placesLibres() {
        return objetsMax - objets.size();
    }

    public int toursASauter() {
        return toursASauter;
    }

    public boolean prochainTirDouble() {
        return prochainTirDouble;
    }

    public Optional<TypeCartouche> chambreConnue() {
        return Optional.ofNullable(chambreConnue);
    }

    /** Les vies de depart changent d'un round a l'autre : elles sont fournies. */
    public void reinitialiserRound(int viesDuRound) {
        if (viesDuRound <= 0 || viesDuRound > viesPlafond) {
            throw new IllegalArgumentException("vies de round invalides : " + viesDuRound);
        }
        vies = viesDuRound;
        viesClope = 0;
        objets.clear();
        toursASauter = 0;
        aJoueDepuisMenottes = true;
        prochainTirDouble = false;
        chambreConnue = null;
    }

    public void subir(int degats) {
        if (degats < 0) {
            throw new IllegalArgumentException("degats negatifs");
        }
        vies = Math.max(0, vies - degats);
        // Les vies de cigarette encaissent en premier, comme l'absorption
        // vanilla qui les affiche : les coeurs noirs partent avant les rouges.
        viesClope = Math.min(Math.max(0, viesClope - degats), vies);
    }

    public boolean soigner() {
        if (vies >= viesPlafond) {
            return false;
        }
        vies++;
        viesClope = Math.min(viesClope + 1, vies);
        return true;
    }

    public boolean ajouterObjet(Objet objet) {
        if (objets.size() >= objetsMax) {
            return false;
        }
        objets.add(objet);
        return true;
    }

    public boolean possede(Objet objet) {
        return objets.contains(objet);
    }

    public boolean retirerObjet(Objet objet) {
        return objets.remove(objet);
    }

    /**
     * Un seul tour saute, pas deux (demande user 2026-08-27) : deux tours
     * d'affilee volaient trop de la manche a la victime, surtout au round 1
     * ou le chargeur ne fait que deux a quatre cartouches.
     */
    public boolean menotterPourUnTour() {
        // Le compteur seul ne suffit pas a interdire l'enchainement. Il
        // retombe a zero AU DEBUT du dernier tour saute : a cet instant la
        // victime parait libre alors qu'elle n'a toujours pas joue, et le
        // dealer remettait les menottes avant qu'elle reprenne la main. Il
        // faut donc exiger un vrai tour joue entre deux menottages.
        if (toursASauter > 0 || !aJoueDepuisMenottes) {
            return false;
        }
        toursASauter = 1;
        aJoueDepuisMenottes = false;
        return true;
    }

    /** Vrai tant que ce participant peut se faire menotter. */
    public boolean menottable() {
        return toursASauter == 0 && aJoueDepuisMenottes;
    }

    /**
     * Vrai de la pose des menottes jusqu'au tour ou la victime rejoue :
     * c'est la fenetre pendant laquelle les bracelets restent visibles a
     * l'ecran, meme quand le compteur de tours est deja retombe.
     */
    public boolean porteMenottes() {
        return !aJoueDepuisMenottes;
    }

    /** A appeler quand le participant prend reellement la main. */
    public void prendreLaMain() {
        aJoueDepuisMenottes = true;
    }

    public boolean consommerTourSaute() {
        if (toursASauter <= 0) {
            return false;
        }
        toursASauter--;
        return true;
    }

    public boolean activerTirDouble() {
        if (prochainTirDouble) {
            return false;
        }
        prochainTirDouble = true;
        return true;
    }

    public int consommerDegatsProchainTir() {
        int degats = prochainTirDouble ? 2 : 1;
        prochainTirDouble = false;
        return degats;
    }

    public void memoriserChambre(TypeCartouche type) {
        chambreConnue = type;
    }

    public void oublierChambre() {
        chambreConnue = null;
    }
}
