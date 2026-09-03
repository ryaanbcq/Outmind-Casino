package net.thundranode.buckshot.paper;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record TableConfig(String monde, double x, double y, double z, float yaw,
                          String type) {

    /** Deux familles de tables (2026-08-30) : "solo" (DrDonutt) ou "duel" (PvP). */
    public TableConfig {
        type = "duel".equalsIgnoreCase(type) ? "duel" : "solo";
    }

    public boolean estDuel() {
        return "duel".equals(type);
    }

    /**
     * Charge TOUTES les tables (liste `tables`), en migrant l'ancienne cle
     * unique `table` si elle existe encore : le plugin est multi-tables
     * depuis le 2026-08-29 (demande user, une table par monde/lieu au choix).
     */
    public static List<TableConfig> chargerToutes(JavaPlugin plugin) {
        FileConfiguration config = plugin.getConfig();
        List<TableConfig> tables = new ArrayList<>();
        for (Map<?, ?> entree : config.getMapList("tables")) {
            Object monde = entree.get("world");
            if (!(monde instanceof String nom)) continue;
            tables.add(new TableConfig(nom, nombre(entree.get("x")),
                    nombre(entree.get("y")), nombre(entree.get("z")),
                    (float) nombre(entree.get("yaw")),
                    entree.get("type") instanceof String t ? t : "solo"));
        }
        if (config.isString("table.world")) {
            tables.add(new TableConfig(
                    config.getString("table.world"), config.getDouble("table.x"),
                    config.getDouble("table.y"), config.getDouble("table.z"),
                    (float) config.getDouble("table.yaw"), "solo"));
        }
        return tables;
    }

    public static void sauverToutes(JavaPlugin plugin, List<TableConfig> tables) {
        List<Map<String, Object>> liste = new ArrayList<>();
        for (TableConfig table : tables) {
            Map<String, Object> entree = new LinkedHashMap<>();
            entree.put("world", table.monde());
            entree.put("x", table.x());
            entree.put("y", table.y());
            entree.put("z", table.z());
            entree.put("yaw", (double) table.yaw());
            entree.put("type", table.type());
            liste.add(entree);
        }
        plugin.getConfig().set("tables", liste);
        // L'ancienne cle unique ne doit pas ressusciter une table au boot.
        plugin.getConfig().set("table", null);
        plugin.saveConfig();
    }

    private static double nombre(Object valeur) {
        return valeur instanceof Number n ? n.doubleValue() : 0;
    }

    /** Identifiant stable de la table, pour marquer ses entites dans le monde. */
    public String id() {
        return monde + ":" + Math.round(x * 10) + ":" + Math.round(z * 10);
    }

    /** L'administrateur se tient à la future place du joueur et regarde la table. */
    public static TableConfig depuisPlaceJoueur(Location joueur) {
        return depuisPlaceJoueur(joueur, "solo");
    }

    public static TableConfig depuisPlaceJoueur(Location joueur, String type) {
        // Yaw aimanté au multiple de 90° le plus proche : à main levée la
        // table partait légèrement de biais (demande user 2026-08-29).
        float yaw = Math.round(joueur.getYaw() / 90f) * 90f;
        double radians = Math.toRadians(yaw);
        Vector avant = new Vector(-Math.sin(radians), 0, Math.cos(radians));
        Location centre = joueur.clone().add(avant.multiply(2.2));
        return new TableConfig(joueur.getWorld().getName(), centre.getX(),
                joueur.getY(), centre.getZ(), yaw, type);
    }

    public Location centre() {
        World world = Bukkit.getWorld(monde);
        if (world == null) throw new IllegalStateException("monde de table absent : " + monde);
        return new Location(world, x, y, z, yaw, 0);
    }

    public Vector avant() {
        double radians = Math.toRadians(yaw);
        return new Vector(-Math.sin(radians), 0, Math.cos(radians)).normalize();
    }

    public Location placeJoueur() {
        Vector avant = avant();
        Location lieu = centre().clone().subtract(avant.clone().multiply(2.2));
        lieu.setYaw(yaw);
        lieu.setPitch(0);
        return lieu;
    }

    public Location placeDealer() {
        Vector avant = avant();
        Location lieu = centre().clone().add(avant.clone().multiply(2.2));
        lieu.setYaw(yaw + 180f);
        lieu.setPitch(0);
        return lieu;
    }
}
