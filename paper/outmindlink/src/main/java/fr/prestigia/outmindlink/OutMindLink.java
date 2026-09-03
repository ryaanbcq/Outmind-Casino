/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.google.gson.Gson
 *  com.google.gson.JsonArray
 *  com.google.gson.JsonElement
 *  com.google.gson.JsonObject
 *  com.google.gson.JsonParser
 *  me.clip.placeholderapi.expansion.PlaceholderExpansion
 *  net.milkbowl.vault.economy.Economy
 *  net.milkbowl.vault.economy.EconomyResponse
 *  org.bukkit.Bukkit
 *  org.bukkit.Material
 *  org.bukkit.OfflinePlayer
 *  org.bukkit.Sound
 *  org.bukkit.command.Command
 *  org.bukkit.command.CommandSender
 *  org.bukkit.configuration.file.YamlConfiguration
 *  org.bukkit.entity.Player
 *  org.bukkit.event.EventHandler
 *  org.bukkit.event.Listener
 *  org.bukkit.event.player.PlayerJoinEvent
 *  org.bukkit.event.player.PlayerQuitEvent
 *  org.bukkit.inventory.ItemStack
 *  org.bukkit.inventory.meta.ItemMeta
 *  org.bukkit.inventory.meta.PotionMeta
 *  org.bukkit.plugin.Plugin
 *  org.bukkit.plugin.RegisteredServiceProvider
 *  org.bukkit.plugin.java.JavaPlugin
 *  org.bukkit.potion.PotionType
 */
package fr.prestigia.outmindlink;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionType;

