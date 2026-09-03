package net.thundranode.buckshot;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.thundranode.buckshot.jeu.Regles;
import net.thundranode.buckshot.paper.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

public final class BuckshotPlugin extends JavaPlugin implements TabExecutor {

    /** Portee par defaut de /rr table retirer, un peu plus large que la table. */
    private static final double RAYON_RETRAIT = 12.0;
    private static final double RAYON_RETRAIT_MAX = 128.0;

    private Etats etats;
    private Animateur animateur;
    private Regles regles;
    /** Regles du duel PvP : un seul round, 6 coeurs chacun (config duel.*). */
    private Regles reglesDuel;
    private Banque banque;
    /** Multi-tables (2026-08-29) : chaque table a sa scene, son dealer, sa
     * partie et ses ecouteurs - les parties tournent en parallele. */
    private final List<TableJeu> tables = new ArrayList<>();
    private org.bukkit.scheduler.BukkitTask balayageRepere;

    private record TableJeu(MiseEnScene scene, ControleurPartie controleur,
                            ControleurDuel duel, EcouteurPartie ecouteurPartie,
                            EcouteurDuel ecouteurDuel, EcouteurTable ecouteurTable) { }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Regles regles;
        try {
            regles = lireRegles();
            reglesDuel = lireReglesDuel();
        } catch (IllegalArgumentException erreur) {
            getLogger().severe("Invalid configuration: " + erreur.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        etats = Etats.charger();
        animateur = new Animateur(this, etats);
        this.regles = regles;
        banque = new Banque();
        if (banque.initialiser()) {
            getLogger().info("Economie Vault branchee : mises actives.");
        } else {
            getLogger().warning("Pas d'economie Vault : la table joue gratuitement.");
        }
        for (TableConfig config : TableConfig.chargerToutes(this)) {
            ajouterTable(config);
        }
        // Reecrit la liste tout de suite : migre l'ancienne cle unique
        // `table` vers `tables` au premier boot multi-tables.
        sauverTables();
        getLogger().info(tables.size() + " table(s) Buckshot chargee(s).");

        var cmd = getCommand("rr");
        if (cmd == null) throw new IllegalStateException("commande rr absente de plugin.yml");
        cmd.setExecutor(this);
        cmd.setTabCompleter(this);
        var sortie = getCommand("leave");
        if (sortie == null) throw new IllegalStateException("commande leave absente de plugin.yml");
        sortie.setExecutor(this);
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new PlaceholdersBuckshot(this).register();
            getLogger().info("Placeholders %buckshot_...% enregistres.");
        }
        getLogger().info("Buckshot C+D loaded; states: " + String.join(", ", etats.noms()));
    }

    @Override
    public void onDisable() {
        for (TableJeu table : tables) {
            table.controleur().arreter();
            table.duel().arreter();
            table.scene().fermer();
        }
        tables.clear();
        if (animateur != null) animateur.annulerTous();
    }

    /** Monte une table complete (scene + dealer + partie + ecouteurs) et la demarre. */
    private TableJeu ajouterTable(TableConfig config) {
        MiseEnScene scene = new MiseEnScene(this,
                getConfig().getDouble("staging.music-start-radius"),
                getConfig().getDouble("staging.music-stop-radius"), config);
        for (int round : new int[]{2, 3}) {
            String base = "skins.round-" + round;
            String valeur = getConfig().getString(base + ".value", "");
            String signature = getConfig().getString(base + ".signature", "");
            if (!valeur.isEmpty() && !signature.isEmpty()) {
                scene.definirPeauRound(round, valeur, signature);
            }
        }
        ControleurPartie controleur = new ControleurPartie(this, regles, animateur,
                new InventairePartie(this), new EcranNoir(this), banque,
                scene, getConfig().getInt("game.dealer-think-min-ticks"),
                getConfig().getInt("game.dealer-think-max-ticks"),
                getConfig().getInt("game.dealer-aim-ticks", 40),
                getConfig().getInt("game.dealer-item-ticks", 30),
                getConfig().getInt("game.loupe-hold-ticks", 18));
        // Duel PvP (2026-08-30) : un second controleur par table, frere du
        // solo - meme scene, meme table, jamais les deux en meme temps.
        ControleurDuel duel = new ControleurDuel(this, reglesDuel, animateur,
                new InventairePartie(this), new EcranNoir(this), banque, scene,
                getConfig().getInt("game.loupe-hold-ticks", 18));
        // Verrou inter-tables : une seule partie par joueur, toutes tables
        // confondues (le clic dealer d'une table voisine passait sinon) -
        // duels compris, dans les deux sens.
        controleur.verrouExterne(joueurId -> {
            TableJeu occupee = tableSession(joueurId);
            if (occupee != null && occupee.controleur() != controleur) return true;
            return tableDuelConcerne(joueurId) != null;
        });
        duel.verrouExterne(joueurId -> {
            if (tableSession(joueurId) != null) return true;
            TableJeu autre = tableDuelConcerne(joueurId);
            return autre != null && autre.duel() != duel;
        });
        duel.verrouSolo(controleur::partieEnCours);
        EcouteurPartie ecouteurPartie = new EcouteurPartie(controleur);
        EcouteurDuel ecouteurDuel = new EcouteurDuel(duel);
        EcouteurTable ecouteurTable = new EcouteurTable(scene, controleur);
        getServer().getPluginManager().registerEvents(ecouteurPartie, this);
        getServer().getPluginManager().registerEvents(ecouteurDuel, this);
        getServer().getPluginManager().registerEvents(ecouteurTable, this);
        scene.demarrer();
        // Deux familles de tables (2026-08-30) : une table DUEL n'a ni
        // DrDonutt ni auto-start solo (le solo y est verrouille en dur),
        // seule sa boucle de reparation tourne. Une table SOLO garde son
        // flow historique et n'accueille aucun duel (routage des commandes).
        if (config.estDuel()) {
            controleur.occupationExterne(() -> true);
            duel.surveillerReparation();
        } else {
            controleur.occupationExterne(duel::occupeTable);
            controleur.surveillerApproche();
        }
        TableJeu table = new TableJeu(scene, controleur, duel, ecouteurPartie,
                ecouteurDuel, ecouteurTable);
        tables.add(table);
        return table;
    }

