package net.thundranode.buckshot.paper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.thundranode.buckshot.Fusil;
import net.thundranode.buckshot.jeu.Objet;
import net.thundranode.buckshot.jeu.Participant;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.Base64;
import java.util.UUID;

public final class InventairePartie {

    private final JavaPlugin plugin;
    private final File fichier;
    private final NamespacedKey cleSession;
    private final NamespacedKey cleType;

    public InventairePartie(JavaPlugin plugin) {
        this.plugin = plugin;
        this.fichier = new File(plugin.getDataFolder(), "recovery.yml");
        this.cleSession = new NamespacedKey(plugin, "session");
        this.cleType = new NamespacedKey(plugin, "kind");
    }

    public void sauvegarder(Player joueur, UUID session) {
        YamlConfiguration yaml = charger();
        String base = joueur.getUniqueId().toString();
        yaml.set(base + ".session", session.toString());
        yaml.set(base + ".storage", encoder(joueur.getInventory().getStorageContents()));
        yaml.set(base + ".armor", encoder(joueur.getInventory().getArmorContents()));
        yaml.set(base + ".offhand", encoder(new ItemStack[]{joueur.getInventory().getItemInOffHand()}));
        yaml.set(base + ".held", joueur.getInventory().getHeldItemSlot());
        yaml.set(base + ".level", joueur.getLevel());
        yaml.set(base + ".exp", joueur.getExp());
        yaml.set(base + ".gamemode", joueur.getGameMode().name());
        // La partie detourne la barre de vie : on rend l'etat d'origine a la fin.
        var maximum = joueur.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        yaml.set(base + ".maxhealth", maximum == null ? 20.0 : maximum.getBaseValue());
        yaml.set(base + ".health", joueur.getHealth());
        yaml.set(base + ".food", joueur.getFoodLevel());
        yaml.set(base + ".saturation", joueur.getSaturation());
        yaml.set(base + ".invulnerable", joueur.isInvulnerable());
        Location l = joueur.getLocation();
        yaml.set(base + ".location.world", l.getWorld().getUID().toString());
        yaml.set(base + ".location.x", l.getX());
        yaml.set(base + ".location.y", l.getY());
        yaml.set(base + ".location.z", l.getZ());
        yaml.set(base + ".location.yaw", l.getYaw());
        yaml.set(base + ".location.pitch", l.getPitch());
        sauver(yaml);
    }

    public boolean aRecuperer(UUID joueurId) {
        return charger().isConfigurationSection(joueurId.toString());
    }

    public boolean restaurer(Player joueur) {
        YamlConfiguration yaml = charger();
        String base = joueur.getUniqueId().toString();
        ConfigurationSection section = yaml.getConfigurationSection(base);
        if (section == null) {
            return false;
        }
        try {
            joueur.getInventory().clear();
            joueur.getInventory().setStorageContents(decoder(section.getString("storage", ""), 36));
            joueur.getInventory().setArmorContents(decoder(section.getString("armor", ""), 4));
            ItemStack[] offhand = decoder(section.getString("offhand", ""), 1);
            joueur.getInventory().setItemInOffHand(offhand[0]);
            joueur.getInventory().setHeldItemSlot(section.getInt("held", 0));
            joueur.setLevel(section.getInt("level", 0));
            joueur.setExp((float) section.getDouble("exp", 0));
            joueur.setGameMode(GameMode.valueOf(section.getString("gamemode", GameMode.SURVIVAL.name())));
            // Le maximum d'abord : setHealth se fait rogner par l'ancien plafond.
            var maximum = joueur.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
            if (maximum != null) maximum.setBaseValue(section.getDouble("maxhealth", 20.0));
            joueur.setHealth(Math.max(1.0, Math.min(section.getDouble("health", 20.0),
                    maximum == null ? 20.0 : maximum.getValue())));
            // La partie affiche les vies de cigarette en absorption : ne pas
            // laisser les coeurs noirs suivre le joueur hors de la table, et
            // rendre son plafond d'absorption vanilla (0) au passage.
            joueur.setAbsorptionAmount(0f);
            var plafondAbsorption = joueur.getAttribute(
                    org.bukkit.attribute.Attribute.MAX_ABSORPTION);
            if (plafondAbsorption != null) plafondAbsorption.setBaseValue(0.0);
            joueur.setFoodLevel(section.getInt("food", 20));
            joueur.setSaturation((float) section.getDouble("saturation", 5.0));
            joueur.setInvulnerable(section.getBoolean("invulnerable", false));
            ConfigurationSection loc = section.getConfigurationSection("location");
            if (loc != null) {
                UUID mondeId = UUID.fromString(loc.getString("world"));
                var monde = Bukkit.getWorld(mondeId);
                if (monde != null) {
                    joueur.teleport(new Location(monde, loc.getDouble("x"), loc.getDouble("y"),
                            loc.getDouble("z"), (float) loc.getDouble("yaw"),
                            (float) loc.getDouble("pitch")));
                }
            }
            yaml.set(base, null);
            sauver(yaml);
            return true;
        } catch (RuntimeException erreur) {
            plugin.getLogger().severe("restauration impossible pour " + joueur.getUniqueId()
                    + " : " + erreur.getMessage());
            return false;
        }
    }

