package net.thundranode.buckshot.paper;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;

/**
 * Pont vers l'economie Vault, par reflexion : l'API n'est pas sur le
 * classpath de compilation et sa surface utile tient en trois methodes.
 *
 * <p>Sur ce serveur, l'economie vivante est le fork vault-1.7.0-java21 et le
 * pont Outmind (OutMindLink) transforme chaque delta de solde d'un joueur en
 * ligne en mise ou gain casino : debiter la mise et crediter les gains ICI
 * suffit, le grand livre Discord suit tout seul, comme pour NitroCasino.
 */
public final class Banque {

    private Object economie;
    private Method solde;
    private Method retirer;
    private Method deposer;
    private Method succes;

    /** Vrai si une economie Vault est enregistree. A appeler a l'enable. */
    public boolean initialiser() {
        try {
            Class<?> type = Class.forName("net.milkbowl.vault.economy.Economy");
            var enregistrement = Bukkit.getServicesManager().getRegistration(type);
            if (enregistrement == null) return false;
            economie = enregistrement.getProvider();
            solde = type.getMethod("getBalance", OfflinePlayer.class);
            retirer = type.getMethod("withdrawPlayer", OfflinePlayer.class, double.class);
            deposer = type.getMethod("depositPlayer", OfflinePlayer.class, double.class);
            succes = Class.forName("net.milkbowl.vault.economy.EconomyResponse")
                    .getMethod("transactionSuccess");
            return true;
        } catch (ReflectiveOperationException | RuntimeException erreur) {
            Bukkit.getLogger().warning("[Buckshot] economie Vault indisponible : " + erreur);
            economie = null;
            return false;
        }
    }

    boolean disponible() {
        return economie != null;
    }

    long solde(Player joueur) {
        try {
            return (long) Math.floor((double) solde.invoke(economie, joueur));
        } catch (ReflectiveOperationException | RuntimeException erreur) {
            Bukkit.getLogger().warning("[Buckshot] lecture de solde impossible : " + erreur);
            return 0;
        }
    }

    boolean debiter(Player joueur, long montant) {
        return transaction(retirer, joueur, montant);
    }

    boolean crediter(Player joueur, long montant) {
        return transaction(deposer, joueur, montant);
    }

    private boolean transaction(Method operation, Player joueur, long montant) {
        if (montant <= 0) return false;
        try {
            Object reponse = operation.invoke(economie, joueur, (double) montant);
            return (boolean) succes.invoke(reponse);
        } catch (ReflectiveOperationException | RuntimeException erreur) {
            Bukkit.getLogger().warning("[Buckshot] transaction impossible : " + erreur);
            return false;
        }
    }
}