    /** Demonte une table : ecouteurs, partie, scene, et sortie de la liste. */
    private void retirerTable(TableJeu table) {
        org.bukkit.event.HandlerList.unregisterAll(table.ecouteurPartie());
        org.bukkit.event.HandlerList.unregisterAll(table.ecouteurDuel());
        org.bukkit.event.HandlerList.unregisterAll(table.ecouteurTable());
        table.controleur().arreter();
        table.duel().arreter();
        table.scene().fermer();
        tables.remove(table);
    }

    private void sauverTables() {
        List<TableConfig> configs = new ArrayList<>();
        for (TableJeu table : tables) {
            if (table.scene().config() != null) configs.add(table.scene().config());
        }
        TableConfig.sauverToutes(this, configs);
    }

    /** La table dont ce joueur occupe la partie, s'il y en a une. */
    private TableJeu tableSession(java.util.UUID joueurId) {
        for (TableJeu table : tables) {
            if (table.controleur().estEnPartie(joueurId)) return table;
        }
        return null;
    }

    /** La table ou ce joueur joue un duel ou a un defi pose, sinon null. */
    private TableJeu tableDuelConcerne(java.util.UUID joueurId) {
        for (TableJeu table : tables) {
            if (table.duel().estConcerne(joueurId)) return table;
        }
        return null;
    }

    /** La table de DUEL la plus proche dans ce monde (jamais une table solo). */
    private TableJeu tableDuelProche(org.bukkit.Location lieu) {
        TableJeu proche = null;
        double meilleure = Double.MAX_VALUE;
        for (TableJeu table : tables) {
            if (table.scene().config() == null || !table.scene().config().estDuel()) continue;
            org.bukkit.Location centre = table.scene().centreConfigure();
            if (centre == null || !centre.getWorld().equals(lieu.getWorld())) continue;
            double distance = centre.distanceSquared(lieu);
            if (distance < meilleure) {
                meilleure = distance;
                proche = table;
            }
        }
        return proche;
    }

    /** Table de duel de ce joueur : son duel/defi d'abord, sinon la table
     *  DUEL la plus proche - jamais une table solo, les deux familles ne se
     *  melangent pas. */
    private TableJeu tableDuelDe(Player joueur) {
        TableJeu concerne = tableDuelConcerne(joueur.getUniqueId());
        return concerne != null ? concerne : tableDuelProche(joueur.getLocation());
    }

    /** La table dont le defi en attente vient de ce provocateur, sinon null. */
    private TableJeu tableDefiDe(String nomChallenger) {
        for (TableJeu table : tables) {
            if (nomChallenger.equalsIgnoreCase(table.duel().nomDefi())) return table;
        }
        return null;
    }

    /**
     * Repli d'un `/rr duel accepter` tape a la main loin de toute table : s'il
     * n'y a qu'UN defi en attente sur le serveur, c'est forcement lui.
     * Plusieurs defis = ambigu, on renvoie vers les boutons JOIN du chat.
     */
    private TableJeu seuleTableAvecDefi(Player joueur) {
        TableJeu trouvee = null;
        for (TableJeu table : tables) {
            if (table.duel().nomDefi() == null) continue;
            if (trouvee != null) {
                joueur.sendMessage(Component.text(
                        "Several duels are waiting: click a JOIN button in chat, "
                                + "or /rr duel accepter <player>.", NamedTextColor.RED));
                return null;
            }
            trouvee = table;
        }
        if (trouvee == null) {
            joueur.sendMessage(Component.text(
                    "No duel to accept. /rr duel <amount> to start one.", NamedTextColor.RED));
        }
        return trouvee;
    }

    /** La table configuree la plus proche dans le monde du lieu, sinon null. */
    private TableJeu tableProche(org.bukkit.Location lieu) {
        TableJeu proche = null;
        double meilleure = Double.MAX_VALUE;
        for (TableJeu table : tables) {
            org.bukkit.Location centre = table.scene().centreConfigure();
            if (centre == null || !centre.getWorld().equals(lieu.getWorld())) continue;
            double distance = centre.distanceSquared(lieu);
            if (distance < meilleure) {
                meilleure = distance;
                proche = table;
            }
        }
        return proche;
    }

    /** Table de travail d'un joueur : sa partie en cours d'abord, sinon la plus proche. */
    private TableJeu tableDe(Player joueur) {
        TableJeu session = tableSession(joueur.getUniqueId());
        return session != null ? session : tableProche(joueur.getLocation());
    }

    // ---- Statuts pour les placeholders (%buckshot_...%, menus DeluxeMenus) ----

    int nombreTables() {
        return tables.size();
    }

    int tablesLibres() {
        int libres = 0;
        for (TableJeu table : tables) {
            if (!table.controleur().partieEnCours() && !table.duel().occupeTable()) libres++;
        }
        return libres;
    }

    /**
     * Occupation de la table la plus proche de (x, z) dans ce monde, ou null
     * si aucune table a moins de 50 blocs. Adresser par coordonnees plutot
     * que par index : l'ordre de la liste bouge quand on cree/retire des
     * tables, les salles du casino ne bougent pas.
     */
    Boolean tableOccupee(String monde, double x, double z) {
        TableJeu proche = null;
        double meilleure = Double.MAX_VALUE;
        for (TableJeu table : tables) {
            org.bukkit.Location centre = table.scene().centreConfigure();
            if (centre == null || !centre.getWorld().getName().equalsIgnoreCase(monde)) continue;
            double dx = centre.getX() - x;
            double dz = centre.getZ() - z;
            double distance = dx * dx + dz * dz;
            if (distance < meilleure) {
                meilleure = distance;
                proche = table;
            }
        }
        if (proche == null || meilleure > 50 * 50) return null;
        return proche.controleur().partieEnCours() || proche.duel().occupeTable();
    }

