package net.thundranode.buckshot;

import net.thundranode.buckshot.jeu.TypeCartouche;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Pousse chaque pose au client et réinjecte l'item dans la main pour forcer le
 * paquet d'inventaire correspondant.
 */
public final class Animateur {

    private final Plugin plugin;
    private final Etats etats;
    /** Une seule sequence a la fois par joueur, sinon deux tâches se marchent dessus. */
    private final Map<UUID, BukkitRunnable> enCours = new HashMap<>();

    public Animateur(Plugin plugin, Etats etats) {
        this.plugin = plugin;
        this.etats = etats;
    }

    /**
     * Joue un etat sur l'item tenu.
     *
     * @return false si l'etat est pilote par le client, auquel cas il a ete
     *         simplement pose et c'est au joueur de maintenir le clic droit.
     */
    public boolean jouer(Player joueur, String nom) {
        Etats.Etat etat = etats.get(nom);
        demarrerSequence(joueur, nom, etat, false, false);
        return true;
    }

    /** Force l'usage actif pour jouer la séquence sans clic maintenu. */
    public void jouerForce(Player joueur, String nom) {
        Etats.Etat etat = etats.get(nom);
        if (etat == null) {
            throw new IllegalArgumentException("etat inconnu : " + nom);
        }
        demarrerSequence(joueur, nom, etat, false, false);
    }

    /**
     * Tir a blanc : meme sequence d'armes que {@code fire}, mais un simple clic
     * sec a la place de la detonation -- ni flamme, ni fumee, ni recul.
     */
    public void jouerTirABlanc(Player joueur) {
        Etats.Etat etat = etats.get("fire");
        if (etat == null) {
            throw new IllegalArgumentException("etat inconnu : fire");
        }
        demarrerSequence(joueur, "fire", etat, false, true);
    }

    /** Joue la montée en joue puis conserve la dernière pose jusqu'au tir. */
    public void jouerEtTenir(Player joueur, String nom) {
        Etats.Etat etat = etats.get(nom);
        if (etat == null) throw new IllegalArgumentException("etat inconnu : " + nom);
        demarrerSequence(joueur, nom, etat, true, false);
    }

    /**
     * Inspection de la chambre : le fusil monte devant les yeux, s'y tient le
     * temps demande, puis redescend.
     *
     * <p>La sequence brute d'{@code inspect} ne dure que huit ticks et repasse
     * aussitot en {@code hold} : la loupe n'affichait qu'un eclair. Les images
     * de descente n'existent pas dans le pack, on rejoue donc la montee a
     * l'envers -- meme resultat visuel, aucun rebuild de pack.
     */
    public void jouerInspection(Player joueur, int tenueTicks, String nom) {
        Etats.Etat etat = etats.get(nom);
        if (etat == null) throw new IllegalArgumentException("etat inconnu : " + nom);
        SequenceAnimation sequence = SequenceAnimation.creer(nom, etat);
        java.util.List<String> images = new java.util.ArrayList<>();
        for (String image : sequence.images()) {
            for (int i = 0; i < sequence.ticksParImage(); i++) images.add(image);
        }
        int montee = images.size();
        String sommet = images.get(montee - 1);
        for (int i = 0; i < Math.max(0, tenueTicks); i++) images.add(sommet);
        for (int i = montee - 1; i >= 0; i--) images.add(images.get(i));

        annuler(joueur);
        final int debutTenue = montee;
        final int finTenue = montee + Math.max(0, tenueTicks);
        var tache = new BukkitRunnable() {
            int tick = 0;

            @Override
            public void run() {
                if (tick >= images.size()) {
                    poserEnMain(joueur, "hold");
                    enCours.remove(joueur.getUniqueId());
                    cancel();
                    return;
                }
                poserEnMain(joueur, images.get(tick));
                if (tick == debutTenue) {
                    joueur.getWorld().playSound(joueur.getLocation(),
                            Sound.ITEM_SPYGLASS_USE, SoundCategory.PLAYERS, 0.7f, 1.3f);
                } else if (tick == finTenue) {
                    joueur.getWorld().playSound(joueur.getLocation(),
                            Sound.ITEM_SPYGLASS_STOP_USING, SoundCategory.PLAYERS, 0.7f, 1.3f);
                }
                tick++;
            }
        };
        enCours.put(joueur.getUniqueId(), tache);
        tache.runTaskTimer(plugin, 0L, 1L);
    }

    /** Duree totale de {@link #jouerInspection}, retour en {@code hold} compris. */
    public int dureeInspection(int tenueTicks) {
        return dureeTicks("inspect") * 2 + Math.max(0, tenueTicks) + 1;
    }

    /**
     * Etat d'inspection montrant la charge annoncee.
     *
     * <p>{@code null} laisse la cartouche neutre : c'est ce que voit le joueur
     * quand c'est le dealer qui regarde sa chambre.
     */
    public static String inspection(TypeCartouche type) {
        if (type == null) return "inspect";
        return type == TypeCartouche.REELLE ? "inspect_rouge" : "inspect_blanche";
    }

