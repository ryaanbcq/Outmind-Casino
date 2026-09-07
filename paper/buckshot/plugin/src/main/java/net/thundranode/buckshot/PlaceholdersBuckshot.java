package net.thundranode.buckshot;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

/**
 * Placeholders du casino Buckshot, pour les menus DeluxeMenus (2026-08-29).
 *
 * Les tables sont adressees par COORDONNEES (monde + x + z approximatifs du
 * centre, la plus proche a moins de 50 blocs repond) et pas par index : la
 * liste des tables se reordonne au gre des creer/retirer, les salles non.
 *
 *   %buckshot_tables%                     nombre de tables
 *   %buckshot_libres%                     nombre de tables sans partie en cours
 *   %buckshot_free_<monde>_<x>_<z>%       yes / no  (pour les view_requirements)
 *   %buckshot_status_<monde>_<x>_<z>%     FREE / BUSY
 *   %buckshot_vies%                       coeurs du joueur s'il est assis a une table, sinon vide
 *   %buckshot_belowname%                  ligne sous le pseudo (TAB belowname, 2026-09-06) :
 *                                         ses coeurs en partie, sinon le moneytag habituel
 */
public final class PlaceholdersBuckshot extends PlaceholderExpansion {

    private final BuckshotPlugin plugin;

    public PlaceholdersBuckshot(BuckshotPlugin plugin) {
        this.plugin = plugin;
    }

    @Override public @NotNull String getIdentifier() { return "buckshot"; }
    @Override public @NotNull String getAuthor() { return "OutMind"; }
    @Override public @NotNull String getVersion() { return plugin.getPluginMeta().getVersion(); }
    @Override public boolean persist() { return true; }

    @Override
    public String onRequest(OfflinePlayer joueur, @NotNull String params) {
        if (params.equalsIgnoreCase("vies")) {
            String vies = joueur == null ? null : plugin.ligneVies(joueur.getUniqueId());
            return vies == null ? "" : vies;
        }
        if (params.equalsIgnoreCase("belowname")) {
            String vies = joueur == null ? null : plugin.ligneVies(joueur.getUniqueId());
            if (vies != null) return vies;
            // hors partie : le moneytag (meme rendu que l'ancien fancy-value de TAB)
            return me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(joueur,
                    "\u00a7x\u00a7F\u00a7B\u00a7C\u00a72\u00a7E\u00a7B\u26c3 \u00a7x\u00a7F\u00a72\u00a7F\u00a74\u00a7F\u00a77%outmind_balance_short%");
        }
        if (params.equalsIgnoreCase("tables")) return String.valueOf(plugin.nombreTables());
        if (params.equalsIgnoreCase("libres")) return String.valueOf(plugin.tablesLibres());

        boolean free = params.toLowerCase().startsWith("free_");
        boolean status = params.toLowerCase().startsWith("status_");
        if (!free && !status) return null;

        // Format : <mode>_<monde>_<x>_<z>. Le monde peut contenir des
        // underscores : x et z sont les DEUX derniers segments.
        String[] segments = params.split("_");
        if (segments.length < 4) return null;
        double x;
        double z;
        try {
            x = Double.parseDouble(segments[segments.length - 2]);
            z = Double.parseDouble(segments[segments.length - 1]);
        } catch (NumberFormatException e) {
            return null;
        }
        String monde = String.join("_",
                java.util.Arrays.copyOfRange(segments, 1, segments.length - 2));

        Boolean occupee = plugin.tableOccupee(monde, x, z);
        if (occupee == null) return free ? "no" : "?";
        if (free) return occupee ? "no" : "yes";
        return occupee ? "BUSY" : "FREE";
    }
}