    private Regles lireRegles() {
        // Vies de depart round par round. L'ancienne cle unique game.lives
        // sert encore de repli pour une config qui n'a pas la liste.
        List<Integer> vies = getConfig().getIntegerList("game.lives-by-round");
        if (vies.size() != 3) {
            int uniforme = getConfig().getInt("game.lives", 3);
            vies = List.of(uniforme, uniforme, uniforme);
        }
        int plafond = getConfig().getInt("game.max-lives",
                vies.stream().mapToInt(Integer::intValue).max().orElse(3));
        List<net.thundranode.buckshot.jeu.Regles.PlageChargeur> plages = new java.util.ArrayList<>();
        for (int round = 1; round <= 3; round++) {
            String base = "game.shells-by-round.round-" + round + ".";
            plages.add(new net.thundranode.buckshot.jeu.Regles.PlageChargeur(
                    getConfig().getInt(base + "min"),
                    getConfig().getInt(base + "max"),
                    getConfig().getInt(base + "reals-min"),
                    getConfig().getInt(base + "reals-max")));
        }
        List<Integer> objets = getConfig().getIntegerList("game.items-per-load-by-round");
        int maximum = getConfig().getInt("game.max-items");
        int blackout = getConfig().getInt("game.blackout-ticks");
        int min = getConfig().getInt("game.dealer-think-min-ticks");
        int max = getConfig().getInt("game.dealer-think-max-ticks");
        double rayonDebut = getConfig().getDouble("staging.music-start-radius");
        double rayonFin = getConfig().getDouble("staging.music-stop-radius");
        if (min < 1 || max < min) throw new IllegalArgumentException("délais du dealer invalides");
        if (rayonDebut <= 0 || rayonFin <= rayonDebut) {
            throw new IllegalArgumentException("rayons de musique invalides");
        }
        return new Regles(vies, plafond, plages, objets, maximum, blackout, lirePoidsObjets());
    }
    /** Poids de tirage des objets (game.item-weights.<objet>), 1 partout par defaut. */
    private List<Integer> lirePoidsObjets() {
        List<Integer> poids = new java.util.ArrayList<>();
        for (net.thundranode.buckshot.jeu.Objet o : net.thundranode.buckshot.jeu.Objet.values()) {
            poids.add(Math.max(0, getConfig().getInt("game.item-weights." + o.name().toLowerCase(java.util.Locale.ROOT), 1)));
        }
        return poids;
    }


    /**
     * Regles du duel PvP : UN round (le moteur tourne avec roundFinal = 1),
     * 6 coeurs chacun, tous les objets en jeu. Le record Regles exige trois
     * entrees par liste, alors la meme valeur est tripliquee - seule la
     * premiere sert.
     */
    private Regles lireReglesDuel() {
        int vies = getConfig().getInt("duel.vies", 6);
        // Chargeur de duel totalement aleatoire (demande user 2026-08-30) :
        // 2 a 8 cartouches, reelles libres - le generateur clampe de toute
        // facon pour garder au moins une reelle ET une blanche.
        var plage = new net.thundranode.buckshot.jeu.Regles.PlageChargeur(
                getConfig().getInt("duel.chargeur.min", 2),
                getConfig().getInt("duel.chargeur.max", 8),
                getConfig().getInt("duel.chargeur.reals-min", 1),
                getConfig().getInt("duel.chargeur.reals-max", 7));
        int objets = getConfig().getInt("duel.objets-par-chargeur", 3);
        int maximum = getConfig().getInt("game.max-items", 8);
        int blackout = getConfig().getInt("game.blackout-ticks", 40);
        return new Regles(List.of(vies, vies, vies), vies,
                List.of(plage, plage, plage), List.of(objets, objets, objets),
                maximum, blackout, lirePoidsObjets());
    }

