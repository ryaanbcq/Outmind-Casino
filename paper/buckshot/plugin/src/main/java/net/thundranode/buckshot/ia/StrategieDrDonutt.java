package net.thundranode.buckshot.ia;

import net.thundranode.buckshot.jeu.Cible;
import net.thundranode.buckshot.jeu.Objet;
import net.thundranode.buckshot.jeu.Regles;
import net.thundranode.buckshot.jeu.TypeCartouche;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * Cerveau de DrDonutt : une strategie MIXTE centree sur le coup juste.
 *
 * <p>L'ancienne version etait une table de regles deterministe (sous 50 % de
 * reelles : tir sur soi, sinon sur le joueur, loupe des qu'il y a un doute...).
 * Deux defauts, mesures au moteur reel le 2026-09-07 : elle etait previsible
 * (une seule action possible dans 95 % des etats), et sa regle du tir sur soi
 * etait FAUSSE des qu'il reste une seule reelle avec plusieurs blanches : le
 * dealer cherchait les blanches lui-meme et mangeait la reelle. Un joueur qui
 * l'avait compris tirait sur DrDonutt a chaque tour et gagnait a l'esperance
 * (1,09 a 1,32 par mise selon le round encaisse).
 *
 * <p>Ici chaque action legale vaut la PROBABILITE EXACTE de gagner le round
 * qu'elle laisse ({@link ValeurRound} : recurrence sur la composition, les vies,
 * le tour et les menottes, joueur suppose optimal), les objets etant evalues
 * par leur effet puis le meilleur tir qui suit. L'action est ensuite TIREE
 * selon une loi de Boltzmann sur ces valeurs : le coup nettement meilleur reste
 * tres probable, les coups proches se partagent la mise, et une petite part
 * d'exploration s'ajoute. Une « humeur » cachee (temperature, agressivite,
 * curiosite), redessinee a chaque manche, deplace legerement les valeurs : deux
 * manches identiques a l'ecran ne suivent pas la meme loi, et ce qu'un joueur
 * apprend sur des centaines de rounds n'est qu'une moyenne, jamais une
 * prediction. Le bruit coute a la maison une part choisie et bornee, mesuree
 * par simulation (scratchpad bucksim2) avant tout deploiement.
 *
 * <p>Ce qui reste ferme, parce que le contraire serait une bourde et non de
 * l'imprevisibilite : jamais de tir sur soi avec une reelle connue ou deduite,
 * jamais de tir sur le joueur avec une blanche connue ou deduite, jamais un
 * objet que le moteur refuserait (le refus laisserait le tour du dealer sans
 * action), jamais de tir sur soi a une vie sans blanche connue.
 */
public final class StrategieDrDonutt {

    /**
     * Curseur de hasard du dealer, en points de probabilite de gain.
     *
     * @param temperatureMin  bornes de la temperature de Boltzmann, tiree a
     * @param temperatureMax  chaque manche : plus c'est haut, plus les coups
     *                        proches du meilleur sont joues (0.03 = quasi
     *                        optimal, 0.10 = franchement joueur)
     * @param exploration     part des tours joues au hasard parmi les actions
     *                        a moins de {@code marge} du meilleur coup
     * @param marge           ecart de valeur maximal d'une action exploree
     * @param humeur          amplitude des biais caches (agressivite, curiosite)
     */
    public record Temperament(double temperatureMin, double temperatureMax, double exploration,
                              double marge, double humeur) {
        public Temperament {
            if (temperatureMin <= 0 || temperatureMax < temperatureMin) {
                throw new IllegalArgumentException("temperature invalide");
            }
            if (exploration < 0 || exploration > 0.5 || marge < 0 || humeur < 0) {
                throw new IllegalArgumentException("temperament invalide");
            }
        }

        public static Temperament defaut() {
            // Calibre le 2026-09-07 au moteur reel (scratchpad bucksim2, oracle
            // 2000 parties x 200 rollouts) : le meilleur joueur possible gagne
            // 54,8 % des rounds 1 (53,6 % contre le dealer parfait), et le
            // premier coup du dealer n'est previsible que dans 35 % des
            // passages de main (64 % a temperature 0.03-0.06).
            return new Temperament(0.06, 0.10, 0.06, 0.12, 0.03);
        }
    }

    private record Candidat(ActionIA action, double valeur) {}

    private final ValeurRound valeurs;
    private Temperament temperament;

    /** Humeur de la manche : cachee, redessinee par {@link #nouvelleManche}. */
    private double temperature;
    private double agressivite;
    private double curiosite;

    public StrategieDrDonutt() {
        this(Regles.standard(), Temperament.defaut());
    }