public final class OutMindLink
extends JavaPlugin
implements Listener {
    private Economy eco;
    private File inboxFile;
    private File outboxFile;
    private File stateFile;
    private long lastSeq = 0L;
    private final Map<UUID, Double> cache = new HashMap<UUID, Double>();
    private final Map<UUID, Long> joinedAt = new HashMap<UUID, Long>();
    private static final long GRACE_MS = 6000L;
    private static final double WELCOME_BONUS = 500000.0;
    private static final String PREFIX = OutMindLink.gradient("Outmind Casino", 161, 140, 209, 251, 194, 235) + " \u00a7f\u00a7l";
    private static final double INVESTOR_MIN = 3000000.0;
    private static final String INVESTOR_TAG = OutMindLink.gradient("\u2600 Investor", 255, 60, 0, 255, 179, 71);
    private static final String WELCOME_MSG = PREFIX + "To welcome you on our Casino, here is \u00a7d\u00a7l500K\u00a7f\u00a7l of value on us. You can start gambling with this bonus, but you won't be able to withdraw this bonus on your Outmind balance in DonutSMP.";
    private final Gson gson = new Gson();
    private final Set<String> bonusGiven = new HashSet<String>();
    private static final double ANNOUNCE_MIN = 100000.0;
    private final Map<String, Double> invested = new HashMap<String, Double>();
    private final Map<UUID, Double> investPending = new HashMap<UUID, Double>();
    private final Map<UUID, Long> investPendingAt = new HashMap<UUID, Long>();
    private static final long INVEST_CONFIRM_MS = 30000L;
    private final Map<String, Long> dailyClaimed = new HashMap<String, Long>();
    private static final long DAILY_COOLDOWN_MS = 86400000L;
    private final Map<String, VerifyCode> verifyCodes = new HashMap<String, VerifyCode>();
    private static final long VERIFY_TTL_MS = 600000L;
    private static final String VERIFY_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private final SecureRandom verifyRandom = new SecureRandom();
    private File verifyFile;
    private final Map<String, String> txStatus = new HashMap<String, String>();
    private final Map<String, Double> txAmount = new HashMap<String, Double>();
    private final Map<String, Long> txAt = new HashMap<String, Long>();
    private final Map<String, List<String>> txHistory = new HashMap<String, List<String>>();
    private static final int HISTORY_MAX = 5;

    private static String gradient(String text, int r1, int g1, int b1, int r2, int g2, int b2) {
        StringBuilder sb = new StringBuilder();
        int n = text.length();
        for (int i = 0; i < n; ++i) {
            char c = text.charAt(i);
            if (c == ' ') {
                sb.append(' ');
                continue;
            }
            double t = n <= 1 ? 0.0 : (double)i / (double)(n - 1);
            String hex = String.format("%02x%02x%02x", (int)Math.round((double)r1 + (double)(r2 - r1) * t), (int)Math.round((double)g1 + (double)(g2 - g1) * t), (int)Math.round((double)b1 + (double)(b2 - b1) * t));
            sb.append("\u00a7x");
            for (char h : hex.toCharArray()) {
                sb.append('\u00a7').append(h);
            }
            sb.append("\u00a7l").append(c);
        }
        return sb.toString();
    }

    public void onEnable() {
        RegisteredServiceProvider rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            this.getLogger().severe("Aucune economie Vault, desactivation.");
            Bukkit.getPluginManager().disablePlugin((Plugin)this);
            return;
        }
        this.eco = (Economy)rsp.getProvider();
        this.getDataFolder().mkdirs();
        this.inboxFile = new File(this.getDataFolder(), "inbox.jsonl");
        this.outboxFile = new File(this.getDataFolder(), "outbox.jsonl");
        this.stateFile = new File(this.getDataFolder(), "state.yml");
        this.verifyFile = new File(this.getDataFolder(), "discord-verify.json");
        this.loadVerifyCodes();
        if (this.stateFile.exists()) {
            YamlConfiguration st = YamlConfiguration.loadConfiguration((File)this.stateFile);
            this.lastSeq = st.getLong("last-seq", 0L);
            for (String n : st.getStringList("bonus-given")) {
                this.bonusGiven.add(n.toLowerCase());
            }
            if (st.isConfigurationSection("tx")) {
                for (String k : st.getConfigurationSection("tx").getKeys(false)) {
                    this.txStatus.put(k, st.getString("tx." + k + ".status", "NONE"));
                    this.txAmount.put(k, st.getDouble("tx." + k + ".amount", 0.0));
                    this.txAt.put(k, st.getLong("tx." + k + ".at", 0L));
                }
            }
            if (st.isConfigurationSection("history")) {
                for (String k : st.getConfigurationSection("history").getKeys(false)) {
                    this.txHistory.put(k, new ArrayList(st.getStringList("history." + k)));
                }
            }
            if (st.isConfigurationSection("daily")) {
                for (String k : st.getConfigurationSection("daily").getKeys(false)) {
                    this.dailyClaimed.put(k, st.getLong("daily." + k, 0L));
                }
            }
            if (st.isConfigurationSection("invested")) {
                for (String k : st.getConfigurationSection("invested").getKeys(false)) {
                    this.invested.put(k, st.getDouble("invested." + k, 0.0));
                }
            }
        }
        if (this.bonusGiven.isEmpty()) {
            this.bonusGiven.addAll(List.of("letenders", "ezokay", "2real4youuu"));
        }
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new CasinoExpansion().register();
            this.getLogger().info("Expansion PlaceholderAPI 'casino' enregistree.");
        } else {
            this.getLogger().warning("PlaceholderAPI absent : le menu /cashout n'aura pas ses placeholders.");
        }
        Bukkit.getPluginManager().registerEvents((Listener)this, (Plugin)this);
        Bukkit.getScheduler().runTaskTimer((Plugin)this, this::processInbox, 100L, 100L);
        Bukkit.getScheduler().runTaskTimer((Plugin)this, this::pollDeltas, 60L, 40L);
        Bukkit.getScheduler().runTaskTimer((Plugin)this, this::writeStatus, 200L, 200L);
        this.getLogger().info("Pont OutMind actif (lastSeq=" + this.lastSeq + ").");
    }

    private void processInbox() {
        List<String> lines;
        if (!this.inboxFile.exists()) {
            return;
        }
        try {
            lines = Files.readAllLines(this.inboxFile.toPath(), StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            this.getLogger().warning("Lecture inbox impossible : " + e.getMessage());
            return;
        }
        boolean advanced = false;
        for (String line : lines) {
            boolean ok;
            JsonObject o;
            if ((line = line.trim()).isEmpty()) continue;
            try {
                o = JsonParser.parseString((String)line).getAsJsonObject();
            }
            catch (Exception e) {
                continue;
            }
            long seq = o.get("seq").getAsLong();
            if (seq <= this.lastSeq) continue;
            String name = o.get("player").getAsString();
            double amount = o.get("amount").getAsDouble();
            if (o.has("kind") && "txresult".equals(o.get("kind").getAsString())) {
                this.applyTxResult(name, amount, o.get("status").getAsString());
                this.lastSeq = seq;
                advanced = true;
                continue;
            }
            OfflinePlayer op = Bukkit.getOfflinePlayer((String)name);
            if (!op.hasPlayedBefore() && !op.isOnline()) {
                ok = false;
            } else if (amount >= 0.0) {
                ok = this.eco.depositPlayer(op, amount).transactionSuccess();
            } else {
                EconomyResponse r = this.eco.withdrawPlayer(op, -amount);
                ok = r.transactionSuccess();
            }
            if (ok) {
                Player online = Bukkit.getPlayerExact((String)name);
                if (online != null && this.cache.containsKey(online.getUniqueId())) {
                    this.cache.merge(online.getUniqueId(), amount, Double::sum);
                }
                if (online != null && amount > 0.0) {
                    online.sendMessage(PREFIX + "\u00a7a\u00a7l+$" + String.format("%,.0f", amount) + "\u00a7f\u00a7l on your in-game balance.");
                }
                this.appendOutbox(this.obj("type", "applied", "seq", seq, "player", name, "amount", amount));
                this.getLogger().info("Inbox seq " + seq + " : " + name + " " + (amount >= 0.0 ? "+" : "") + amount);
            } else {
                this.appendOutbox(this.obj("type", "apply_failed", "seq", seq, "player", name, "amount", amount));
                this.getLogger().warning("Inbox seq " + seq + " NON appliquee (" + name + ", " + amount + ")");
            }
            this.lastSeq = seq;
            advanced = true;
        }
        if (advanced) {
            this.saveState();
        }
    }

    private void pollDeltas() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Long joined = this.joinedAt.get(p.getUniqueId());
            if (joined != null && System.currentTimeMillis() - joined < 6000L) continue;
            double bal = this.eco.getBalance((OfflinePlayer)p);
            Double prev = this.cache.get(p.getUniqueId());
            if (prev == null) {
                this.cache.put(p.getUniqueId(), bal);
                continue;
            }
            double delta = bal - prev;
            if (!(Math.abs(delta) >= 0.001)) continue;
            this.cache.put(p.getUniqueId(), bal);
            this.appendOutbox(this.obj("type", "delta", "player", p.getName(), "delta", delta, "balance", bal));
        }
    }

    private void writeStatus() {
        ArrayList<String> online = new ArrayList<String>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            online.add(p.getName());
        }
        JsonObject o = new JsonObject();
        o.addProperty("type", "status");
        o.addProperty("lastSeq", (Number)this.lastSeq);
        o.addProperty("at", (Number)System.currentTimeMillis());
        o.add("online", this.gson.toJsonTree(online));
        this.appendOutbox(o);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        this.joinedAt.put(e.getPlayer().getUniqueId(), System.currentTimeMillis());
        this.cache.remove(e.getPlayer().getUniqueId());
        Player p = e.getPlayer();
        boolean isNew = !p.hasPlayedBefore();
        this.getLogger().info("join " + p.getName() + " (hasPlayedBefore=" + !isNew + ")");
        if (isNew) {
            Bukkit.getScheduler().runTaskLater((Plugin)this, () -> {
                if (!p.isOnline()) {
                    return;
                }
                this.eco.depositPlayer((OfflinePlayer)p, 500000.0);
                p.sendMessage(WELCOME_MSG);
                this.bonusGiven.add(p.getName().toLowerCase());
                this.saveState();
                this.getLogger().info("Bonus de bienvenue 500000.0 donne a " + p.getName());
            }, 40L);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        this.cache.remove(e.getPlayer().getUniqueId());
        this.joinedAt.remove(e.getPlayer().getUniqueId());
    }

    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String amountArg;
        Player target;
        if (command.getName().equalsIgnoreCase("invest")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Commande joueur uniquement.");
                return true;
            }
            Player p = (Player)sender;
            this.doInvest(p, args.length >= 1 ? args[0] : null);
            return true;
        }
        if (command.getName().equalsIgnoreCase("verify")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Commande joueur uniquement.");
                return true;
            }
            Player p = (Player)sender;
            this.doVerify(p);
            return true;
        }
        if (!command.getName().equalsIgnoreCase("outmind")) {
            return false;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("daily")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Commande joueur uniquement.");
                return true;
            }
            Player p = (Player)sender;
            this.doDaily(p);
            return true;
        }
        if (args.length == 0 || !args[0].equalsIgnoreCase("cashout")) {
            sender.sendMessage("Usage: /outmind <cashout|daily>");
            return true;
        }
        if (sender instanceof Player) {
            Player p;
            target = p = (Player)sender;
            amountArg = args.length >= 2 ? args[1] : null;
        } else {
            if (args.length < 2) {
                sender.sendMessage("Usage console: outmind cashout <joueur> [montant|max]");
                return true;
            }
            target = Bukkit.getPlayerExact((String)args[1]);
            if (target == null) {
                OfflinePlayer op = Bukkit.getOfflinePlayer((String)args[1]);
                if (!op.hasPlayedBefore()) {
                    sender.sendMessage("Joueur inconnu : " + args[1]);
                    return true;
                }
                target = op;
            }
            amountArg = args.length >= 3 ? args[2] : null;
        }
        this.doCashout((OfflinePlayer)target, amountArg);
        return true;
    }

    private void tell(OfflinePlayer op, String message) {
        Player online = op.getPlayer();
        if (online != null) {
            online.sendMessage(message);
        }
    }

    private void refuseCashout(String name, double asked, double allowed, String reason) {
        this.getLogger().info("Cashout refuse : " + name + " demande " + String.format("%.0f", asked) + ", autorise " + String.format("%.0f", allowed) + " (" + reason + ")");
        this.appendOutbox(this.obj("type", "cashout_refused", "player", name, "amount", asked, "allowed", allowed, "reason", reason));
        Bukkit.dispatchCommand((CommandSender)Bukkit.getConsoleSender(), (String)("cashoutrefused " + name + " " + reason));
    }

    private void doCashout(OfflinePlayer p, String amountArg) {
        double amount;
        String name = p.getName();
        if (name == null) {
            return;
        }
        if (!this.isBotOnline()) {
            this.tell(p, PREFIX + "\u00a7c\u00a7lCashouts are closed right now\u00a7f\u00a7l: the bank bot is offline on DonutSMP. Try again in a few minutes.");
            this.refuseCashout(name, 0.0, 0.0, "bot_offline");
            return;
        }
        double balance = this.eco.getBalance(p);
        double reserve = this.bonusGiven.contains(name.toLowerCase()) ? 500000.0 : 0.0;
        double allowed = Math.max(0.0, Math.floor(balance - reserve));
        if (amountArg == null) {
            this.tell(p, PREFIX + "You can cash out up to \u00a7a\u00a7l$" + String.format("%,.0f", allowed) + "\u00a7f\u00a7l" + (reserve > 0.0 ? " (your 500K welcome bonus stays in the casino)." : "."));
            this.tell(p, PREFIX + "Pick an amount in the menu, or type \u00a7e\u00a7l/outmind cashout <amount|max>\u00a7f\u00a7l.");
            return;
        }
        if (amountArg.equalsIgnoreCase("all") || amountArg.equalsIgnoreCase("max")) {
            amount = allowed;
        } else {
            amount = this.parseAmount(amountArg);
            if (amount < 0.0) {
                this.tell(p, PREFIX + "\u00a7c\u00a7lInvalid amount.\u00a7f\u00a7l Try \u00a7e\u00a7l300k\u00a7f\u00a7l or \u00a7e\u00a7l1.5m\u00a7f\u00a7l.");
                this.refuseCashout(name, 0.0, allowed, "invalid_amount");
                return;
            }
        }
        if (allowed < 1.0) {
            this.tell(p, PREFIX + "\u00a7c\u00a7lNothing to cash out\u00a7f\u00a7l" + (reserve > 0.0 ? ": your 500K welcome bonus can't leave the casino. Win above it or deposit on DonutSMP first!" : "."));
            this.refuseCashout(name, amount, allowed, "nothing_allowed");
            return;
        }
        if (amount < 1.0) {
            this.tell(p, PREFIX + "\u00a7c\u00a7lNothing to cash out.");
            this.refuseCashout(name, amount, allowed, "nothing_asked");
            return;
        }
        if (amount > allowed) {
            this.tell(p, PREFIX + "You can cash out at most \u00a7a\u00a7l$" + String.format("%,.0f", allowed) + "\u00a7f\u00a7l" + (reserve > 0.0 ? " (your 500K welcome bonus stays in the casino)." : "."));
            this.refuseCashout(name, amount, allowed, "over_allowed");
            return;
        }
        EconomyResponse r = this.eco.withdrawPlayer(p, amount);
        if (!r.transactionSuccess()) {
            this.tell(p, PREFIX + "\u00a7c\u00a7lWithdrawal failed\u00a7f\u00a7l, try again.");
            this.refuseCashout(name, amount, allowed, "withdraw_failed");
            return;
        }
        if (this.cache.containsKey(p.getUniqueId())) {
            this.cache.merge(p.getUniqueId(), -amount, Double::sum);
        }
        this.appendOutbox(this.obj("type", "cashout", "player", name, "amount", amount));
        String key = name.toLowerCase();
        long now = System.currentTimeMillis();
        this.txStatus.put(key, "PENDING");
        this.txAmount.put(key, amount);
        this.txAt.put(key, now);
        List h = this.txHistory.computeIfAbsent(key, k -> new ArrayList());
        h.add(0, (long)amount + "|" + now + "|PENDING");
        while (h.size() > 5) {
            h.remove(h.size() - 1);
        }
        this.saveState();
        this.getLogger().info("Cashout demande : " + name + " " + amount + (p.isOnline() ? "" : " (hors ligne, Discord)"));
        this.tell(p, PREFIX + "Cashout of \u00a7a\u00a7l$" + String.format("%,.0f", amount) + "\u00a7f\u00a7l requested!");
        String cfr_ignored_0 = PREFIX + "Anything above your bank balance comes right back here. Join \u00a7e\u00a7lDonutSMP\u00a7f\u00a7l to receive your money from \u00a7e\u00a7lOutmindCompany\u00a7f\u00a7l.";
        Bukkit.dispatchCommand((CommandSender)Bukkit.getConsoleSender(), (String)("cashoutpending " + name));
    }

    private double parseAmount(String arg) {
        String raw = arg.replace(",", "").replace("$", "").trim().toLowerCase();
        double mult = 1.0;
        if (raw.endsWith("k")) {
            mult = 1000.0;
            raw = raw.substring(0, raw.length() - 1);
        } else if (raw.endsWith("m")) {
            mult = 1000000.0;
            raw = raw.substring(0, raw.length() - 1);
        } else if (raw.endsWith("b")) {
            mult = 1.0E9;
            raw = raw.substring(0, raw.length() - 1);
        }
        try {
            return Math.floor(Double.parseDouble(raw) * mult);
        }
        catch (NumberFormatException e) {
            return -1.0;
        }
    }

    private void doInvest(Player p, String arg) {
        double amount;
        String key = p.getName().toLowerCase();
        double balance = this.eco.getBalance((OfflinePlayer)p);
        double reserve = this.bonusGiven.contains(key) ? 500000.0 : 0.0;
        double allowed = Math.max(0.0, Math.floor(balance - reserve));
        if (arg == null) {
            p.sendMessage(PREFIX + "Invest in the casino: you \u00a7c\u00a7lgive up\u00a7f\u00a7l that money for good, it becomes house capital.");
            p.sendMessage(PREFIX + "You can invest up to \u00a7d\u00a7l$" + String.format("%,.0f", allowed) + "\u00a7f\u00a7l. Type \u00a7e\u00a7l/invest <amount|max>\u00a7f\u00a7l.");
            double total = this.invested.getOrDefault(key, 0.0);
            if (total > 0.0) {
                p.sendMessage(PREFIX + "Invested so far: \u00a7d\u00a7l$" + String.format("%,.0f", total));
            }
            return;
        }
        if (arg.equalsIgnoreCase("confirm")) {
            Double pending = this.investPending.get(p.getUniqueId());
            Long at = this.investPendingAt.get(p.getUniqueId());
            if (pending == null || at == null || System.currentTimeMillis() - at > 30000L) {
                this.investPending.remove(p.getUniqueId());
                this.investPendingAt.remove(p.getUniqueId());
                p.sendMessage(PREFIX + "\u00a7c\u00a7lNothing to confirm.\u00a7f\u00a7l Start with \u00a7e\u00a7l/invest <amount|max>\u00a7f\u00a7l.");
                return;
            }
            double amount2 = pending;
            this.investPending.remove(p.getUniqueId());
            this.investPendingAt.remove(p.getUniqueId());
            if (amount2 > allowed) {
                p.sendMessage(PREFIX + "\u00a7c\u00a7lYour balance changed\u00a7f\u00a7l, you can invest at most \u00a7d\u00a7l$" + String.format("%,.0f", allowed) + "\u00a7f\u00a7l now. Start again.");
                return;
            }
            EconomyResponse r = this.eco.withdrawPlayer((OfflinePlayer)p, amount2);
            if (!r.transactionSuccess()) {
                p.sendMessage(PREFIX + "\u00a7c\u00a7lInvestment failed\u00a7f\u00a7l, try again.");
                return;
            }
            double before = this.invested.getOrDefault(key, 0.0);
            this.invested.merge(key, amount2, Double::sum);
            this.saveState();
            if (before < 3000000.0 && this.invested.get(key) >= 3000000.0) {
                Bukkit.dispatchCommand((CommandSender)Bukkit.getConsoleSender(), (String)("lp user " + p.getName() + " parent add investor"));
                p.sendMessage(PREFIX + "You unlocked the " + INVESTOR_TAG + "\u00a7f\u00a7l rank! Your name now shines in the chat.");
                p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.0f);
                String rankLine = INVESTOR_TAG + " \u00a7f\u00a7l" + p.getName() + " \u00a77is now an official investor of the OutMind Casino!";
                for (Player pl : Bukkit.getOnlinePlayers()) {
                    pl.sendMessage(rankLine);
                }
                this.getLogger().info("Grade investor donne a " + p.getName());
            }
            String fmt = String.format("%,.0f", amount2);
            p.sendMessage(PREFIX + "\u26c1 You invested \u00a7d\u00a7l$" + fmt + "\u00a7f\u00a7l into the OutMind Casino. Thank you, investor!");
            p.sendMessage(PREFIX + "Total invested: \u00a7d\u00a7l$" + String.format("%,.0f", this.invested.get(key)));
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 0.9f);
            if (amount2 >= 100000.0) {
                String line = "\u00a7d\u00a7l\u26c1 \u00a7f\u00a7l" + p.getName() + " \u00a77just invested \u00a7d\u00a7l$" + fmt + " \u00a77into the OutMind Casino!";
                for (Player pl : Bukkit.getOnlinePlayers()) {
                    pl.sendMessage(line);
                    pl.playSound(pl.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.6f, 1.2f);
                }
            }
            this.getLogger().info("Invest : " + p.getName() + " " + amount2 + " (total " + String.valueOf(this.invested.get(key)) + ")");
            return;
        }
        double d = amount = arg.equalsIgnoreCase("all") || arg.equalsIgnoreCase("max") ? allowed : this.parseAmount(arg);
        if (amount < 0.0) {
            p.sendMessage(PREFIX + "\u00a7c\u00a7lInvalid amount.\u00a7f\u00a7l Try \u00a7e\u00a7l300k\u00a7f\u00a7l, \u00a7e\u00a7l1.5m\u00a7f\u00a7l or \u00a7e\u00a7lmax\u00a7f\u00a7l.");
            return;
        }
        if (allowed < 1.0) {
            p.sendMessage(PREFIX + "\u00a7c\u00a7lNothing to invest\u00a7f\u00a7l" + (reserve > 0.0 ? ": your 500K welcome bonus is house money already." : "."));
            return;
        }
        if (amount < 1.0) {
            p.sendMessage(PREFIX + "\u00a7c\u00a7lNothing to invest.");
            return;
        }
        if (amount > allowed) {
            p.sendMessage(PREFIX + "You can invest at most \u00a7d\u00a7l$" + String.format("%,.0f", allowed) + "\u00a7f\u00a7l" + (reserve > 0.0 ? " (your 500K welcome bonus stays out of it)." : "."));
            return;
        }
        this.investPending.put(p.getUniqueId(), amount);
        this.investPendingAt.put(p.getUniqueId(), System.currentTimeMillis());
        p.sendMessage(PREFIX + "You are about to invest \u00a7d\u00a7l$" + String.format("%,.0f", amount) + "\u00a7f\u00a7l into the casino. \u00a7c\u00a7lThis is permanent\u00a7f\u00a7l, that money will never come back.");
        p.sendMessage(PREFIX + "Type \u00a7e\u00a7l/invest confirm\u00a7f\u00a7l within \u00a7e\u00a7l30s\u00a7f\u00a7l to seal it.");
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 0.8f);
    }

    private void doVerify(Player p) {
        long now = System.currentTimeMillis();
        this.verifyCodes.values().removeIf(v -> v.expiresAt() <= now);
        String uuid = p.getUniqueId().toString();
        String code = null;
        for (Map.Entry<String, VerifyCode> e : this.verifyCodes.entrySet()) {
            if (!e.getValue().uuid().equals(uuid)) continue;
            code = e.getKey();
            break;
        }
        if (code == null) {
            StringBuilder sb = new StringBuilder(6);
            do {
                sb.setLength(0);
                for (int i = 0; i < 6; ++i) {
                    sb.append(VERIFY_ALPHABET.charAt(this.verifyRandom.nextInt(VERIFY_ALPHABET.length())));
                }
            } while (this.verifyCodes.containsKey(sb.toString()));
            code = sb.toString();
            this.verifyCodes.put(code, new VerifyCode(p.getName(), uuid, now + 600000L));
        }
        this.writeVerifyCodes();
        long minLeft = Math.max(1L, (this.verifyCodes.get(code).expiresAt() - now) / 60000L);
        p.sendMessage(PREFIX + "Link your Discord! Your code: \u00a7d\u00a7l" + code);
        p.sendMessage(PREFIX + "Send this code to the OutMind Discord bot. It expires in \u00a7e\u00a7l" + minLeft + " min\u00a7f\u00a7l.");
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.8f, 1.5f);
        this.getLogger().info("Code verify " + code + " pour " + p.getName());
    }

    private void loadVerifyCodes() {
        if (!this.verifyFile.exists()) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString((String)Files.readString(this.verifyFile.toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
            long now = System.currentTimeMillis();
            for (JsonElement el : root.getAsJsonArray("codes")) {
                JsonObject o = el.getAsJsonObject();
                long exp = o.get("expiresAt").getAsLong();
                if (exp <= now) continue;
                this.verifyCodes.put(o.get("code").getAsString(), new VerifyCode(o.get("player").getAsString(), o.get("uuid").getAsString(), exp));
            }
        }
        catch (Exception e) {
            this.getLogger().warning("Lecture discord-verify.json impossible : " + e.getMessage());
        }
    }

    private void writeVerifyCodes() {
        JsonObject root = new JsonObject();
        JsonArray arr = new JsonArray();
        for (Map.Entry<String, VerifyCode> e : this.verifyCodes.entrySet()) {
            JsonObject o = new JsonObject();
            o.addProperty("code", e.getKey());
            o.addProperty("player", e.getValue().player());
            o.addProperty("uuid", e.getValue().uuid());
            o.addProperty("expiresAt", (Number)e.getValue().expiresAt());
            arr.add((JsonElement)o);
        }
        root.add("codes", (JsonElement)arr);
        root.addProperty("updatedAt", (Number)System.currentTimeMillis());
        try {
            Files.writeString(this.verifyFile.toPath(), (CharSequence)this.gson.toJson((JsonElement)root), StandardCharsets.UTF_8, new OpenOption[0]);
        }
        catch (IOException e) {
            this.getLogger().warning("Ecriture discord-verify.json impossible : " + e.getMessage());
        }
    }

    private void doDaily(Player p) {
        long last;
        String key = p.getName().toLowerCase();
        long now = System.currentTimeMillis();
        if (now - (last = this.dailyClaimed.getOrDefault(key, 0L).longValue()) < 86400000L) {
            long left = 86400000L - (now - last);
            long h = left / 3600000L;
            long m = left % 3600000L / 60000L;
            p.sendMessage(PREFIX + "\u00a7c\u00a7lYou already claimed today's gift.\u00a7f\u00a7l Come back in \u00a7e\u00a7l" + h + "h " + m + "m\u00a7f\u00a7l.");
            p.playSound(p.getLocation(), Sound.INTENTIONALLY_EMPTY, 0.8f, 1.0f);
            return;
        }
        ItemStack potion = new ItemStack(Material.POTION);
        PotionMeta meta = (PotionMeta)potion.getItemMeta();
        meta.setBasePotionType(PotionType.LUCK);
        meta.setDisplayName("\u00a7d\u00a7lPotion of Luck");
        meta.setLore(List.of("\u00a77A daily gift from the Outmind Casino.", "\u00a77May fortune follow you."));
        potion.setItemMeta((ItemMeta)meta);
        for (ItemStack rest : p.getInventory().addItem(new ItemStack[]{potion}).values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), rest);
        }
        this.dailyClaimed.put(key, now);
        this.saveState();
        p.sendMessage(PREFIX + "\u00a7a\u00a7l\u2714 \u00a7f\u00a7lHere is your \u00a7d\u00a7lPotion of Luck\u00a7f\u00a7l. May it bless your bets, see you tomorrow!");
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.3f);
        this.getLogger().info("Daily reward donnee a " + p.getName());
    }

    private void applyTxResult(String name, double amount, String status) {
        String key = name.toLowerCase();
        this.txStatus.put(key, status);
        this.txAmount.put(key, amount);
        this.txAt.put(key, System.currentTimeMillis());
        List<String> h = this.txHistory.get(key);
        if (h != null) {
            for (int i = 0; i < h.size(); ++i) {
                double entryAmt;
                String[] parts = h.get(i).split("\\|");
                if (parts.length != 3 || !"PENDING".equals(parts[2])) continue;
                try {
                    entryAmt = Double.parseDouble(parts[0]);
                }
                catch (NumberFormatException e) {
                    continue;
                }
                if (!(Math.abs(entryAmt - amount) <= Math.max(2000.0, amount * 0.02))) continue;
                h.set(i, parts[0] + "|" + parts[1] + "|" + status);
                break;
            }
        }
        this.saveState();
        Player online = Bukkit.getPlayerExact((String)name);
        if (online != null) {
            if ("PAID".equals(status)) {
                String cfr_ignored_0 = PREFIX + "\u00a7a\u00a7l\u2714 \u00a7f\u00a7lYour cashout of \u00a7a\u00a7l$" + String.format("%,.0f", amount) + "\u00a7f\u00a7l has been paid on DonutSMP.";
                Bukkit.dispatchCommand((CommandSender)Bukkit.getConsoleSender(), (String)("cashoutdone " + name + " " + status));
            } else {
                online.sendMessage(PREFIX + "\u00a7c\u00a7l\u2718 \u00a7f\u00a7lYour cashout of \u00a7c\u00a7l$" + String.format("%,.0f", amount) + "\u00a7f\u00a7l failed, the money came back to your in-game balance.");
                Bukkit.dispatchCommand((CommandSender)Bukkit.getConsoleSender(), (String)("cashoutdone " + name + " " + status));
            }
        }
        if ("PAID".equals(status) && amount >= 100000.0) {
            String line = "\u00a76\u00a7l$ \u00a7f\u00a7l" + name + " \u00a77just cashed out \u00a7a\u00a7l$" + String.format("%,.0f", amount) + " \u00a77to DonutSMP!";
            for (Player pl : Bukkit.getOnlinePlayers()) {
                pl.sendMessage(line);
                pl.playSound(pl.getLocation(), "rr:cashout.announce", 1.0f, 1.0f);
            }
        }
        this.getLogger().info("txresult " + name + " " + amount + " " + status);
    }

    private boolean isBotOnline() {
        try {
            File f = new File(this.getDataFolder(), "botstatus.json");
            JsonObject o = JsonParser.parseString((String)Files.readString(f.toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
            return o.get("online").getAsBoolean() && System.currentTimeMillis() - o.get("at").getAsLong() < 120000L;
        }
        catch (Exception e) {
            return false;
        }
    }

    private JsonObject obj(Object ... kv) {
        JsonObject o = new JsonObject();
        for (int i = 0; i < kv.length; i += 2) {
            String k = (String)kv[i];
            Object v = kv[i + 1];
            if (v instanceof Number) {
                Number n = (Number)v;
                o.addProperty(k, n);
                continue;
            }
            o.addProperty(k, String.valueOf(v));
        }
        o.addProperty("at", (Number)System.currentTimeMillis());
        return o;
    }

    private synchronized void appendOutbox(JsonObject o) {
        try (FileWriter w = new FileWriter(this.outboxFile, StandardCharsets.UTF_8, true);){
            w.write(this.gson.toJson((JsonElement)o));
            w.write("\n");
        }
        catch (IOException e) {
            this.getLogger().warning("Ecriture outbox impossible : " + e.getMessage());
        }
    }

    private void saveState() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("last-seq", (Object)this.lastSeq);
        y.set("bonus-given", new ArrayList<String>(this.bonusGiven));
        for (Map.Entry<String, String> entry : this.txStatus.entrySet()) {
            String k = entry.getKey();
            y.set("tx." + k + ".status", (Object)entry.getValue());
            y.set("tx." + k + ".amount", (Object)this.txAmount.getOrDefault(k, 0.0));
            y.set("tx." + k + ".at", (Object)this.txAt.getOrDefault(k, 0L));
        }
        for (Map.Entry<String, Object> entry : this.txHistory.entrySet()) {
            y.set("history." + entry.getKey(), entry.getValue());
        }
        for (Map.Entry<String, Object> entry : this.dailyClaimed.entrySet()) {
            y.set("daily." + entry.getKey(), entry.getValue());
        }
        for (Map.Entry<String, Object> entry : this.invested.entrySet()) {
            y.set("invested." + entry.getKey(), entry.getValue());
        }
        try {
            y.save(this.stateFile);
        }
        catch (IOException e) {
            this.getLogger().warning("Sauvegarde state impossible : " + e.getMessage());
        }
    }

    private class CasinoExpansion
    extends PlaceholderExpansion {
        private CasinoExpansion() {
        }

        public String getIdentifier() {
            return "casino";
        }

        public String getAuthor() {
            return "Prestigia";
        }

        public String getVersion() {
            return OutMindLink.this.getDescription().getVersion();
        }

        public boolean persist() {
            return true;
        }

        public String onRequest(OfflinePlayer p, String params) {
            String param;
            if (p == null || p.getName() == null) {
                return "";
            }
            String key = p.getName().toLowerCase();
            switch (param = params.toLowerCase()) {
                case "balance": {
                    double bal = OutMindLink.this.eco.getBalance(p);
                    double reserve = OutMindLink.this.bonusGiven.contains(key) ? 500000.0 : 0.0;
                    return String.format("%,.0f", Math.max(0.0, Math.floor(bal - reserve)));
                }
                case "bonus": {
                    return String.format("%,.0f", OutMindLink.this.bonusGiven.contains(key) ? 500000.0 : 0.0);
                }
                case "balance_raw": {
                    double bal = OutMindLink.this.eco.getBalance(p);
                    double reserve = OutMindLink.this.bonusGiven.contains(key) ? 500000.0 : 0.0;
                    return String.valueOf((long)Math.max(0.0, Math.floor(bal - reserve)));
                }
                case "bot_status": {
                    return OutMindLink.this.isBotOnline() ? "ONLINE" : "OFFLINE";
                }
                case "invested": {
                    return String.format("%,.0f", OutMindLink.this.invested.getOrDefault(key, 0.0));
                }
                case "tx_status": {
                    String s = OutMindLink.this.txStatus.get(key);
                    if (s == null) {
                        return "NONE";
                    }
                    Long at = OutMindLink.this.txAt.get(key);
                    if (!"PENDING".equals(s) && at != null && System.currentTimeMillis() - at > 300000L) {
                        return "NONE";
                    }
                    return s;
                }
                case "tx_amount": {
                    Double a = OutMindLink.this.txAmount.get(key);
                    return a == null ? "0" : String.format("%,.0f", a);
                }
            }
            if (param.startsWith("history_")) {
                double amt;
                String date;
                int idx;
                try {
                    idx = Integer.parseInt(param.substring("history_".length()));
                }
                catch (NumberFormatException e) {
                    return null;
                }
                List<String> h = OutMindLink.this.txHistory.get(key);
                if (h == null || idx < 1 || idx > h.size()) {
                    return "No transactions";
                }
                String[] parts = h.get(idx - 1).split("\\|");
                if (parts.length != 3) {
                    return "No transactions";
                }
                try {
                    SimpleDateFormat df = new SimpleDateFormat("dd/MM HH:mm");
                    df.setTimeZone(TimeZone.getTimeZone("Europe/Paris"));
                    date = df.format(new Date(Long.parseLong(parts[1])));
                }
                catch (NumberFormatException e) {
                    return "No transactions";
                }
                try {
                    amt = Double.parseDouble(parts[0]);
                }
                catch (NumberFormatException e) {
                    return "No transactions";
                }
                return "$" + String.format("%,.0f", amt) + " | " + date + " | " + parts[2];
            }
            return null;
        }
    }

    private record VerifyCode(String player, String uuid, long expiresAt) {
    }
}

