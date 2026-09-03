package net.thundranode.buckshot.paper;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.api.trait.Trait;
import net.thundranode.buckshot.Fusil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;

public final class DrDonuttNpc {

    private final NPCRegistry registre;
    private final org.bukkit.plugin.Plugin plugin;
    private NPC npc;
    private boolean leverBrasSignale;
    private boolean holoSignale;
    private org.bukkit.scheduler.BukkitTask regard;
    /** Place attitree du dealer : le suivi l'y ramene s'il en derive. */
    private Location poste;
    private volatile double pitchAnimation;
    /** Vrai entre mourir() et ranimer() : le re-ancrage laisse le corps au sol. */
    private volatile boolean mort;
    /** Cap fige du dealer, vers la place du joueur, calcule a l'apparition. */
    private volatile double lacetTable;

    /** Un registre PAR dealer : Citizens ecrase l'entree de meme nom, et le
     * registre remplace laisse ses PNJ orphelins (multi-tables 2026-08-29). */
    private static final java.util.concurrent.atomic.AtomicInteger COMPTEUR_REGISTRE =
            new java.util.concurrent.atomic.AtomicInteger();

    public DrDonuttNpc(org.bukkit.plugin.Plugin plugin) {
        if (!CitizensAPI.hasImplementation()) {
            throw new IllegalStateException("Citizens n'est pas initialise");
        }
        this.plugin = plugin;
        registre = CitizensAPI.createInMemoryNPCRegistry(
                "buckshot-" + COMPTEUR_REGISTRE.incrementAndGet());
    }

    public void apparaitre(Location lieu, Location aRegarder) {
        registre.deregisterAll();
        // Balaye les orphelins des instances precedentes : chaque reload du
        // plugin (PlugManX) cree un nouveau registre en memoire, et le
        // deregisterAll() de l'ancien ne depeuple pas un chunk decharge --
        // les dealers restaient alors empiles sur place (constat user
        // 2026-08-27, quatre DrDonutt l'un sur l'autre).
        for (org.bukkit.entity.Entity entite : lieu.getWorld()
                .getNearbyEntities(lieu, 4, 4, 4)) {
            if (entite instanceof org.bukkit.entity.Player clone
                    && clone.getName().equals("DrDonutt")) {
                entite.remove();
            }
        }
        npc = registre.createNPC(EntityType.PLAYER, "DrDonutt");
        npc.setProtected(true);
        // Protege ne veut pas dire inamovible : un joueur qui marche dans le
        // PNJ le pousse quand meme. Sans collision, plus de bousculade.
        npc.data().setPersistent(NPC.Metadata.COLLIDABLE, false);
        npc.setUseMinecraftAI(false);
        appliquerSkinDrDonutt();
        if (!npc.spawn(lieu)) {
            throw new IllegalStateException("apparition de DrDonutt impossible");
        }
        npc.faceLocation(aRegarder);
        // Remplace LookClose (2026-08-23) : LookClose composait aussi le
        // PITCH du regard, et le dealer piquait du nez des que le joueur
        // s'approchait. Puis (meme jour, demande utilisateur) le suivi du
        // joueur le plus proche a saute a son tour : les bras crossbow sont
        // rendus par le client en suivant la TETE, donc chaque rotation
        // baladait les bras pendant que l'habillage ItemDisplay (menottes,
        // fusil) restait cale — le dealer est desormais VERROUILLE face a
        // la place du joueur, pitch fige a pitchAnimation.
        var difference = aRegarder.toVector().subtract(lieu.toVector());
        lacetTable = Math.toDegrees(Math.atan2(-difference.getX(), difference.getZ()));
        poste = lieu.clone();
        demarrerRegard();
    }

    /** Pitch impose au dealer (degres) ; 0 = tete droite. */
    public void pitchAnimation(double degres) {
        this.pitchAnimation = degres;
    }