    private void demarrerSequence(Player joueur, String nom, Etats.Etat etat,
                                  boolean tenirDernierePose, boolean tirABlanc) {
        SequenceAnimation sequence = SequenceAnimation.creer(nom, etat);

        annuler(joueur);

        var tache = new BukkitRunnable() {
            int tick = 0;
            final float lacetBase = joueur.getLocation().getYaw();
            final float tangageBase = joueur.getLocation().getPitch();

            @Override
            public void run() {
                int image = tick / sequence.ticksParImage();
                if (image >= sequence.images().size()) {
                    // La visee tenue garde simplement sa derniere image : on
                    // arrete de pousser.
                    //
                    // Le tremblement continu a ete tente deux fois (d8bb054 puis
                    // 0f4ec53) en reposant une image _t a chaque tick. Ca casse
                    // le jeu : poserEnMain remplace la pile tenue, le client
                    // rejoue son animation d'equipement en boucle, le fusil
                    // descend sans jamais remonter, et le clic droit n'a plus le
                    // temps d'enregistrer un usage. Toute animation soutenue
                    // pilotee par le serveur retombera sur le meme mur.
                    if (!tenirDernierePose) poserEnMain(joueur, "hold");
                    if ("fire".equals(nom)) {
                        joueur.setRotation(lacetBase, tangageBase);
                    }
                    enCours.remove(joueur.getUniqueId());
                    cancel();
                    return;
                }
                poserEnMain(joueur, sequence.images().get(image));
                if ("fire".equals(nom)) {
                    if (image == 0 && tick == 0) {
                        if (tirABlanc) clicABlanc(joueur);
                        else coupDeFeu(joueur);
                    }
                    if (!tirABlanc) secousse(joueur, lacetBase, tangageBase, image, etat.frames());
                } else if ("reload".equals(nom) && image % 7 == 0) {
                    // Trois accents sonores aux etapes de la course. Un clic
                    // par image transformait la nouvelle sequence fluide en
                    // mitraillette mecanique.
                    joueur.playSound(joueur.getLocation(), Sound.BLOCK_LEVER_CLICK,
                            SoundCategory.PLAYERS, 0.6f, 0.72f + image * 0.02f);
                }
                tick++;
            }
        };
        enCours.put(joueur.getUniqueId(), tache);
        tache.runTaskTimer(plugin, 0L, 1L);
    }

    public void annuler(Player joueur) {
        BukkitRunnable t = enCours.remove(joueur.getUniqueId());
        if (t != null) {
            t.cancel();
        }
    }

    private void poserEnMain(Player joueur, String etat) {
        ItemStack item = joueur.getInventory().getItemInMainHand();
        Fusil.poser(item, etat);
        joueur.getInventory().setItemInMainHand(item);
    }

    public int dureeTicks(String nom) {
        Etats.Etat etat = etats.get(nom);
        if (etat == null) {
            throw new IllegalArgumentException("etat inconnu : " + nom);
        }
        return etat.dureeTicks();
    }

    public void annulerTous() {
        for (UUID joueurId : java.util.Set.copyOf(enCours.keySet())) {
            Player joueur = org.bukkit.Bukkit.getPlayer(joueurId);
            if (joueur != null) annuler(joueur);
            else enCours.remove(joueurId).cancel();
        }
    }

    private void coupDeFeu(Player joueur) {
        Location oeil = joueur.getEyeLocation();
        Location bouche = oeil.clone().add(oeil.getDirection().multiply(0.9));
        joueur.getWorld().spawnParticle(Particle.FLAME, bouche, 12, 0.05, 0.05, 0.05, 0.02);
        joueur.getWorld().spawnParticle(Particle.LARGE_SMOKE, bouche, 20, 0.1, 0.1, 0.1, 0.01);
        joueur.getWorld().playSound(bouche, Sound.ENTITY_GENERIC_EXPLODE,
                SoundCategory.PLAYERS, 0.9f, 1.6f);
        joueur.getWorld().playSound(bouche, Sound.ITEM_FIRECHARGE_USE,
                SoundCategory.PLAYERS, 1.0f, 0.8f);
        joueur.swingMainHand();
    }

    private void clicABlanc(Player joueur) {
        Location oeil = joueur.getEyeLocation();
        Location bouche = oeil.clone().add(oeil.getDirection().multiply(0.9));
        joueur.getWorld().playSound(bouche, "rr:shot.blanc",
                SoundCategory.PLAYERS, 1.0f, 1.0f);
        joueur.swingMainHand();
    }

    /** Le recul part fort a la premiere image puis retombe vers la visee de depart. */
    private void secousse(Player joueur, float lacet, float tangage, int image, int total) {
        double reste = 1.0 - (double) image / total;
        var rng = ThreadLocalRandom.current();
        joueur.setRotation(
                lacet + (float) (rng.nextDouble(-1.0, 1.0) * 1.6 * reste),
                tangage - (float) (2.6 * reste));
    }
}
