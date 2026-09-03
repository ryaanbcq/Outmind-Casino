package net.thundranode.buckshot.paper;

import net.kyori.adventure.text.Component;
import net.thundranode.buckshot.jeu.Acteur;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;

interface ScenePartie {
    boolean configuree();
    boolean aPortee(Player joueur);
    void installerJoueur(Player joueur);
    void montrerFusil(Acteur acteur, Player joueur);
    void reposerFusil(Player joueur);
    void montrerObjetDealer(ItemStack item);
    void leverBrasDealer();
    void baisserBrasDealer();
    void dealerRegarde(org.bukkit.Location lieu);
    void montrerViesDealer(int vies, int viesClope, int plafond);
    void masquerViesDealer();
    void montrerViesJoueur(Player joueur, int vies, int viesClope, int plafond);
    void masquerViesJoueur();
    void poserCorpsChute(Player joueur);
    void coucherCorpsChute();
    void poserCage();
    void retirerCage();
    void reparerSiNecessaire();
    void sangChuteJoueur(org.bukkit.Location sol);
    void masquerChuteJoueur();
    void peauDealerPourRound(int round);
    void viserDealer(String pose);
    void finViseeDealer();

    /** Meme posture reglee pour un vrai joueur, vue par les autres. */
    void montrerPose(org.bukkit.entity.Player joueur, String pose);

    /**
     * Cigarette fumee en accelere a la bouche du porteur, puis megot au sol.
     * {@code fin} est appele quand le megot touche le feutre.
     */
    void fumerCigarette(Player porteur, boolean masquerAuPorteur, Runnable fin);
    /**
     * Le joueur boit sa biere : bouteille en main, geste de bras vanilla
     * (visible par tous, Bedrock compris) pendant {@code ticks}, puis
     * {@code fin}. La bouteille rendue est celle de sa hotbar.
     */
    void boireBiere(Player porteur, int ticks, Runnable fin);
    /**
     * Bras tendus (pose arbalete) sur l'humain qui inspecte : le fusil en
     * main porte deja le composant consumable, il suffit de l'"utiliser"
     * cote serveur. {@code false} rend les bras.
     */
    void brasInspection(Player porteur, boolean leves);

    void cacherPose(org.bukkit.entity.Player joueur);

    /**
     * Tableau de mort de DrDonutt, installe pendant l'ecran noir : corps
     * couche sur la table tete cote joueur, sang partout sur le feutre.
     * Reste en place jusqu'a {@link #ranimerDealer}.
     */
    void mortDealer();

    /** Vrai entre {@link #mortDealer} et {@link #ranimerDealer}. */
    boolean dealerMort();

    /** Mallette de gains posee sur le feutre a la victoire. */
    void poserMalletteVictoire();

    /**
     * Remet le dealer debout a son poste et retire la mallette de victoire.
     * Le sang du tableau part avec sangPourRound(1) au nettoyage.
     */
    void ranimerDealer();

    /** Bascule sur le theme du round final a partir du round 3. */
    void musiquePourRound(int round);

    /**
     * Situations musicales, de la plus calme a la plus tendue. L'ordre du
     * calcul compte : le dernier coeur bat tout le reste.
     */
    enum Musique { CALME, FINALE, FINALE_TOUCHEE, DERNIER_COEUR, DERNIER_COEUR_FINAL }

    /**
     * Bascule le theme selon la situation de jeu (workflow user 2026-08-27 :
     * une OST dediee au dernier coeur, une autre des le premier tir encaisse
     * au round final). Sans effet si la piste voulue joue deja.
     */
    void musiqueSituation(Musique situation);

    /** Eclaboussures sur le feutre : rien au round 1, puis de plus en plus. */
    void sangPourRound(int round);

    /**
     * Menottes portees (pose {@code pose.menottes}) posees ou retirees selon
     * l'etat des deux participants : claquement a la pose, chaine cassee au
     * retrait, suivi du cap du porteur entre les deux. Idempotent, a appeler
     * apres chaque action traitee.
     */
    void synchroniserMenottes(boolean dealerMenotte, boolean joueurMenotte, Player humain,
                              Player humain2);

    /** Solo : le camp DEALER est le PNJ. */
    default void synchroniserMenottes(boolean dealerMenotte, boolean joueurMenotte, Player humain) {
        synchroniserMenottes(dealerMenotte, joueurMenotte, humain, null);
    }
    /**
     * Douille ejectee par {@code acteur} : petit arc depuis sa main jusqu'au
     * feutre, culbute en vol, repose un moment puis disparait. Sert au tir
     * (a la pompe) comme a l'ejection a la biere.
     */
    void ejecterCartouche(Acteur acteur, boolean reelle, Player humain);

    /**
     * Pose sur le feutre les deux objets du choix de relance : le fusil a la
     * gauche du joueur, la mallette a sa droite.
     */
    void montrerChoixRelance();

    /**
     * Retire de la table l'objet que le joueur vient de prendre en main.
     * {@code type} est le marquage de l'item tenu ({@code relance:continuer},
     * {@code relance:cashout}) ou null quand la main est vide : les deux
     * objets sont alors sur le feutre.
     */
    void choixRelanceEnMain(String type);

    /** Retire les deux objets du choix de relance. Idempotent. */
    void masquerChoixRelance();

    /**
     * Pose le fusil sur le feutre devant le joueur, avec sa zone cliquable :
     * c'est en le ramassant qu'il recupere ses items de tir. Idempotent.
     */
    void poserFusilAPrendre();

    /**
     * Pose le fusil sur le feutre COTE DEALER, sans zone cliquable : le
     * joueur vient de finir son tour, DrDonutt le ramassera en visant.
     */
    void poserFusilDealer();

    /** Retire le fusil pose sur la table, quel que soit le cote. Idempotent. */
    void retirerFusilAPrendre();

    /** Vrai si cette entite est la zone cliquable du fusil a prendre. */
    boolean estPriseFusil(org.bukkit.entity.Entity entite);

    /** Joueurs a moins de {@code rayon} blocs du centre de la table. */
    java.util.List<Player> spectateurs(double rayon);

    void annoncer(Player joueur, Component message);
    void demarrerMusique(Player joueur);
    void arreterMusique(Player joueur);

    /**
     * Deux palettes de defibrillateur devant les yeux du joueur, qui
     * s'ecartent chacune de son cote jusqu'a quitter son champ de vision.
     * A appeler quand l'ecran noir se leve : sous le noir, rien ne se voit.
     */
    void defibrillation(Player joueur);
    Optional<Player> dealerEntite();
}