    public StrategieDrDonutt(Regles regles) {
        this(regles, Temperament.defaut());
    }

    public StrategieDrDonutt(Regles regles, Temperament temperament) {
        this.valeurs = ValeurRound.pour(regles);
        temperament(temperament);
    }

    /** Change le curseur de hasard (rechargement de config) ; l'humeur courante est recalee. */
    public void temperament(Temperament temperament) {
        this.temperament = Objects.requireNonNull(temperament, "temperament");
        temperature = (temperament.temperatureMin() + temperament.temperatureMax()) / 2;
        agressivite = 0;
        curiosite = 0;
    }

    public Temperament temperament() {
        return temperament;
    }

    /** Tire une nouvelle humeur : a appeler au debut de chaque round. */
    public void nouvelleManche(RandomGenerator aleatoire) {
        Objects.requireNonNull(aleatoire, "aleatoire");
        temperature = temperament.temperatureMin() == temperament.temperatureMax()
                ? temperament.temperatureMin()
                : aleatoire.nextDouble(temperament.temperatureMin(), temperament.temperatureMax());
        double h = temperament.humeur();
        agressivite = h == 0 ? 0 : aleatoire.nextDouble(-h, h);
        curiosite = h == 0 ? 0 : aleatoire.nextDouble(-h, h);
    }

    public ActionIA choisir(VueIA vue, RandomGenerator aleatoire) {
        Objects.requireNonNull(vue, "vue");
        Objects.requireNonNull(aleatoire, "aleatoire");
        List<Candidat> candidats = candidats(vue);
        double meilleure = candidats.stream().mapToDouble(Candidat::valeur).max().orElse(0);
        if (aleatoire.nextDouble() < temperament.exploration()) {
            List<Candidat> acceptables = candidats.stream()
                    .filter(c -> c.valeur() >= meilleure - temperament.marge()).toList();
            return acceptables.get(aleatoire.nextInt(acceptables.size())).action();
        }
        // Boltzmann : poids exp((v - max) / T), le max evite les debordements.
        double[] poids = new double[candidats.size()];
        double total = 0;
        for (int i = 0; i < poids.length; i++) {
            poids[i] = Math.exp((candidats.get(i).valeur() - meilleure) / temperature);
            total += poids[i];
        }
        double tirage = aleatoire.nextDouble() * total;
        for (int i = 0; i < poids.length; i++) {
            tirage -= poids[i];
            if (tirage < 0) return candidats.get(i).action();
        }
        return candidats.get(poids.length - 1).action();
    }

    /**
     * Tir de repli, deterministe et toujours legal : si le moteur refuse
     * l'action choisie (etat qui a bouge entre le choix et l'execution), le
     * controleur tire ceci au lieu de laisser le tour du dealer vide.
     */
    public ActionIA.Tirer tirDeRepli(VueIA vue) {
        Optional<TypeCartouche> connue = chambreConnueOuDeduite(vue);
        if (connue.isPresent()) {
            return new ActionIA.Tirer(connue.get() == TypeCartouche.REELLE ? Cible.ADVERSAIRE : Cible.SOI);
        }
        if (vue.viesDealer() <= 1 || vue.probabiliteReelle() >= 0.5) {
            return new ActionIA.Tirer(Cible.ADVERSAIRE);
        }
        return new ActionIA.Tirer(Cible.SOI);
    }

    /** Chambre vue a la loupe, ou deduite d'une composition a une seule couleur. */
    private static Optional<TypeCartouche> chambreConnueOuDeduite(VueIA vue) {
        if (vue.chambreConnueParDealer().isPresent()) return vue.chambreConnueParDealer();
        if (vue.cartouchesRestantes() == 0) return Optional.empty();
        if (vue.ballesReellesRestantes() == 0) return Optional.of(TypeCartouche.BLANCHE);
        if (vue.ballesBlanchesRestantes() == 0) return Optional.of(TypeCartouche.REELLE);
        return Optional.empty();
    }

    private ValeurRound.Etat etat(VueIA vue) {
        return new ValeurRound.Etat(vue.round(), vue.ballesReellesRestantes(), vue.ballesBlanchesRestantes(),
                vue.viesDealer(), vue.viesJoueur(), true,
                vue.toursSautesDealer() > 0, vue.toursSautesJoueur() > 0);
    }

    /** Valeur d'un tir depuis cet etat avec cette connaissance de la chambre. */
    private double valeurTir(ValeurRound.Etat e, boolean surSoi, Optional<TypeCartouche> connue, int degats) {
        return connue.isPresent()
                ? valeurs.apresTir(e, surSoi, connue.get(), degats)
                : valeurs.tirInconnu(e, surSoi, degats);
    }