    @Override
    public boolean onCommand(CommandSender envoyeur, Command cmd, String label, String[] args) {
        if (!(envoyeur instanceof Player joueur)) {
            envoyeur.sendMessage(Component.text("Players only."));
            return true;
        }
        // Multi-tables : les commandes travaillent sur la table du joueur -
        // sa partie en cours d'abord, sinon la plus proche dans son monde.
        TableJeu tableCourante = tableDe(joueur);
        MiseEnScene scene = tableCourante == null ? null : tableCourante.scene();
        ControleurPartie controleur = tableCourante == null ? null : tableCourante.controleur();

        // /leave : la meme sortie que le bouton GIVE UP - a la question de
        // relance on part avec la caisse, en plein round on perd tout.
        if (cmd.getName().equalsIgnoreCase("leave")) {
            if (!joueur.hasPermission("buckshot.play")) return interdit(joueur);
            TableJeu session = tableSession(joueur.getUniqueId());
            if (session != null) {
                session.controleur().abandonner(joueur);
                return true;
            }
            // En duel, /leave vaut forfait : l'adversaire prend le pot.
            TableJeu duelEnCours = tableDuelConcerne(joueur.getUniqueId());
            if (duelEnCours != null) {
                if (duelEnCours.duel().estEnPartie(joueur.getUniqueId())) {
                    duelEnCours.duel().abandonner(joueur);
                } else {
                    duelEnCours.duel().annulerDefi(joueur);
                }
                return true;
            }
            joueur.sendMessage(Component.text("You are not in a game.", NamedTextColor.RED));
            return true;
        }
        if (args.length == 0) return false;

        String sousCommande = args[0].toLowerCase();
        if (sousCommande.equals("jouer")) {
            if (!joueur.hasPermission("buckshot.play")) return interdit(joueur);
            if (controleur == null) {
                joueur.sendMessage(Component.text("No Buckshot table in this world.", NamedTextColor.RED));
                return true;
            }
            if (tableCourante.scene().config() != null
                    && tableCourante.scene().config().estDuel()) {
                joueur.sendMessage(Component.text(
                        "This is a Duel table: /rr duel <amount> to challenge someone.",
                        NamedTextColor.RED));
                return true;
            }
            controleur.demarrer(joueur);
            return true;
        }
        if (sousCommande.equals("continuer")) {
            if (!joueur.hasPermission("buckshot.play")) return interdit(joueur);
            if (controleur == null) {
                joueur.sendMessage(Component.text("You are not in a game.", NamedTextColor.RED));
                return true;
            }
            controleur.reprendre(joueur);
            return true;
        }
        // Duel PvP : /rr duel <montant> pose un defi, accepter/annuler le
        // resolvent. La table de travail est celle du duel du joueur, sinon
        // la plus proche - comme les autres commandes.
        if (sousCommande.equals("duel")) {
            if (!joueur.hasPermission("buckshot.play")) return interdit(joueur);
            // Resolue paresseusement : le JOIN clique depuis un autre monde
            // n'a pas besoin d'une table proche, le nom du provocateur route.
            TableJeu tableDuel = tableDuelDe(joueur);
            if (args.length < 2) {
                joueur.sendMessage(Component.text(
                        "/rr duel <amount> to challenge, /rr duel accepter to accept, "
                                + "/rr duel annuler to take your challenge back.",
                        NamedTextColor.GRAY));
                return true;
            }
            String choix = args[1].toLowerCase();
            if (choix.equals("accepter") || choix.equals("accept")) {
                // Le bouton JOIN du chat porte le nom du provocateur : c'est
                // lui qui designe la table, le joueur peut cliquer depuis
                // n'importe ou (l'installation le teleporte).
                if (args.length >= 3) {
                    // Nom donne : c'est CE defi ou rien. Pas de repli vers
                    // un autre defi, sinon un clic sur un JOIN perime
                    // ferait payer une mise pour un adversaire non choisi.
                    TableJeu visee = tableDefiDe(args[2]);
                    if (visee == null) {
                        joueur.sendMessage(Component.text("That challenge is gone.",
                                NamedTextColor.RED));
                        return true;
                    }
                    visee.duel().accepter(joueur, args[2]);
                    return true;
                }
                TableJeu visee = null;
                if (tableDuel != null && tableDuel.duel().nomDefi() != null) {
                    visee = tableDuel;
                }
                if (visee == null) visee = seuleTableAvecDefi(joueur);
                if (visee != null) visee.duel().accepter(joueur);
                return true;
            }
            if (tableDuel == null) {
                joueur.sendMessage(Component.text(
                        "No Duel table in this world. /rr table creer duel to add one.",
                        NamedTextColor.RED));
                return true;
            }
            if (choix.equals("annuler") || choix.equals("cancel")) {
                tableDuel.duel().annulerDefi(joueur);
                return true;
            }
            long montant = net.thundranode.buckshot.Mises.parser(args[1]);
            if (montant <= 0) {
                joueur.sendMessage(Component.text("Unreadable amount: " + args[1]
                        + " (e.g. 500K, 2M).", NamedTextColor.RED));
                return true;
            }
            tableDuel.duel().proposer(joueur, montant);
            return true;
        }
        if (sousCommande.equals("abandonner")) {
            if (!joueur.hasPermission("buckshot.play")) return interdit(joueur);
            if (controleur == null) {
                joueur.sendMessage(Component.text("You are not in a game.", NamedTextColor.RED));
                return true;
            }
            controleur.abandonner(joueur);
            return true;
        }
        if (!joueur.hasPermission("buckshot.admin")) return interdit(joueur);

        // Les commandes qui n'ont pas besoin d'une table existante.
        boolean sansTable = sousCommande.equals("donner") || sousCommande.equals("anim")
                || sousCommande.equals("stop") || sousCommande.equals("table");
        if (scene == null && !sansTable) {
            joueur.sendMessage(Component.text(
                    "No Buckshot table in this world. /rr table creer to add one.", NamedTextColor.RED));
            return true;
        }

        switch (sousCommande) {
            // Reglage en direct du fusil vise du dealer. Sans argument :
            // /rr pose front|self montre la pose et rappelle les axes.
            // /rr pose front avant 0.1 pousse d'un dixieme de bloc, etc.
            // /rr pose stop cache le fusil de reglage.
            case "pose" -> {
                if (args.length < 2) return false;
                String quoi = args[1].toLowerCase();
                if (quoi.equals("stop")) {
                    scene.finViseeDealer();
                    joueur.sendMessage(Component.text("Tuning shotgun hidden.", NamedTextColor.GRAY));
                    return true;
                }
                if (!java.util.List.of("front", "self", "inspect", "main",
                        "cigarette", "cigarette-soi", "self2", "menottes").contains(quoi)) {
                    return false;
                }
                String base = "pose." + quoi + ".";
                boolean demandeReset = args.length >= 3 && args[2].equalsIgnoreCase("reset");
                // Une pose jamais ecrite doit s'initialiser toute seule, sinon
                // la commande sort un fusil a l'origine du dealer, bras
                // baisses, et laisse croire a une pose cassee. C'est ce qui
                // arrivait a "main", dont les valeurs n'existaient que dans la
                // branche reset.
                if ((demandeReset || !getConfig().isSet(base + "avant"))
                        && quoi.startsWith("cigarette")) {
                    // Deux poses de cigarette independantes, calibrees en jeu
                    // au stick le 2026-08-23 : "cigarette" = bouche du dealer
                    // (vue de face), "cigarette-soi" = bouche du fumeur
                    // humain (jugee en premiere personne).
                    boolean soi = quoi.endsWith("-soi");
                    getConfig().set(base + "avant", soi ? 0.06 : 0.23);
                    getConfig().set(base + "droite", soi ? 0.0 : -0.01);
                    getConfig().set(base + "haut", soi ? 1.59 : 1.50);
                    getConfig().set(base + "lacet", 2.0);
                    getConfig().set(base + "tangage", -8.0);
                    getConfig().set(base + "roulis", 0.0);
                    getConfig().set(base + "echelle", 0.35);
                    getConfig().set(base + "pivot", 1.67);
                    getConfig().set(base + "bras", 0.0);
                    getConfig().set(base + "modele", "s0");
                    getConfig().set(base + "main", "cache");
                    saveConfig();
                    joueur.sendMessage(Component.text("Pose " + quoi + " reset to defaults.",
                            NamedTextColor.GREEN));
                } else if ((demandeReset || !getConfig().isSet(base + "avant"))
                        && quoi.equals("self2")) {
                    // Pose d'ESSAI de la visee sur soi, sortie du banc de
                    // pose web le 2026-08-23 : s'essaie et se regle sans
                    // toucher a pose.self calibree. Une fois validee, ses
                    // valeurs se recopient dans pose.self.
                    getConfig().set(base + "avant", 0.39);
                    getConfig().set(base + "droite", 0.16);
                    getConfig().set(base + "haut", 1.45);
                    getConfig().set(base + "lacet", 25.0);
                    getConfig().set(base + "tangage", -110.0);
                    getConfig().set(base + "roulis", 180.0);
                    getConfig().set(base + "echelle", 0.55);
                    getConfig().set(base + "bras", 1.0);
                    getConfig().set(base + "modele", "hold");
                    getConfig().set(base + "main", "cache");
                    saveConfig();
                    joueur.sendMessage(Component.text(
                            "Pose self2 initialised from the bench values.",
                            NamedTextColor.GREEN));
                } else if ((demandeReset || !getConfig().isSet(base + "avant"))
                        && quoi.equals("menottes")) {
                    // Menottes PORTEES : anneaux fermes sur les poignets
                    // de la pose arbalete EXACTE (positions calculees depuis
                    // le decompile 1.21.11 : ArmPosing.hold + chaine du
                    // renderer), modele genere par tools/gen_menottes_portees.py.
                    // Ancre = milieu des deux poignets (ecart 0.244 bloc).
                    getConfig().set(base + "avant", 0.45);
                    getConfig().set(base + "droite", 0.08);
                    getConfig().set(base + "haut", 1.23);
                    getConfig().set(base + "lacet", -1.5);
                    getConfig().set(base + "tangage", -7.5);
                    getConfig().set(base + "roulis", 0.0);
                    getConfig().set(base + "echelle", 0.81);
                    getConfig().set(base + "bras", 2.0);
                    getConfig().set(base + "modele", "portees");
                    getConfig().set(base + "pitch-npc", 1.0);
                    getConfig().set(base + "main", "cache");
                    saveConfig();
                    joueur.sendMessage(Component.text(
                            "Pose menottes initialised (bench values).",
                            NamedTextColor.GREEN));
                } else if (demandeReset || !getConfig().isSet(base + "avant")) {
                    // Valeurs calibrees en jeu le 2026-08-22, qui servent
                    // aussi a retrouver un fusil egare (ex : avant 180 = 180
                    // blocs). Les trois poses de jeu ont ete reglees au stick
                    // par l'utilisateur ; "main" est un banc de mesure.
                    boolean self = quoi.equals("self");
                    boolean insp = quoi.equals("inspect");
                    boolean mesure = quoi.equals("main");
                    getConfig().set(base + "avant", mesure ? 0.30 : insp ? 0.95 : self ? 0.17 : 1.06);
                    getConfig().set(base + "droite", mesure ? 0.35 : insp ? 0.45 : self ? 0.29 : 0.10);
                    getConfig().set(base + "haut", mesure ? 1.35 : insp ? 1.61 : 1.45);
                    getConfig().set(base + "lacet", mesure ? 0.0 : insp ? 230.0 : self ? 233.1 : 183.0);
                    getConfig().set(base + "tangage", mesure ? 0.0 : insp ? -20.0 : self ? 115.0 : -10.9);
                    getConfig().set(base + "roulis", mesure ? 0.0 : self ? 180.0 : 0.0);
                    getConfig().set(base + "echelle", 0.55);
                    getConfig().set(base + "bras", self ? 1.0 : 2.0);
                    getConfig().set(base + "modele", insp ? "inspect_7" : "hold");
                    // Le fusil REELLEMENT tenu reste invisible partout sauf au
                    // banc de mesure, ou c'est justement lui qu'on vise.
                    getConfig().set(base + "main", mesure ? "hold" : "cache");
                    saveConfig();
                    joueur.sendMessage(Component.text(mesure
                                    ? "Measuring bench: line the floating shotgun up with the one in his hand."
                                    : "Pose " + quoi + " reset to defaults.",
                            NamedTextColor.GREEN));
                }
                if (args.length >= 4 && args[2].equalsIgnoreCase("main")) {
                    // Etat pose sur le fusil REELLEMENT TENU par le dealer.
                    // "cache" en jeu normal ; un etat visible sert a mesurer.
                    getConfig().set(base + "main", args[3]);
                    saveConfig();
                    scene.finViseeDealer();
                } else if (args.length >= 4 && args[2].equalsIgnoreCase("modele")) {
                    // Seul axe non numerique : le nom de l'etat dessine par
                    // l'ItemDisplay (hold = fusil nu, inspect_7 = cartouche
                    // visible dans le port).
                    getConfig().set(base + "modele", args[3]);
                    saveConfig();
                } else if (args.length >= 4) {
                    String axe = args[2].toLowerCase();
                    if (!java.util.List.of("avant", "droite", "haut", "lacet",
                            "tangage", "roulis", "echelle", "bras", "pivot",
                            "pitch-npc").contains(axe)) {
                        joueur.sendMessage(Component.text(
                                "Axes: avant droite haut (blocks), lacet tangage roulis (degrees), "
                                        + "echelle, bras (0 empty / 1 item in hand / 2 raised), "
                                        + "modele (hold / inspect_7)",
                                NamedTextColor.RED));
                        return true;
                    }
                    double delta;
                    try {
                        delta = Double.parseDouble(args[3]);
                    } catch (NumberFormatException e) {
                        joueur.sendMessage(Component.text("Unreadable value: " + args[3],
                                NamedTextColor.RED));
                        return true;
                    }
                    // pivot n'existe pas dans les configs d'avant : partir de
                    // son defaut, pas de zero, sinon le premier delta teleporte.
                    double valeur = getConfig().getDouble(base + axe,
                            axe.equals("pivot") ? 1.67 : 0.0) + delta;
                    // Les positions se comptent en blocs : au-dela de trois,
                    // le fusil sort du champ et semble avoir disparu.
                    if (java.util.List.of("avant", "droite", "haut", "pivot").contains(axe)) {
                        valeur = Math.max(-3.0, Math.min(3.0, valeur));
                    } else if (axe.equals("echelle")) {
                        valeur = Math.max(0.05, Math.min(3.0, valeur));
                    } else if (axe.equals("pitch-npc")) {
                        // Inclinaison du corps du dealer : au-dela le PNJ
                        // semble casse, meme borne que pitchSur.
                        valeur = Math.max(-60.0, Math.min(60.0, valeur));
                    } else if (axe.equals("bras")) {
                        // Valeur absolue, pas un delta : 0 main vide,
                        // 1 item en main, 2 bras leve.
                        valeur = Math.max(0, Math.min(2, Math.round(delta)));
                    }
                    getConfig().set(base + axe, valeur);
                    saveConfig();
                }
                scene.viserDealer(quoi);
                StringBuilder etat = new StringBuilder("§b" + quoi + " :");
                for (String axe : new String[]{"avant", "droite", "haut", "lacet",
                        "tangage", "roulis", "echelle", "bras", "pivot", "pitch-npc"}) {
                    etat.append(" §7").append(axe).append("§f ")
                            .append(String.format(java.util.Locale.ROOT, "%.2f",
                                    getConfig().getDouble(base + axe)));
                }
                etat.append(" §7modele§f ").append(getConfig().getString(base + "modele", "hold"));
                etat.append(" §7main§f ").append(getConfig().getString(base + "main", "cache"));
                joueur.sendMessage(Component.text(etat.toString()));
                return true;
            }
            // Diagnostic : fait defiler les six axes purs du repere de la main
            // sur DrDonutt, bras leve. Sert a lire ou pointe reellement chaque
            // axe plutot qu'a le deduire, deux deductions opposees ayant chacune
            // explique les memes observations.
            // Cigarette statique posee sur SOI, visible par soi, qui suit la
            // config en direct : /rr garro puis /rr pose cigarette haut -0.02
            // la fait bouger sous tes yeux, sans rejouer la sequence.
            // /rr garro stop la retire.
            // Banc d'essai du tableau de mort : /rr mortdonut l'installe
            // (corps sur la table, sang, mallette), /rr mortdonut stop remet
            // le dealer debout et retire le tout. Sert a calibrer la pose
            // sans gagner une partie.
            case "mortdonut" -> {
                if (args.length >= 2 && args[1].equalsIgnoreCase("stop")) {
                    scene.ranimerDealer();
                    scene.sangPourRound(1);
                    joueur.sendMessage(Component.text("DrDonutt is back up.",
                            NamedTextColor.GRAY));
                } else if (scene.dealerMort()) {
                    joueur.sendMessage(Component.text(
                            "Already dead. /rr mortdonut stop to reset.",
                            NamedTextColor.RED));
                } else {
                    scene.mortDealer();
                    scene.poserMalletteVictoire();
                    joueur.sendMessage(Component.text(
                            "Death tableau set. /rr mortdonut stop to reset.",
                            NamedTextColor.GRAY));
                }
                return true;
            }
            // Apercu de l'anim de mort du joueur : le corps a ton skin tombe
            // la ou tu te tiens, s'effondre face a la table et saigne, comme
            // en vraie partie. Recule d'un pas pour le regarder.
            case "mort" -> {
                scene.apercuMort(joueur);
                joueur.sendMessage(Component.text(
                        "Death preview: your body drops where you stand, gone in 30 s.",
                        NamedTextColor.GRAY));
                return true;
            }
            case "garro" -> {
                if (args.length >= 2 && args[1].equalsIgnoreCase("stop")) {
                    scene.arreterEssaiPose(joueur);
                    joueur.sendMessage(Component.text("Test cigarette removed.",
                            NamedTextColor.GRAY));
                } else {
                    scene.essayerPoseSurSoi(joueur, "cigarette-soi");
                    joueur.sendMessage(Component.text(
                            "Cigarette placed on you (F5 to see it). Tune with "
                                    + "/rr pose cigarette-soi <axis> <delta>; /rr garro stop to remove.",
                            NamedTextColor.GRAY));
                }
                return true;
            }
            // Banc d'essai de la fumette : /rr fume la joue sur soi (a
            // regarder en F5), /rr fume dealer sur le PNJ. Sert a voir la
            // sequence complete et a calibrer /rr pose cigarette sans
            // derouler une partie.
            case "fume" -> {
                if (args.length >= 2 && args[1].equalsIgnoreCase("dealer")) {
                    var entite = scene.dealerEntite().orElse(null);
                    if (entite == null) {
                        joueur.sendMessage(Component.text("No dealer here.", NamedTextColor.RED));
                        return true;
                    }
                    scene.fumerCigarette(entite, false, () -> { });
                } else {
                    scene.fumerCigarette(joueur, false, () -> { });
                    joueur.sendMessage(Component.text(
                            "Switch to F5 to watch yourself smoke; /rr pose cigarette to tune.",
                            NamedTextColor.GRAY));
                }
                return true;
            }
            case "repere" -> {
                if (balayageRepere != null) {
                    balayageRepere.cancel();
                    balayageRepere = null;
                    scene.dealer().baisserBras();
                    joueur.sendMessage(Component.text("Sweep stopped.", NamedTextColor.GRAY));
                    return true;
                }
                if (scene.dealer().entite().isEmpty()) {
                    joueur.sendMessage(Component.text(
                            "DrDonutt is not here. Create the table first.", NamedTextColor.RED));
                    return true;
                }
                // Suffixe _0 obligatoire : le pack declare ses cas par image,
                // pas par etat. Sans lui aucun cas ne correspond et le modele
                // retombe sur hold, ce qui ressemble a une commande sans effet.
                // "self" montre la pose finale du tir sur soi, tenue, bras
                // baisse : c'est une pose a juger telle quelle, pas un balayage.
                boolean soi = args.length >= 2 && args[1].equalsIgnoreCase("self");
                boolean face = args.length >= 2 && args[1].equalsIgnoreCase("front");
                java.util.List<String> axes = soi ? java.util.List.of("aim_self_7")
                        : face ? java.util.List.of("aim_front_7")
                        : java.util.List.of("mesure_v0_0", "mesure_v1_0", "mesure_v2_0",
                                "mesure_v3_0", "mesure_v4_0", "mesure_v5_0", "mesure_v6_0",
                                "mesure_v7_0");
                joueur.sendMessage(Component.text(soi
                        ? "Final self-shot pose, held. Run again to stop."
                        : face
                        ? "Final across-the-table aiming pose, arm raised, held. "
                                + "Run again to stop."
                        : "SELF SHOT: the barrel turns in 45\u00B0 steps, fixed "
                                + "position. Tell me the number where it points "
                                + "straight up at his chin.",
                        NamedTextColor.AQUA));
                balayageRepere = new org.bukkit.scheduler.BukkitRunnable() {
                    int index = 0;

                    @Override
                    public void run() {
                        var dealer = scene.dealer();
                        if (index >= axes.size() || dealer.entite().isEmpty()) {
                            dealer.baisserBras();
                            dealer.retirerFusil();
                            joueur.sendMessage(Component.text("Sweep finished.",
                                    NamedTextColor.GRAY));
                            balayageRepere = null;
                            cancel();
                            return;
                        }
                        String axe = axes.get(index++);
                        // Il te regarde pendant la mesure : sinon « devant lui »
                        // et « vers toi » sont deux directions differentes, et
                        // le balayage mesure autre chose que ce qu'on croit.
                        dealer.regarder(joueur.getEyeLocation());
                        // Poser la pile avant de lever le bras : un remplacement
                        // de pile interrompt l'usage, donc l'ordre inverse
                        // laisserait le bras baisse.
                        dealer.equiperFusil();
                        dealer.entite().ifPresent(entite -> {
                            var item = entite.getInventory().getItemInMainHand();
                            Fusil.poser(item, axe);
                            entite.getInventory().setItemInMainHand(item);
                        });
                        // Toutes les poses de visee sont mesurees bras pendant.
                        String etiquette = axe.startsWith("mesure_")
                                ? axe.substring("mesure_".length(), axe.length() - 2)
                                : axe.substring(0, axe.length() - 2);
                        joueur.sendMessage(Component.text(
                                etiquette + "  (" + index + "/" + axes.size() + ")",
                                NamedTextColor.YELLOW));
                    }
                }.runTaskTimer(this, 0L, (soi || face) ? 1200L : 60L);
                return true;
            }
            case "table" -> {
                if (args.length < 2) return false;
                if (args[1].equalsIgnoreCase("creer")) {
                    if (tableSession(joueur.getUniqueId()) != null) {
                        joueur.sendMessage(Component.text("Leave the game before creating a table.",
                                NamedTextColor.RED));
                        return true;
                    }
                    TableJeu existante = tableProche(joueur.getLocation());
                    if (existante != null && existante.scene().configureeIci(joueur.getLocation(), RAYON_RETRAIT)) {
                        joueur.sendMessage(Component.text(
                                "A table already exists right here (" + existante.scene().description()
                                        + "). Move away or /rr table retirer first.",
                                NamedTextColor.RED));
                        return true;
                    }
                    // /rr table creer [duel] : sans argument, table solo
                    // historique ; "duel" cree une table PvP sans DrDonutt.
                    String typeTable = args.length >= 3
                            && args[2].equalsIgnoreCase("duel") ? "duel" : "solo";
                    TableJeu nouvelle = ajouterTable(
                            TableConfig.depuisPlaceJoueur(joueur.getLocation(), typeTable));
                    sauverTables();
                    joueur.sendMessage(Component.text("Table created (" + typeTable + "): "
                                    + nouvelle.scene().description()
                                    + " (" + tables.size() + " table" + (tables.size() > 1 ? "s" : "") + " total)",
                            NamedTextColor.GREEN));
                    return true;
                }
                if (args[1].equalsIgnoreCase("retirer")) {
                    double rayon = RAYON_RETRAIT;
                    if (args.length >= 3) {
                        try {
                            rayon = Double.parseDouble(args[2]);
                        } catch (NumberFormatException e) {
                            joueur.sendMessage(Component.text("Unreadable radius: " + args[2],
                                    NamedTextColor.RED));
                            return true;
                        }
                        if (!(rayon > 0) || rayon > RAYON_RETRAIT_MAX) {
                            joueur.sendMessage(Component.text(
                                    "Radius must be between 1 and " + (int) RAYON_RETRAIT_MAX + ".",
                                    NamedTextColor.RED));
                            return true;
                        }
                    }
                    // Multi-tables : on ne retire que LA table dans le rayon.
                    // Les autres tables (meme dans ce monde) ne bougent pas.
                    TableJeu cible = tableProche(joueur.getLocation());
                    if (cible != null && !cible.scene().configureeIci(joueur.getLocation(), rayon)) {
                        cible = null;
                    }
                    if (cible != null && (cible.controleur().partieEnCours()
                            || cible.duel().occupeTable())) {
                        joueur.sendMessage(Component.text(
                                "A game is running at this table. Wait for the end first.",
                                NamedTextColor.RED));
                        return true;
                    }
                    java.util.Set<String> idsVivants = new java.util.HashSet<>();
                    for (TableJeu autre : tables) {
                        if (autre != cible && autre.scene().config() != null) {
                            idsVivants.add(autre.scene().config().id());
                        }
                    }
                    if (cible != null) {
                        retirerTable(cible);
                        sauverTables();
                    }
                    int orphelins = MiseEnScene.balayerOrphelins(this, joueur.getLocation(), rayon, idsVivants);
                    if (cible == null && orphelins == 0) {
                        joueur.sendMessage(Component.text("No table within " + (int) rayon + " blocks.",
                                NamedTextColor.GRAY));
                    } else {
                        String base = cible != null
                                ? "Table removed (" + tables.size() + " remaining)"
                                : "Orphan table elements removed";
                        joueur.sendMessage(Component.text(base
                                        + (orphelins > 0 ? ", " + orphelins + " stray element(s) swept." : "."),
                                NamedTextColor.GREEN));
                    }
                    return true;
                }
                if (args[1].equalsIgnoreCase("afficher")) {
                    if (tables.isEmpty()) {
                        joueur.sendMessage(Component.text("No table configured.", NamedTextColor.GRAY));
                        return true;
                    }
                    int index = 1;
                    for (TableJeu table : tables) {
                        String typeAffiche = table.scene().config() != null
                                && table.scene().config().estDuel() ? "duel" : "solo";
                        joueur.sendMessage(Component.text("Table " + index++ + " (" + typeAffiche
                                        + "): " + table.scene().description()
                                        + (table.controleur().partieEnCours() ? " - game in progress"
                                        : table.duel().enCours() ? " - duel in progress"
                                        : table.duel().occupeTable() ? " - duel challenge pending" : ""),
                                NamedTextColor.GRAY));
                    }
                    return true;
                }
                return false;
            }
            case "donner" -> {
                joueur.getInventory().addItem(Fusil.creer());
                joueur.sendMessage(Component.text("Shotgun given.", NamedTextColor.GREEN));
            }
            case "anim" -> {
                if (args.length < 2) {
                    joueur.sendMessage(Component.text("States: " + String.join(", ", etats.noms()), NamedTextColor.GRAY));
                    return true;
                }
                String nom = args[1].toLowerCase();
                if (!etats.existe(nom)) {
                    joueur.sendMessage(Component.text("Unknown state: " + nom, NamedTextColor.RED));
                    return true;
                }
                if (joueur.getInventory().getItemInMainHand().isEmpty()) {
                    joueur.sendMessage(Component.text("Nothing in hand. /rr donner first.", NamedTextColor.RED));
                    return true;
                }
                boolean pousse = animateur.jouer(joueur, nom);
                joueur.sendMessage(Component.text(pousse
                        ? nom + ": " + etats.get(nom).dureeTicks() + " ticks"
                        : nom + " applied. Hold right click.", NamedTextColor.GRAY));
            }
            // Panneau de mixage en direct : /rr sons affiche musique et voix
            // avec des [-]/[+] cliquables, /rr sons musique|voix <0..2> regle.
            // La musique repart aussitot au nouveau volume ; les voix le
            // prennent des la replique suivante. Au-dela de 1.0, Minecraft
            // n'amplifie pas, il elargit seulement la portee du son.
            case "sons" -> {
                if (args.length >= 3) {
                    boolean musique = args[1].equalsIgnoreCase("musique");
                    double valeur;
                    try {
                        valeur = Double.parseDouble(args[2]);
                    } catch (NumberFormatException e) {
                        return false;
                    }
                    valeur = Math.round(Math.max(0.0, Math.min(2.0, valeur)) * 100) / 100.0;
                    getConfig().set(musique ? "sons.volume-musique" : "sons.volume-voix", valeur);
                    saveConfig();
                    if (musique) {
                        scene.arreterMusique(joueur);
                        scene.demarrerMusique(joueur);
                    }
                }
                envoyerPanneauSons(joueur);
            }
            case "stop" -> {
                animateur.annuler(joueur);
                if (!joueur.getInventory().getItemInMainHand().isEmpty()) {
                    Fusil.poser(joueur.getInventory().getItemInMainHand(), "hold");
                }
                joueur.sendMessage(Component.text("Animation stopped.", NamedTextColor.GRAY));
            }
            default -> { return false; }
        }
        return true;
    }