    /**
     * Tour du joueur, fusil encore sur la table : ses objets aux slots
     * habituels, pas d'items de tir, et la main reste ou elle etait --
     * plus de saut force vers le slot 1 a chaque tour (demande user
     * 2026-08-27).
     */
    public void preparerHotbarSansFusil(Player joueur, UUID session, Participant participant) {
        joueur.getInventory().clear();
        for (DispositionHotbar.Entree entree : DispositionHotbar.sansFusil(participant.objets()).entrees()) {
            joueur.getInventory().setItem(entree.slot(),
                    creerObjet(entree.objet(), entree.quantite(), session));
        }
        sonHotbar(joueur);
    }

    /**
     * Petit pop de ramassage a chaque fois que la hotbar se garnit : le nom
     * de l'item tenu s'affiche au-dessus d'elle a cet instant, le son
     * accompagne l'apparition du texte (demande user 2026-08-28).
     */
    private void sonHotbar(Player joueur) {
        joueur.playSound(joueur.getLocation(),
                org.bukkit.Sound.ENTITY_ITEM_PICKUP, 0.6f, 1.4f);
    }

    public void preparerHotbar(Player joueur, UUID session, Participant participant, boolean actionsActives) {
        preparerHotbar(joueur, session, participant, actionsActives, "DRDONUTT");
    }

    /** Variante duel : l'item de tir porte le nom du VRAI adversaire d'en face. */
    public void preparerHotbar(Player joueur, UUID session, Participant participant,
                               boolean actionsActives, String nomAdversaire) {
        joueur.getInventory().clear();
        DispositionHotbar disposition = DispositionHotbar.creer(participant.objets(), actionsActives);
        for (DispositionHotbar.Entree entree : disposition.entrees()) {
            ItemStack item = switch (entree.type()) {
                case TIR_DRDONUTT -> creerTir(session, "shot:dealer",
                        "SHOOT " + nomAdversaire.toUpperCase(java.util.Locale.ROOT),
                        "Point the barrel across the table");
                case TIR_SOI -> creerTir(session, "shot:self",
                        "SHOOT YOURSELF", "Turn the barrel on yourself");
                case OBJET -> creerObjet(entree.objet(), entree.quantite(), session);
                case ATTENTE -> participant.porteMenottes()
                        ? creerAttenteMenotte() : creerAttente();
            };
            joueur.getInventory().setItem(entree.slot(), item);
        }
        joueur.getInventory().setHeldItemSlot(actionsActives ? 0 : 4);
        sonHotbar(joueur);
    }

    /** Slots de la question de relance : de part et d'autre du centre. */
    private static final int SLOT_CONTINUER = 3;
    private static final int SLOT_CASHOUT = 5;
    /**
     * Main posee AU MILIEU, sur le cran vide (demande user 2026-08-27).
     * Partir sur le fusil, c'etait deja avoir choisi : la table montre alors
     * un seul des deux objets et le choix se lit de travers. Depuis le
     * centre, les deux sont sur le feutre et un cran suffit pour prendre
     * l'un ou l'autre.
     */
    private static final int SLOT_MILIEU = 4;

