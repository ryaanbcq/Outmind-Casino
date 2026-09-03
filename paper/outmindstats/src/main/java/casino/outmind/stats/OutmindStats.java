package casino.outmind.stats;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import me.clip.placeholderapi.PlaceholderAPI;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/**
 * Profit d'un joueur = argent gagne au casino, hors depots :
 *   profit = solde vault + investi - net depose (mirrored) - bonus de bienvenue.
 * Le net depose vient de mirrored.json, pousse par le bridge VPS a chaque
 * changement (cle = pseudo, insensible a la casse et au point Bedrock).
 */
public final class OutmindStats extends JavaPlugin {
    private volatile Map<String, Double> mirrored = new HashMap<>();
    private volatile java.util.Set<String> lies = new java.util.HashSet<>();
    private long derniereModif = -1;
    private long derniereModifLiens = -1;
    private File fichier;
    private File fichierLiens;
    private Expansion expansion;

    @Override
    public void onEnable() {
        getDataFolder().mkdirs();
        fichier = new File(getDataFolder(), "mirrored.json");
        fichierLiens = new File(getDataFolder(), "links.json");
        recharger();
        getServer().getScheduler().runTaskTimerAsynchronously(this, this::recharger, 100L, 100L);
        // plugman reload : l'ancienne expansion reste enregistree dans PAPI, on la retire d'abord
        me.clip.placeholderapi.PlaceholderAPIPlugin.getInstance().getLocalExpansionManager()
            .findExpansionByIdentifier("outmind").ifPresent(PlaceholderExpansion::unregister);
        expansion = new Expansion();
        expansion.register();
        getLogger().info("placeholders %outmind_profit% / %outmind_profit_raw% / %outmind_balance_short% enregistres (" + mirrored.size() + " joueurs dans mirrored.json)");
    }

    @Override
    public void onDisable() {
        if (expansion != null) { expansion.unregister(); }
    }

    private void recharger() {
        rechargerLiens();
        try {
            if (!fichier.exists()) { return; }
            long m = fichier.lastModified();
            if (m == derniereModif) { return; }
            String json = Files.readString(fichier.toPath(), StandardCharsets.UTF_8);
            Map<String, Double> brut = new Gson().fromJson(json, new TypeToken<Map<String, Double>>() {}.getType());
            Map<String, Double> propre = new HashMap<>();
            if (brut != null) {
                for (Map.Entry<String, Double> e : brut.entrySet()) {
                    if (e.getValue() != null) { propre.put(normaliser(e.getKey()), e.getValue()); }
                }
            }
            mirrored = propre;
            derniereModif = m;
        } catch (Exception ex) {
            getLogger().warning("mirrored.json illisible : " + ex.getMessage());
        }
    }

    private void rechargerLiens() {
        try {
            if (!fichierLiens.exists()) { return; }
            long m = fichierLiens.lastModified();
            if (m == derniereModifLiens) { return; }
            String json = Files.readString(fichierLiens.toPath(), StandardCharsets.UTF_8);
            java.util.List<String> brut = new Gson().fromJson(json, new TypeToken<java.util.List<String>>() {}.getType());
            java.util.Set<String> propre = new java.util.HashSet<>();
            if (brut != null) { for (String n : brut) { if (n != null) { propre.add(normaliser(n)); } } }
            lies = propre;
            derniereModifLiens = m;
        } catch (Exception ex) {
            getLogger().warning("links.json illisible : " + ex.getMessage());
        }
    }

    static String normaliser(String nom) {
        String n = nom.toLowerCase();
        return n.startsWith(".") ? n.substring(1) : n;
    }

    static long chiffres(String s) {
        if (s == null) { return 0L; }
        String d = s.replaceAll("[^0-9-]", "");
        if (d.isEmpty() || d.equals("-")) { return 0L; }
        try { return Long.parseLong(d); } catch (NumberFormatException e) { return 0L; }
    }

    long profit(Player p) {
        long solde = chiffres(PlaceholderAPI.setPlaceholders(p, "%casino_balance_raw%"));
        long investi = chiffres(PlaceholderAPI.setPlaceholders(p, "%casino_invested%"));
        long bonus = chiffres(PlaceholderAPI.setPlaceholders(p, "%casino_bonus%"));
        double net = mirrored.getOrDefault(normaliser(p.getName()), 0.0);
        return solde + investi - Math.round(net) - bonus;
    }

    /** 1234 -> "1.2K", 55_930_801 -> "55.9M", 975_000_000 -> "975M", 1_500_000_000 -> "1.5B". */
    static String court(long v) {
        long a = Math.abs(v);
        String signe = v < 0 ? "-" : "";
        if (a < 1_000L) { return signe + a; }
        String[] suf = {"K", "M", "B", "T"};
        double d = a; int i = -1;
        while (d >= 1_000d && i < suf.length - 1) { d /= 1_000d; i++; }
        String txt = d >= 100 ? String.format("%.0f", d) : String.format("%.1f", d).replace(",", ".");
        if (txt.endsWith(".0")) { txt = txt.substring(0, txt.length() - 2); }
        return signe + txt + suf[i];
    }

    private final class Expansion extends PlaceholderExpansion {
        @Override public String getIdentifier() { return "outmind"; }
        @Override public String getAuthor() { return "Outmind"; }
        @Override public String getVersion() { return "1.0.0"; }
        @Override public boolean persist() { return true; }

        @Override
        public String onRequest(OfflinePlayer off, String params) {
            if (!(off instanceof Player p)) { return ""; }
            switch (params) {
                case "discord": return lies.contains(normaliser(p.getName())) ? "&aʟɪɴᴋᴇᴅ" : "&cɴᴏᴛ ʟɪɴᴋᴇᴅ";
                case "playtime": {
                    long ticks = p.getStatistic(org.bukkit.Statistic.PLAY_ONE_MINUTE);
                    long min = ticks / 1200L;
                    long jours = min / 1440L, heures = (min % 1440L) / 60L, minutes = min % 60L;
                    if (jours >= 1) { return jours + "d"; }
                    if (heures >= 1) { return heures + "h " + minutes + "m"; }
                    return minutes + "m";
                }
                case "discord_raw": return lies.contains(normaliser(p.getName())) ? "yes" : "no";
                case "invested_short": return court(chiffres(PlaceholderAPI.setPlaceholders(p, "%casino_invested%")));
                case "profit_short": {
                    long v = profit(p);
                    if (v > 0) { return "&a+$" + court(v); }
                    if (v < 0) { return "&c-$" + court(-v); }
                    return "&7$0";
                }
                case "balance_short": return court(chiffres(PlaceholderAPI.setPlaceholders(p, "%casino_balance_raw%")));
                case "balance_k": return Long.toString(chiffres(PlaceholderAPI.setPlaceholders(p, "%casino_balance_raw%")) / 1000L);
                case "profit_raw": return Long.toString(profit(p));
                case "profit_abs": return String.format("%,d", Math.abs(profit(p)));
                case "profit": {
                    long v = profit(p);
                    if (v > 0) { return "&a+$" + String.format("%,d", v); }
                    if (v < 0) { return "&c-$" + String.format("%,d", -v); }
                    return "&7$0";
                }
                default: return null;
            }
        }
    }
}