    private void envoyerPanneauSons(Player joueur) {
        joueur.sendMessage(Component.text("- Buckshot mixing -", NamedTextColor.GOLD));
        joueur.sendMessage(ligneSon("Music", "musique",
                getConfig().getDouble("sons.volume-musique", 0.7)));
        joueur.sendMessage(ligneSon("Voice", "voix",
                getConfig().getDouble("sons.volume-voix", 1.6)));
    }

    private Component ligneSon(String etiquette, String cle, double valeur) {
        java.util.Locale racine = java.util.Locale.ROOT;
        double moins = Math.max(0.0, Math.round((valeur - 0.1) * 100) / 100.0);
        double plus = Math.min(2.0, Math.round((valeur + 0.1) * 100) / 100.0);
        return Component.text()
                .append(Component.text(String.format(racine, "%-8s ", etiquette), NamedTextColor.GOLD))
                .append(Component.text(String.format(racine, "%.2f  ", valeur), NamedTextColor.WHITE))
                .append(Component.text("[-]", NamedTextColor.RED)
                        .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand(
                                String.format(racine, "/rr sons %s %.2f", cle, moins))))
                .append(Component.text(" "))
                .append(Component.text("[+]", NamedTextColor.GREEN)
                        .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand(
                                String.format(racine, "/rr sons %s %.2f", cle, plus))))
                .build();
    }

    private static boolean interdit(Player joueur) {
        joueur.sendMessage(Component.text("You do not have permission.", NamedTextColor.RED));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender envoyeur, Command cmd, String label, String[] args) {
        if (args.length == 1) {
            List<String> choix = new ArrayList<>(List.of("jouer", "abandonner", "duel"));
            if (envoyeur.hasPermission("buckshot.admin")) {
                choix.addAll(List.of("table", "donner", "anim", "stop"));
            }
            return filtrer(choix, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("duel")) {
            return filtrer(List.of("accepter", "annuler", "1M", "5M"), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("anim")) {
            return filtrer(new ArrayList<>(etats.noms()), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("table")) {
            return filtrer(List.of("creer", "retirer", "afficher"), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("table")
                && args[1].equalsIgnoreCase("creer")) {
            return filtrer(List.of("duel"), args[2]);
        }
        return List.of();
    }

    private static List<String> filtrer(List<String> source, String debut) {
        String d = debut.toLowerCase();
        return source.stream().filter(s -> s.startsWith(d)).toList();
    }
}