    /**
     * Hotbar de la question de relance : le fusil pour repartir, la mallette
     * pour encaisser. Remplace les boutons cliquables du chat (demande user
     * 2026-08-27) -- le choix se fait a la molette, comme le reste du jeu.
     * Les deux items encadrent le slot central, ecartes d'un cran vide, pour
     * que le choix se lise au milieu de l'ecran et non dans un coin.
     * Textes en anglais, comme le reste de ce que voit le joueur.
     */
    public void preparerRelance(Player joueur, UUID session, long gain) {
        joueur.getInventory().clear();

        ItemStack fusil = Fusil.creer();
        Fusil.poser(fusil, "hold");
        fusil.editMeta(meta -> {
            meta.displayName(Component.text("NEXT ROUND", NamedTextColor.RED));
            meta.lore(java.util.List.of(
                    Component.text("Everything stays on the table.", NamedTextColor.GRAY),
                    Component.text("Right click to keep playing", NamedTextColor.YELLOW)));
        });
        marquer(fusil, session, "relance:continuer");
        joueur.getInventory().setItem(SLOT_CONTINUER, fusil);

        ItemStack mallette = new ItemStack(Material.PAPER);
        mallette.setData(io.papermc.paper.datacomponent.DataComponentTypes.ITEM_MODEL,
                net.kyori.adventure.key.Key.key("rr", "mallette"));
        mallette.editMeta(meta -> {
            meta.displayName(Component.text("CASH OUT  $"
                    + net.thundranode.buckshot.Mises.formater(gain), NamedTextColor.GOLD));
            meta.lore(java.util.List.of(
                    Component.text("Walk away with your winnings.", NamedTextColor.GRAY),
                    Component.text("Right click to cash out", NamedTextColor.YELLOW)));
        });
        marquer(mallette, session, "relance:cashout");
        joueur.getInventory().setItem(SLOT_CASHOUT, mallette);

        joueur.getInventory().setHeldItemSlot(SLOT_MILIEU);
        sonHotbar(joueur);
    }

    public void preparerAnimationFusil(Player joueur) {
        joueur.getInventory().clear();
        joueur.getInventory().setItem(0, Fusil.creer());
        joueur.getInventory().setHeldItemSlot(0);
    }

    public boolean estItemDePartie(ItemStack item) {
        return item != null && item.getPersistentDataContainer().has(cleSession, PersistentDataType.STRING);
    }

    public String type(ItemStack item) {
        return item == null ? null : item.getPersistentDataContainer().get(cleType, PersistentDataType.STRING);
    }

    public Objet objet(ItemStack item) {
        String type = type(item);
        if (type == null || !type.startsWith("item:")) return null;
        return Objet.valueOf(type.substring(5));
    }

    private ItemStack creerTir(UUID session, String type, String nom, String aide) {
        ItemStack item = Fusil.creer();
        // L'etat de visee est pose une fois pour toutes, pas a chaque tick :
        // le pack fait le reste tout seul. use_duration a zero affiche la pose
        // de repos, et des que le joueur maintient le clic droit il enchaine
        // la montee en joue puis la boucle de tremblement.
        //
        // Avant editMeta : Fusil.poser reapplique le nom d'objet par defaut et
        // ecraserait le nom pose juste apres.
        Fusil.poser(item, "shot:self".equals(type) ? "aim_self" : "aim_front");
        item.editMeta(meta -> {
            meta.displayName(Component.text(nom, NamedTextColor.RED));
            meta.lore(java.util.List.of(Component.text(aide, NamedTextColor.GRAY),
                    Component.text("Hold right click to aim, release to fire",
                            NamedTextColor.YELLOW)));
        });
        marquer(item, session, type);
        return item;
    }

    /**
     * Item purement visuel, sans marquage de session.
     *
     * <p>Sert a montrer un objet dans la main de DrDonutt. Le marquage est
     * volontairement absent : cet item n'est jamais ramassable ni jouable, et
     * lui donner une session le ferait passer pour un item de partie.
     */
    public static ItemStack vitrine(Objet objet) {
        ItemStack item = itemDeBase(objet);
        item.editMeta(meta -> meta.displayName(
                Component.text(nom(objet), NamedTextColor.GOLD)));
        return item;
    }

    private static Material materiau(Objet objet) {
        return switch (objet) {
            case CIGARETTES -> Material.PAPER;
            case BIERE -> Material.POTION;
            case MENOTTES -> Material.TRIPWIRE_HOOK;
            case COUTEAU -> Material.IRON_SWORD;
            case LOUPE -> Material.SPYGLASS;
        };
    }

    /**
     * Les modeles Blockbench de l'utilisateur habillent les objets vanilla,
     * dans la hotbar du joueur comme dans la main du dealer.
     */
    private static ItemStack itemDeBase(Objet objet) {
        ItemStack item = new ItemStack(materiau(objet));
        String modele = switch (objet) {
            case CIGARETTES -> "paquet_cigarettes";
            case MENOTTES -> "menottes";
            // Bouteille Blockbench (2026-08-27) : remplace la fiole de
            // potion vanilla, dans la hotbar comme dans la main du dealer.
            case BIERE -> "biere";
            default -> null;
        };
        if (modele != null) {
            item.setData(io.papermc.paper.datacomponent.DataComponentTypes.ITEM_MODEL,
                    net.kyori.adventure.key.Key.key("rr", modele));
        }
        return item;
    }