    private void demarrerRegard() {
        arreterRegard();
        // Reapplique le cap fige toutes les 2 ticks : Citizens et les
        // animations peuvent tourner la tete, la table la remet en place.
        regard = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            Player corps = entite().orElse(null);
            if (corps == null) return;
            // Un mort ne se redresse pas : le re-ancrage remettrait le corps
            // debout au poste en moins de 2 ticks.
            if (mort) return;
            // Re-ancrage : quoi qu'il arrive (poussee residuelle, explosion,
            // piston), le dealer revient a son poste en moins de 2 ticks.
            if (poste != null && corps.getLocation().distanceSquared(poste) > 0.01) {
                Location retour = poste.clone();
                retour.setYaw((float) lacetTable);
                retour.setPitch((float) pitchAnimation);
                corps.teleport(retour);
            }
            corps.setRotation((float) lacetTable, (float) pitchAnimation);
        }, 1L, 2L);
    }

    private void arreterRegard() {
        if (regard != null) {
            regard.cancel();
            regard = null;
        }
    }


    public Optional<Player> entite() {
        return npc != null && npc.isSpawned() && npc.getEntity() instanceof Player joueur
                ? Optional.of(joueur) : Optional.empty();
    }

    public boolean est(NPC autre) {
        return npc != null && npc.getUniqueId().equals(autre.getUniqueId());
    }

    public void equiperFusil() {
        entite().ifPresent(joueur -> {
            joueur.clearActiveItem();
            joueur.getInventory().setItemInMainHand(Fusil.creer());
        });
        masquerMainBedrock();
    }

    /**
     * Met DrDonutt en position d'usage, ce qui leve reellement son bras.
     *
     * <p>Sans ca l'arme pivotait seule pendant que le bras restait le long du
     * corps : les poses troisieme personne sont calculees en supposant le bras
     * leve, donc c'est cet appel qui les rend justes plutot que magiques.
     * L'item porte le composant consumable, c'est ce qui autorise l'usage.
     */
    public void leverBras() {
        entite().ifPresent(joueur -> {
            try {
                joueur.startUsingItem(EquipmentSlot.HAND);
            } catch (RuntimeException erreur) {
                // Purement cosmetique : si Citizens refuse de mettre son entite
                // en usage, la partie doit continuer avec le bras baisse plutot
                // que se figer au milieu du tour du dealer.
                if (leverBrasSignale) return;
                leverBrasSignale = true;
                Bukkit.getLogger().warning(
                        "[Buckshot] bras de DrDonutt non levable : " + erreur);
            }
        });
    }

    public void baisserBras() {
        entite().ifPresent(Player::clearActiveItem);
    }

    /**
     * Mort : le corps DISPARAIT (demande user 2026-08-27) -- c'est sa tete,
     * posee par la mise en scene sur la table, qui raconte le coup. Les
     * poses couchees (SLEEP Citizens puis SLEEPING Paper) rendaient mal :
     * un corps entier allonge sur le feutre cassait la scene.
     */
    public void mourir() {
        if (mort) return;
        mort = true;
        entite().ifPresent(Player::clearActiveItem);
        if (npc != null && npc.isSpawned()) npc.despawn();
    }

    public boolean estMort() {
        return mort;
    }

    /** Refait apparaitre le dealer a son poste, pret pour la partie suivante. */
    public void ranimer() {
        if (!mort) return;
        mort = false;
        if (npc != null && !npc.isSpawned() && poste != null) {
            Location retour = poste.clone();
            retour.setYaw((float) lacetTable);
            retour.setPitch((float) pitchAnimation);
            npc.spawn(retour);
        }
    }

    /** Oriente DrDonutt vers un point, pour que le canon suive vraiment. */
    public void regarder(Location lieu) {
        if (npc != null && npc.isSpawned()) npc.faceLocation(lieu);
    }

    /** Met un objet quelconque dans la main de DrDonutt, le temps qu'on veut. */
    public void equiperObjet(ItemStack item) {
        entite().ifPresent(joueur -> joueur.getInventory().setItemInMainHand(item));
        masquerMainBedrock();
    }

    public void retirerFusil() {
        entite().ifPresent(joueur -> {
            joueur.clearActiveItem();
            joueur.getInventory().setItemInMainHand(ItemStack.empty());
        });
    }

    /**
     * La main du dealer, cote Bedrock, est TOUJOURS vide. Ses accessoires
     * (fusil "cache", arbalete de pose chargee, cigarette) sont des habillages
     * Java : chez Geyser ils sortaient en sprite extrude vu par la tranche
     * ("un baton dans la main") ou en crossbow vanilla pendant la visee
     * (constats user 2026-08-29). Un paquet d'equipement cible par client
     * Bedrock re-vide la main, sans toucher ce que voient les clients Java.
     */
    public void masquerMainBedrock() {
        // Deux ticks plus tard : le vrai equipement vient d'etre pose et son
        // paquet part en fin de tick, il ecraserait un masque immediat.
        Bukkit.getScheduler().runTaskLater(plugin, () -> entite().ifPresent(dealer -> {
            for (Player observateur : Bukkit.getOnlinePlayers()) {
                if (EcouteurPartie.estBedrock(observateur)) {
                    observateur.sendEquipmentChange(dealer, EquipmentSlot.HAND,
                            ItemStack.empty());
                }
            }
        }), 2L);
    }

    /** Le meme masque, pour UN client Bedrock (re-assertion periodique). */
    void masquerMainPour(Player observateur) {
        entite().ifPresent(dealer -> observateur.sendEquipmentChange(
                dealer, EquipmentSlot.HAND, ItemStack.empty()));
    }

    /**
     * Ecrit les vies de DrDonutt en coeurs, une ligne au-dessus de son pseudo.
     *
     * <p>Passe par le HologramTrait de Citizens, atteint par reflexion comme le
     * SkinTrait : l'API publique de Citizens ne l'expose pas. Purement
     * cosmetique, donc un echec se signale une fois et la partie continue.
     */
    public void afficherVies(int vies, int viesClope, int plafond) {
        if (npc == null || !npc.isSpawned()) return;
        int noirs = Math.max(0, Math.min(viesClope, vies));
        int rouges = Math.max(0, vies - noirs);
        StringBuilder texte = new StringBuilder("§c");
        texte.append("❤".repeat(rouges));
        // Les vies regagnees a la cigarette sont noires, comme celles du
        // joueur dans sa barre de vie.
        if (noirs > 0) texte.append("§0").append("❤".repeat(noirs));
        if (vies < plafond) {
            texte.append("§8").append("❤".repeat(plafond - Math.max(0, vies)));
        }
        hologramme(trait -> {
            Class<?> type = trait.getClass();
            java.util.List<?> lignes =
                    (java.util.List<?>) type.getMethod("getLines").invoke(trait);
            if (lignes.isEmpty()) {
                type.getMethod("addLine", String.class).invoke(trait, texte.toString());
            } else {
                type.getMethod("setLine", int.class, String.class)
                        .invoke(trait, 0, texte.toString());
            }
        });
    }

    public void masquerVies() {
        if (npc == null || !npc.isSpawned()) return;
        hologramme(trait -> trait.getClass().getMethod("clear").invoke(trait));
    }

    @SuppressWarnings("unchecked")
    private void hologramme(GesteHologramme geste) {
        try {
            Class<? extends Trait> type = (Class<? extends Trait>) Class
                    .forName("net.citizensnpcs.trait.HologramTrait").asSubclass(Trait.class);
            geste.appliquer(npc.getOrAddTrait(type));
        } catch (ReflectiveOperationException | RuntimeException erreur) {
            if (holoSignale) return;
            holoSignale = true;
            Bukkit.getLogger().warning(
                    "[Buckshot] coeurs de DrDonutt indisponibles : " + erreur);
        }
    }

    private interface GesteHologramme {
        void appliquer(Trait trait) throws ReflectiveOperationException;
    }

    /**
     * Applique une peau signee Mojang (valeur + signature, obtenues via
     * MineSkin). Peau par defaut : {@link #peauParDefaut()}.
     */
    public void changerPeau(String valeur, String signature) {
        if (npc == null || !npc.isSpawned()) return;
        try {
            Class<? extends Trait> type = (Class<? extends Trait>) Class
                    .forName("net.citizensnpcs.trait.SkinTrait").asSubclass(Trait.class);
            Trait trait = npc.getOrAddTrait(type);
            type.getMethod("setSkinPersistent", String.class, String.class, String.class)
                    .invoke(trait, "buckshot-round", signature, valeur);
        } catch (ReflectiveOperationException | RuntimeException erreur) {
            Bukkit.getLogger().warning("[Buckshot] changement de peau impossible : " + erreur);
        }
    }

    public void peauParDefaut() {
        if (npc == null || !npc.isSpawned()) return;
        appliquerSkinDrDonutt();
    }

    public void fermer() {
        arreterRegard();
        registre.deregisterAll();
        npc = null;
    }

    @SuppressWarnings("unchecked")
    private void appliquerSkinDrDonutt() {
        try {
            Class<? extends Trait> type = (Class<? extends Trait>) Class
                    .forName("net.citizensnpcs.trait.SkinTrait").asSubclass(Trait.class);
            Trait trait = npc.getOrAddTrait(type);
            type.getMethod("setSkinName", String.class, boolean.class)
                    .invoke(trait, "DrDonutt", true);
        } catch (ReflectiveOperationException erreur) {
            throw new IllegalStateException("SkinTrait Citizens indisponible", erreur);
        }
    }
}
