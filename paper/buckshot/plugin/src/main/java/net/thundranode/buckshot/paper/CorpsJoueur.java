package net.thundranode.buckshot.paper;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.api.trait.Trait;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;

/**
 * Le corps du joueur pour les spectateurs. Passe en mode spectateur pour sa
 * cinematique de chute, le joueur disparait net de leur ecran : ce PNJ
 * temporaire a son skin reste debout a sa place pendant la sentence, puis
 * s'effondre (pose couchee du client, figee) et reste au sol.
 */
public final class CorpsJoueur {

    private final NPCRegistry registre;
    private final org.bukkit.plugin.Plugin plugin;
    private NPC npc;

    /** Multi-tables : un registre par corps, meme raison que pour le dealer. */
    private static final java.util.concurrent.atomic.AtomicInteger COMPTEUR_REGISTRE =
            new java.util.concurrent.atomic.AtomicInteger();

    public CorpsJoueur(org.bukkit.plugin.Plugin plugin) {
        this.plugin = plugin;
        // Registre a part : celui du dealer fait deregisterAll() a chaque
        // reapparition, ce qui faucherait le corps en pleine scene.
        this.registre = CitizensAPI.createInMemoryNPCRegistry(
                "buckshot-corps-" + COMPTEUR_REGISTRE.incrementAndGet());
    }

    public void apparaitre(Player joueur, Location lieu) {
        retirer();
        npc = registre.createNPC(EntityType.PLAYER, joueur.getName());
        npc.setProtected(true);
        npc.data().setPersistent(NPC.Metadata.COLLIDABLE, false);
        npc.data().setPersistent(NPC.Metadata.NAMEPLATE_VISIBLE, false);
        npc.setUseMinecraftAI(false);
        appliquerSkin(joueur);
        npc.spawn(lieu);
        // Cache au mourant : sa camera tombe exactement la ou git le corps.
        entite().ifPresent(corps -> joueur.hideEntity(plugin, corps));
    }

    /**
     * Effondrement : bascule instantanee en pose couchee, tete pointee vers
     * {@code lacetTete} (le yaw de la table : le corps s'allonge droit face
     * a elle). Sans lit, le client oriente un corps SLEEPING d'apres son
     * body yaw seul -- LivingEntityRenderer 1.21.11 decompile applique
     * Y(bodyYaw), Z(90), Y(270), soit une tete vers le yaw (90 - bodyYaw).
     * Herite du yaw du joueur mort, le corps se couchait de travers.
     */
    public void coucher(float lacetTete) {
        entite().ifPresent(corps -> {
            float lacet = 90.0f - lacetTete;
            corps.setRotation(lacet, 0);
            corps.setBodyYaw(lacet);
            corps.setPose(Pose.SLEEPING, true);
        });
    }

    public void retirer() {
        if (npc != null) {
            npc.destroy();
            npc = null;
        }
    }

    public java.util.Optional<Player> entite() {
        return npc != null && npc.isSpawned() && npc.getEntity() instanceof Player corps
                ? java.util.Optional.of(corps) : java.util.Optional.empty();
    }

    /**
     * Skin du joueur, lu sur son profil en ligne (texture signee comprise).
     * SkinTrait par reflexion, comme {@link DrDonuttNpc#changerPeau}.
     */
    @SuppressWarnings("unchecked")
    private void appliquerSkin(Player joueur) {
        String valeur = null;
        String signature = null;
        for (var propriete : joueur.getPlayerProfile().getProperties()) {
            if (propriete.getName().equals("textures")) {
                valeur = propriete.getValue();
                signature = propriete.getSignature();
            }
        }
        if (valeur == null) return;
        try {
            Class<? extends Trait> type = (Class<? extends Trait>) Class
                    .forName("net.citizensnpcs.trait.SkinTrait").asSubclass(Trait.class);
            Trait trait = npc.getOrAddTrait(type);
            type.getMethod("setSkinPersistent", String.class, String.class, String.class)
                    .invoke(trait, joueur.getName(), signature, valeur);
        } catch (ReflectiveOperationException | RuntimeException erreur) {
            Bukkit.getLogger().warning("[Buckshot] skin du corps impossible : " + erreur);
        }
    }
}