    private ItemStack creerObjet(Objet objet, int quantite, UUID session) {
        ItemStack item = itemDeBase(objet);
        item.setAmount(quantite);
        item.editMeta(meta -> {
            meta.displayName(Component.text(nom(objet), NamedTextColor.GOLD));
            meta.lore(java.util.List.of(Component.text(description(objet), NamedTextColor.GRAY),
                    Component.text("Right click to use", NamedTextColor.YELLOW)));
        });
        marquer(item, session, "item:" + objet.name());
        return item;
    }

    /**
     * Item d'attente du joueur MENOTTE : l'arbalete chargee habillee en
     * menottes remplace l'horloge -- il voit ses bracelets en main, et
     * l'arbalete chargee donne la pose bras crossbow a tout le monde.
     * La hotbar etant reappliquee a chaque phase, c'est ELLE qui doit
     * porter les menottes, pas un echange de main ponctuel (ecrase).
     */
    private static ItemStack creerAttenteMenotte() {
        // Menottes TENUES comme un objet ordinaire (demande user 2026-08-27,
        // confirmee le 2026-09-02 apres un essai de bracelets 3D sur les
        // humains : pose crossbow + anneaux suivant la tete, abandonne --
        // un temoin Bedrock ne voit aucun display, et le resultat Java ne
        // valait pas la fragilite). Les bracelets 3D restent au dealer.
        ItemStack item = new ItemStack(Material.PAPER);
        item.setData(io.papermc.paper.datacomponent.DataComponentTypes.ITEM_MODEL,
                net.kyori.adventure.key.Key.key("rr", "menottes"));
        item.editMeta(meta -> meta.displayName(
                Component.text("CUFFED", NamedTextColor.RED)));
        return item;
    }

    private static ItemStack creerAttente() {
        ItemStack item = new ItemStack(Material.CLOCK);
        item.editMeta(meta -> {
            meta.displayName(Component.text("DRDONUTT'S TURN", NamedTextColor.RED));
            meta.lore(java.util.List.of(Component.text(
                    "Shooting and items locked", NamedTextColor.GRAY)));
        });
        return item;
    }

    private void marquer(ItemStack item, UUID session, String type) {
        item.editPersistentDataContainer(pdc -> {
            pdc.set(cleSession, PersistentDataType.STRING, session.toString());
            pdc.set(cleType, PersistentDataType.STRING, type);
        });
    }

    private static String nom(Objet objet) {
        return switch (objet) {
            case CIGARETTES -> "CIGARETTES (+1 life)";
            case BIERE -> "Beer";
            case MENOTTES -> "Handcuffs";
            case COUTEAU -> "Knife";
            case LOUPE -> "Magnifier";
        };
    }

    private static String description(Objet objet) {
        return switch (objet) {
            case CIGARETTES -> "Restores one life, up to 5";
            case BIERE -> "Ejects the chambered shell";
            // "Un tour" est vrai du cote de DrDonutt, mais utiliser un
            // objet ne rend pas la main : on joue donc deux fois de suite.
            // Ne dire que la moitie faisait croire a un bug de comptage.
            case MENOTTES -> "DrDonutt skips his next turn: you play twice";
            case COUTEAU -> "Doubles the damage of the next live shot";
            case LOUPE -> "Inspects the chambered shell";
        };
    }

    private YamlConfiguration charger() {
        return YamlConfiguration.loadConfiguration(fichier);
    }

    private void sauver(YamlConfiguration yaml) {
        try {
            if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
                throw new IOException("creation du dossier plugin impossible");
            }
            yaml.save(fichier);
        } catch (IOException erreur) {
            throw new IllegalStateException("ecriture recovery.yml impossible", erreur);
        }
    }

    private static String encoder(ItemStack[] items) {
        return Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(items));
    }

    private static ItemStack[] decoder(String texte, int taille) {
        if (texte == null || texte.isBlank()) return new ItemStack[taille];
        ItemStack[] lus = ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(texte));
        if (lus.length != taille) {
            throw new IllegalStateException("unexpected inventory size: " + lus.length);
        }
        return lus;
    }
}