    /** Tirs legaux depuis un etat : jamais de bourde sur une chambre connue, jamais de tir sur soi a une vie. */
    private List<Candidat> tirs(ValeurRound.Etat e, Optional<TypeCartouche> connue, int degats) {
        List<Candidat> liste = new ArrayList<>(2);
        boolean reelleConnue = connue.isPresent() && connue.get() == TypeCartouche.REELLE;
        boolean blancheConnue = connue.isPresent() && connue.get() == TypeCartouche.BLANCHE;
        if (!blancheConnue) {
            liste.add(new Candidat(new ActionIA.Tirer(Cible.ADVERSAIRE),
                    valeurTir(e, false, connue, degats) + agressivite));
        }
        if (!reelleConnue && (blancheConnue || e.viesDealer() > 1)) {
            liste.add(new Candidat(new ActionIA.Tirer(Cible.SOI),
                    valeurTir(e, true, connue, degats) - agressivite));
        }
        return liste;
    }

    private static double meilleure(List<Candidat> candidats) {
        return candidats.stream().mapToDouble(Candidat::valeur).max().orElse(0);
    }

    /** Actions que le moteur acceptera, chacune avec sa valeur. Jamais vide. */
    private List<Candidat> candidats(VueIA vue) {
        ValeurRound.Etat e = etat(vue);
        Optional<TypeCartouche> connue = chambreConnueOuDeduite(vue);
        int degats = vue.couteauDealerActif() ? 2 : 1;
        double p = vue.probabiliteReelle();

        List<Candidat> liste = new ArrayList<>(tirs(e, connue, degats));

        // Les objets sont des actions gratuites : leur valeur est celle du
        // meilleur tir dans l'etat qu'ils laissent.
        if (possede(vue, Objet.LOUPE) && connue.isEmpty() && p > 0 && p < 1) {
            double v = p * meilleure(tirs(e, Optional.of(TypeCartouche.REELLE), degats))
                    + (1 - p) * meilleure(tirs(e, Optional.of(TypeCartouche.BLANCHE), degats));
            liste.add(new Candidat(new ActionIA.UtiliserObjet(Objet.LOUPE), v + curiosite));
        }
        if (possede(vue, Objet.COUTEAU) && !vue.couteauDealerActif()
                && !(connue.isPresent() && connue.get() == TypeCartouche.BLANCHE)) {
            // Le couteau ne sert qu'a un tir sur le joueur : evaluer ce tir a deux degats.
            List<Candidat> avecCouteau = tirs(e, connue, 2).stream()
                    .filter(c -> c.action().equals(new ActionIA.Tirer(Cible.ADVERSAIRE))).toList();
            if (!avecCouteau.isEmpty()) {
                liste.add(new Candidat(new ActionIA.UtiliserObjet(Objet.COUTEAU), meilleure(avecCouteau)));
            }
        }
        if (possede(vue, Objet.MENOTTES) && vue.joueurMenottable()) {
            ValeurRound.Etat menotte = e.avec(e.reelles(), e.blanches(), e.viesDealer(), e.viesJoueur(),
                    true, e.sauteDealer(), true);
            liste.add(new Candidat(new ActionIA.UtiliserObjet(Objet.MENOTTES), meilleure(tirs(menotte, connue, degats))));
        }
        if (possede(vue, Objet.BIERE) && vue.cartouchesRestantes() > 0
                && !(connue.isPresent() && connue.get() == TypeCartouche.BLANCHE)) {
            double v = connue.isPresent()
                    ? valeurs.apresEjection(e, connue.get())
                    : p * valeurs.apresEjection(e, TypeCartouche.REELLE)
                    + (1 - p) * valeurs.apresEjection(e, TypeCartouche.BLANCHE);
            liste.add(new Candidat(new ActionIA.UtiliserObjet(Objet.BIERE), v));
        }
        boolean fumerRefusee = vue.viesDealer() >= vue.viesPlafond()
                || (vue.round() >= 3 && vue.viesDealer() <= 1);
        if (possede(vue, Objet.CIGARETTES) && !fumerRefusee) {
            ValeurRound.Etat soigne = e.avec(e.reelles(), e.blanches(), e.viesDealer() + 1, e.viesJoueur(),
                    true, e.sauteDealer(), e.sauteJoueur());
            liste.add(new Candidat(new ActionIA.UtiliserObjet(Objet.CIGARETTES), meilleure(tirs(soigne, connue, degats))));
        }
        if (liste.isEmpty()) {
            liste.add(new Candidat(tirDeRepli(vue), 0));
        }
        return liste;
    }

    private static boolean possede(VueIA vue, Objet objet) {
        return vue.objetsDealer().contains(objet);
    }
}
