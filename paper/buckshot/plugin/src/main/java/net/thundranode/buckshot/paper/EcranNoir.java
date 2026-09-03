package net.thundranode.buckshot.paper;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class EcranNoir {

    private final JavaPlugin plugin;
    private final Map<UUID, BukkitTask> fins = new HashMap<>();

    public EcranNoir(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void afficher(Player joueur, int ticks, Runnable apres) {
        nettoyer(joueur);
        if (EcouteurPartie.estBedrock(joueur)) {
            // Pas de title glyphe pour Bedrock : U+E000 y est le glyphe
            // systeme "bouton A de manette" (constat user 2026-08-29, un
            // joystick s'affichait au tir). Le noir vient des effets, que
            // Bedrock rend tous les deux.
            joueur.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS,
                    ticks + 20, 0, false, false, false));
        } else {
            Component glyphe = Component.text("\uE000").font(Key.key("rr", "blackout"));
            joueur.showTitle(Title.title(glyphe, Component.empty(),
                    Title.Times.times(Duration.ZERO, Duration.ofMillis(ticks * 50L), Duration.ZERO)));
        }
        joueur.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, ticks + 20, 1,
                false, false, false));
        BukkitTask fin = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            nettoyer(joueur);
            if (joueur.isOnline()) apres.run();
        }, ticks);
        fins.put(joueur.getUniqueId(), fin);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (fins.containsKey(joueur.getUniqueId())) {
                nettoyer(joueur);
            }
        }, ticks + 10L);
    }

    public void nettoyer(Player joueur) {
        BukkitTask fin = fins.remove(joueur.getUniqueId());
        if (fin != null) fin.cancel();
        joueur.clearTitle();
        joueur.removePotionEffect(PotionEffectType.DARKNESS);
        joueur.removePotionEffect(PotionEffectType.BLINDNESS);
    }
}
