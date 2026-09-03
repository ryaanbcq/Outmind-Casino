package net.thundranode.buckshot.paper;

import net.kyori.adventure.text.Component;
import net.thundranode.buckshot.Cigarette;
import net.thundranode.buckshot.Fusil;
import net.thundranode.buckshot.jeu.Acteur;
import org.bukkit.*;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class MiseEnScene implements ScenePartie {

    private final JavaPlugin plugin;
    private final NamespacedKey cleTable;
    /** Identifie A QUELLE table appartient une entite : deux tables peuvent
     * partager un monde, le nettoyage ne doit balayer que les siennes. */
    private final NamespacedKey cleTableId;
    private final double rayonDepart;
    private final double rayonArret;
    private final DrDonuttNpc dealer;
    /**
     * Debut de lecture par joueur, en secondes de {@link #verifierMusique}.
     *
     * <p>Minecraft ne sait pas boucler un son : c'est ici qu'on retient depuis
     * quand le morceau tourne pour le relancer avant qu'il se taise.
     */
    private final Map<UUID, Long> musique = new HashMap<>();
    /**
     * Duree du morceau, en secondes, une de moins que les 304,75 reelles pour
     * que la reprise chevauche la fin plutot que de laisser un blanc.
     */
    private static final long DUREE_MUSIQUE_S = 303L;
    /** Theme du round final, plus long : 394,4 s reelles. */
    private static final long DUREE_FINALE_S = 393L;
    /** lastlife.ogg fait 128,8 s ; relance juste avant la fin, comme les autres. */
    private static final long DUREE_DERNIER_COEUR_S = 127L;
    /** finallastlife.ogg fait 218,6 s. */
    private static final long DUREE_DERNIER_COEUR_FINAL_S = 217L;
    private static final String PISTE_NORMALE = "rr:music.dealer";
    private static final String PISTE_FINALE = "rr:music.final";
    // Workflow musical (user 2026-08-27). L'OST du round final touche
    // ATTEND encore son fichier : en placeholder elle pointe sur le theme
    // final, la bascule du round 3 est donc inerte pour l'instant.
    private static final String PISTE_FINALE_TOUCHEE = PISTE_FINALE;
    /** OST du dernier coeur : lastlife.MP3 fourni le 2026-08-27. */
    private static final String PISTE_DERNIER_COEUR = "rr:music.lastheart";
    /**
     * OST du dernier coeur au round FINAL, du joueur comme de DrDonutt :
     * final-last-life.MP3 fourni le 2026-08-27.
     */
    private static final String PISTE_DERNIER_COEUR_FINAL = "rr:music.lastheart_final";
    /** Palettes : fremissement de la decharge, puis duree de la sortie. */
    private static final int TREMBLE_TICKS = 10;
    private static final int ECART_TICKS = 14;

    /** Piste en cours, changee par {@link #musiquePourRound}. */
    private String piste = PISTE_NORMALE;

    private final Set<UUID> bloqueeJusquaSortie = new HashSet<>();
    private TableConfig config;
    private ItemDisplay fusil;
    private ItemDisplay fusilVise;
    /** Les deux objets poses sur le feutre pendant la question de relance. */
    private ItemDisplay relanceFusil;
    private ItemDisplay relanceMallette;
    /** La mallette posee sur la table a la victoire. */
    private ItemDisplay malletteVictoire;
    /** Le fusil pose devant le joueur au debut de son tour, et sa hitbox. */
    private ItemDisplay fusilAPrendre;
    private org.bukkit.entity.Interaction priseFusil;
    /** Doublure du fusil pose pour les clients Bedrock, cachee aux Java. */
    private org.bukkit.entity.Item fusilBedrock;
    /** Cote ou le fusil est actuellement pose, null si absent. */
    private Acteur fusilPoseCote;
    /** La tete de DrDonutt posee sur le feutre a sa mort. */
    private ItemDisplay teteMort;
    /** Coeurs du joueur assis, pour les spectateurs (cache au joueur). */
    private org.bukkit.entity.TextDisplay viesJoueur;
    /** Coeurs du second joueur (duel PvP), percheee cote place du dealer. */
    private org.bukkit.entity.TextDisplay viesJoueur2;
    /** Corps du joueur effondre, pour les spectateurs. */
    private final CorpsJoueur corpsJoueur;
    /** Taches au sol autour du corps, retirees avec lui. */
    private final java.util.List<Entity> sangChute = new java.util.ArrayList<>();
    /** Numerote chaque corps pose : le filet de retrait ne fauche que le sien. */
    private int generationCorps;
    /** Cage de barrieres autour du siege, posee le temps d'une partie. */
    private final java.util.List<org.bukkit.block.Block> cage = new java.util.ArrayList<>();
    /** Profil de la tete, copie sur l'entite du PNJ a l'instant de la mort. */
    private com.destroystokyo.paper.profile.PlayerProfile profilTete;
    private BukkitTask tacheVise;
    private String poseVise;
    private int brasVise = -1;
    /** Poses d'essai visibles posees sur soi par /rr garro, et leur tache. */
    private final Map<UUID, ItemDisplay> essais = new HashMap<>();
    private final Map<UUID, BukkitTask> tachesEssai = new HashMap<>();
    /** Menottes portees par les participants menottes, et leur suivi. */
    private final Map<Acteur, ItemDisplay> menottesPortees = new java.util.EnumMap<>(Acteur.class);
    private Player porteurMenottesJoueur;
    private final Geste geste;
    /** Duel : l'humain qui tient le camp DEALER (null en solo = le PNJ). */
    private Player porteurMenottesJoueur2;
    private BukkitTask tacheMenottes;
    /** Postures reglees portees par de vrais joueurs, et leur cap fige. */
    private final Map<UUID, ItemDisplay> affichages = new HashMap<>();
    private final Map<UUID, String> posesJoueur = new HashMap<>();
    private final Map<UUID, Float> capsJoueur = new HashMap<>();
    /** Eclaboussures posees sur le feutre, remplacees a chaque round. */
    private final java.util.List<Entity> sang = new java.util.ArrayList<>();
    private final java.util.Random dessin = new java.util.Random();
    /** Nombre de textures d'eclaboussure produites par tools/gen_blood.py. */
    private static final int VARIANTES_SANG = 6;
    private BukkitTask tacheMusique;
    private long secondes;

    public MiseEnScene(JavaPlugin plugin, double rayonDepart, double rayonArret, TableConfig config) {
        this.plugin = plugin;
        this.geste = new Geste(plugin);
        this.rayonDepart = rayonDepart;
        this.rayonArret = rayonArret;
        this.cleTable = new NamespacedKey(plugin, "table_entity");
        this.cleTableId = new NamespacedKey(plugin, "table_id");
        this.dealer = new DrDonuttNpc(plugin);
        this.corpsJoueur = new CorpsJoueur(plugin);
        this.config = config;
    }

    public TableConfig config() { return config; }

    /** Centre configure, ou null si pas de table ou monde disparu. */
    public Location centreConfigure() {
        if (config == null) return null;
        World monde = Bukkit.getWorld(config.monde());
        if (monde == null) return null;
        return new Location(monde, config.x(), config.y(), config.z());
    }

    public void demarrer() {
        // centreConfigure() nul = monde absent (supprime ?) : on ne
        // reconstruit pas, mais la table reste en config au cas ou le monde
        // revienne — reparerSiNecessaire retentera.
        if (centreConfigure() != null) reconstruire();
        tacheMusique = Bukkit.getScheduler().runTaskTimer(plugin, this::verifierMusique, 20L, 20L);
    }

    /** Installe la table (la persistance de la liste est au plugin). */
    public void creer(TableConfig nouvelle) {
        config = nouvelle;
        reconstruire();
    }

    /**
     * Retire les elements de table presents dans un rayon, et renvoie leur nombre.
     *
     * Le balayage part des entites marquees, pas de la config : c'est le seul
     * moyen de rattraper une table orpheline, laissee derriere par un
     * /rr table creer dont la config a ensuite designe un autre endroit.
     */
    /**
     * Balaye les entites de table orphelines dans un rayon, en EPARGNANT
     * celles des tables encore vivantes (leurs ids sont passes en parametre).
     * Multi-tables : deux tables peuvent etre a portee l'une de l'autre, le
     * menage de l'une ne doit pas eventrer sa voisine.
     */
    public static int balayerOrphelins(JavaPlugin plugin, Location lieu, double rayon,
                                       java.util.Set<String> idsVivants) {
        NamespacedKey cle = new NamespacedKey(plugin, "table_entity");
        NamespacedKey cleId = new NamespacedKey(plugin, "table_id");
        double carre = rayon * rayon;
        int retires = 0;
        for (Entity entite : lieu.getWorld().getEntities()) {
            if (!entite.getPersistentDataContainer().has(cle, PersistentDataType.STRING)) continue;
            if (entite.getLocation().distanceSquared(lieu) > carre) continue;
            String id = entite.getPersistentDataContainer().get(cleId, PersistentDataType.STRING);
            if (id != null && idsVivants.contains(id)) continue;
            entite.remove();
            retires++;
        }
        return retires;
    }

    public String description() {
        if (config == null) return "not set up";
        return config.monde() + " " + String.format("%.1f %.1f %.1f", config.x(), config.y(), config.z());
    }

    /** Vrai si la table configuree est DANS ce rayon autour du lieu. */
    public boolean configureeIci(Location lieu, double rayon) {
        Location centre = centreConfigure();
        if (centre == null || !centre.getWorld().equals(lieu.getWorld())) return false;
        return lieu.distanceSquared(centre) <= rayon * rayon;
    }

    private void reconstruire() {
        nettoyerDisplays();
        Location centre = config.centre();
        float rotation = (float) Math.toRadians(-config.yaw());
        // Table refaite le 2026-08-23 : feutre olive ENCASTRE dans un plateau
        // de chene noir a rebord clair, tablier sous le plateau, pieds trapus
        // relies par des entretoises, coins ferres. Chaque ligne = un pave
        // {min local x, min y, min z, taille x, y, z} tourne autour de l'axe
        // du centre. CONTRAINTE : le dessus du feutre reste a +0.90 — le sang
        // (0.902) et le fusil (0.95) y sont calibres.
        record Pave(Material matiere, float x, float y, float z,
                    float sx, float sy, float sz) {}
        var paves = new Pave[]{
                // feutre (depasse de 2 cm du plateau : lisiere encastree)
                new Pave(Material.GREEN_TERRACOTTA, -2.35f, 0.84f, -1.10f, 4.70f, 0.06f, 2.20f),
                // plateau
                new Pave(Material.DARK_OAK_PLANKS, -2.60f, 0.70f, -1.35f, 5.20f, 0.18f, 2.70f),
                // rebord clair autour du feutre
                new Pave(Material.STRIPPED_DARK_OAK_WOOD, -2.60f, 0.86f, -1.35f, 5.20f, 0.10f, 0.25f),
                new Pave(Material.STRIPPED_DARK_OAK_WOOD, -2.60f, 0.86f, 1.10f, 5.20f, 0.10f, 0.25f),
                new Pave(Material.STRIPPED_DARK_OAK_WOOD, -2.60f, 0.86f, -1.10f, 0.25f, 0.10f, 2.20f),
                new Pave(Material.STRIPPED_DARK_OAK_WOOD, 2.35f, 0.86f, -1.10f, 0.25f, 0.10f, 2.20f),
                // coins ferres, un poil plus hauts et plus larges que le rebord
                new Pave(Material.IRON_BLOCK, -2.62f, 0.86f, -1.37f, 0.22f, 0.12f, 0.22f),
                new Pave(Material.IRON_BLOCK, 2.40f, 0.86f, -1.37f, 0.22f, 0.12f, 0.22f),
                new Pave(Material.IRON_BLOCK, -2.62f, 0.86f, 1.15f, 0.22f, 0.12f, 0.22f),
                new Pave(Material.IRON_BLOCK, 2.40f, 0.86f, 1.15f, 0.22f, 0.12f, 0.22f),
                // tablier sous le plateau
                new Pave(Material.DARK_OAK_PLANKS, -2.45f, 0.46f, -1.16f, 4.90f, 0.26f, 0.10f),
                new Pave(Material.DARK_OAK_PLANKS, -2.45f, 0.46f, 1.06f, 4.90f, 0.26f, 0.10f),
                new Pave(Material.DARK_OAK_PLANKS, -2.45f, 0.46f, -1.16f, 0.10f, 0.26f, 2.32f),
                new Pave(Material.DARK_OAK_PLANKS, 2.35f, 0.46f, -1.16f, 0.10f, 0.26f, 2.32f),
                // pieds
                new Pave(Material.DARK_OAK_LOG, -2.42f, 0f, -1.14f, 0.35f, 0.72f, 0.35f),
                new Pave(Material.DARK_OAK_LOG, 2.07f, 0f, -1.14f, 0.35f, 0.72f, 0.35f),
                new Pave(Material.DARK_OAK_LOG, -2.42f, 0f, 0.79f, 0.35f, 0.72f, 0.35f),
                new Pave(Material.DARK_OAK_LOG, 2.07f, 0f, 0.79f, 0.35f, 0.72f, 0.35f),
                // entretoises basses entre pieds + traverse centrale
                new Pave(Material.STRIPPED_DARK_OAK_WOOD, -2.315f, 0.14f, -0.965f, 0.14f, 0.14f, 1.93f),
                new Pave(Material.STRIPPED_DARK_OAK_WOOD, 2.175f, 0.14f, -0.965f, 0.14f, 0.14f, 1.93f),
                new Pave(Material.STRIPPED_DARK_OAK_WOOD, -2.245f, 0.15f, -0.07f, 4.49f, 0.12f, 0.14f),
        };
        for (Pave pave : paves) {
            // L'offset du pave doit tourner AVEC la table : la leftRotation
            // du display ne fait pivoter le cube qu'autour de son propre
            // coin, pas sa position. Sans cette rotation de l'offset, toute
            // table posee a un yaw non nul se disloquait.
            Vector3f decale = new Vector3f(pave.x(), 0, pave.z());
            new AxisAngle4f(rotation, 0, 1, 0).transform(decale);
            spawnBloc(centre.clone().add(0, pave.y(), 0), pave.matiere(),
                    decale, new Vector3f(pave.sx(), pave.sy(), pave.sz()), rotation);
        }
        poserBarrieres(centre, rotation);

        // Deco posee sur le feutre, tournee avec la table comme le sang :
        // le paquet de cigarettes couche cote joueur, une cigarette sortie
        // a cote, et deux bougies noires fondues aux coins opposes. Tout
        // est marque "deco" et repart avec nettoyerDisplays().
        double cosDeco = Math.cos(rotation), sinDeco = Math.sin(rotation);
        var paquet = new ItemStack(Material.PAPER);
        paquet.setData(io.papermc.paper.datacomponent.DataComponentTypes.ITEM_MODEL,
                net.kyori.adventure.key.Key.key("rr", "paquet_cigarettes"));
        // Table duel : la bougie diagonale (ci-dessous) occupe le coin
        // (-2.4, -0.4) et tombait sur le paquet ; paquet et cigarette
        // glissent vers le milieu du bord long (remarque user 2026-09-02).
        boolean duel = config.estDuel();
        poserDecoTable(centre, cosDeco, sinDeco, duel ? -0.90 : -1.85, 0.952,
                duel ? -0.75 : -0.72, paquet, 0.45f, (float) (Math.PI / 2), 0.55f);
        poserDecoTable(centre, cosDeco, sinDeco, duel ? -0.50 : -1.45, 0.916,
                duel ? -0.50 : -0.45,
                net.thundranode.buckshot.Cigarette.creer("s1"), -0.7f, 0f, 0.5f);
        var menottesDeco = new ItemStack(Material.TRIPWIRE_HOOK);
        menottesDeco.setData(io.papermc.paper.datacomponent.DataComponentTypes.ITEM_MODEL,
                net.kyori.adventure.key.Key.key("rr", "menottes"));
        poserDecoTable(centre, cosDeco, sinDeco, 1.75, 0.93, -0.65, menottesDeco,
                0.5f, (float) (Math.PI / 2), 0.6f);
        // Table solo : les deux bougies vivent cote DrDonutt, comme toujours.
        // Table duel : une bougie par joueur, aux coins opposes en diagonale —
        // les deux du meme cote encombraient la place du joueur 2 (remarque
        // user 2026-08-30).
        float[][] bougies = duel
                ? new float[][]{{1.6f, 0.45f, 3}, {-2.4f, -0.4f, 2}}
                : new float[][]{{1.6f, 0.45f, 3}, {-2.4f, 0.4f, 2}};
        for (float[] bougie : bougies) {
            Vector3f decale = new Vector3f(bougie[0], 0, bougie[1]);
            new AxisAngle4f(rotation, 0, 1, 0).transform(decale);
            spawnBlocData(centre.clone().add(0, 0.90, 0),
                    Bukkit.createBlockData("minecraft:black_candle[candles="
                            + (int) bougie[2] + ",lit=true]"),
                    decale, new Vector3f(0.8f), rotation);
        }
        fusil = centre.getWorld().spawn(centre.clone().add(0, 0.95, 0), ItemDisplay.class, display -> {
            marquer(display, "fusil");
            // Table nue par defaut : le display reste comme ancre (configuree()
            // s'appuie dessus) mais aucun fusil de decor n'y est pose.
            display.setItemStack(ItemStack.empty());
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            display.setBillboard(Display.Billboard.FIXED);
            display.setTransformation(new Transformation(new Vector3f(),
                    new AxisAngle4f(rotation, 0, 1, 0), new Vector3f(0.65f), new AxisAngle4f()));
        });
        // Le dealer fixe l'axe de la table (placeJoueur est sur l'axe), PAS la
        // cellule du joueur qui est un pas a cote : vise sur la cellule, son
        // regard partait de biais ("il regarde plus droit devant nous").
        // Table de DUEL : pas de DrDonutt du tout, la place d'en face est au
        // second joueur. Tous les appels dealer.* sont gardes npc == null.
        if (!config.estDuel()) {
            dealer.apparaitre(config.placeDealer(), config.placeJoueur());
        }
    }

    /**
     * Le sang s'accumule sur le feutre au fil des rounds.
     *
     * <p>Repose tout a chaque appel plutot que d'ajouter : un round qui
     * recommence ne doit pas empiler deux generations d'eclaboussures.
     */
    @Override
    public void sangPourRound(int round) {
        for (Entity entite : sang) entite.remove();
        sang.clear();
        if (config == null || round < 2) return;
        // Le round final est un carnage, pas une progression lineaire.
        int taches = round >= 3 ? 26 : 8;
        // Les trois dernieres variantes sont du sang seche : au round 2 tout
        // vient de tomber, au round final les deux ages coexistent.
        int variantes = round >= 3 ? VARIANTES_SANG : VARIANTES_SANG / 2;
        Location centre = config.centre();
        float rotation = (float) Math.toRadians(-config.yaw());
        double cos = Math.cos(rotation), sin = Math.sin(rotation);

        // Placement stratifie : une tache par case d'une grille melangee,
        // plutot qu'un tirage uniforme. A vingt-six taches sur un feutre de
        // 4,5 x 2,1, l'uniforme en empile trois au meme endroit et en laisse
        // des zones vides -- ce sont ces amas qui se chevauchaient.
        int colonnes = Math.max(1, (int) Math.ceil(Math.sqrt(taches * (4.5 / 2.1))));
        int lignes = Math.max(1, (int) Math.ceil((double) taches / colonnes));
        java.util.List<Integer> cases = new java.util.ArrayList<>();
        for (int k = 0; k < colonnes * lignes; k++) cases.add(k);
        java.util.Collections.shuffle(cases, dessin);

        for (int i = 0; i < taches; i++) {
            int cellule = cases.get(i);
            double u = (((cellule % colonnes) + dessin.nextDouble()) / colonnes * 2 - 1) * 2.25;
            double v = (((double) (cellule / colonnes) + dessin.nextDouble()) / lignes * 2 - 1) * 1.05;
            // Chaque decalque monte d'un cheveu de plus que le precedent.
            // Sans cet etagement, deux quads qui se recouvrent occupent le
            // meme plan de profondeur et clignotent l'un a travers l'autre.
            double hauteur = 0.902 + i * 0.001;
            // Coordonnees dans le repere du feutre, puis tournees avec lui :
            // la table n'est pas alignee sur les axes du monde.
            Location lieu = centre.clone().add(u * cos + v * sin, hauteur, -u * sin + v * cos);
            // Le decalque est un quad plat centre sur l'entite : il suffit de
            // le tourner a plat et de l'echelonner, sans decalage a calculer.
            float taille = 0.45f + dessin.nextFloat() * 0.75f;
            var pivot = new AxisAngle4f(dessin.nextFloat() * (float) (Math.PI * 2), 0, 1, 0);
            var decalque = new ItemStack(Material.PAPER);
            decalque.setData(io.papermc.paper.datacomponent.DataComponentTypes.ITEM_MODEL,
                    net.kyori.adventure.key.Key.key("rr", "blood"));
            decalque.setData(io.papermc.paper.datacomponent.DataComponentTypes.CUSTOM_MODEL_DATA,
                    io.papermc.paper.datacomponent.item.CustomModelData.customModelData()
                            .addString("blood_" + dessin.nextInt(variantes)).build());
            sang.add(lieu.getWorld().spawn(lieu, ItemDisplay.class, display -> {
                marquer(display, "sang");
                display.setItemStack(decalque);
                display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                display.setBillboard(Display.Billboard.FIXED);
                display.setTransformation(new Transformation(new Vector3f(),
                        pivot, new Vector3f(taille), new AxisAngle4f()));
            }));
        }
    }

    /** Blocs barriere poses sous la table, retenus pour le nettoyage. */
    private final java.util.List<org.bukkit.block.Block> barrieres = new java.util.ArrayList<>();

    /**
     * La table n'etait que des displays : on la traversait. Des barrieres
     * invisibles remplissent son emprise sur deux blocs de haut — assez pour
     * qu'on ne puisse ni la traverser ni sauter dessus. Seuls des blocs d'AIR
     * sont remplaces, et chaque bloc pose est memorise pour le retrait.
     */
    private void poserBarrieres(Location centre, float rotation) {
        retirerBarrieres();
        World monde = centre.getWorld();
        double cos = Math.cos(rotation), sin = Math.sin(rotation);
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                // Une seule couche, a hauteur de la table (demande user
                // 2026-08-23) : on ne la traverse pas, mais on peut encore
                // se pencher ou sauter par-dessus.
                for (int dy = 0; dy <= 0; dy++) {
                    org.bukkit.block.Block bloc = monde.getBlockAt(
                            centre.getBlockX() + dx, centre.getBlockY() + dy, centre.getBlockZ() + dz);
                    double wx = bloc.getX() + 0.5 - centre.getX();
                    double wz = bloc.getZ() + 0.5 - centre.getZ();
                    // Repere local de la table : inverse de la rotation des paves.
                    double lx = wx * cos - wz * sin;
                    double lz = wx * sin + wz * cos;
                    if (Math.abs(lx) <= 2.6 && Math.abs(lz) <= 1.35) {
                        if (bloc.getType() == Material.AIR) {
                            bloc.setType(Material.BARRIER, false);
                            barrieres.add(bloc);
                        } else if (bloc.getType() == Material.BARRIER) {
                            // Barriere heritee d'un demarrage precedent : la
                            // reprendre en gestion plutot que de la laisser
                            // orpheline (le serveur redemarre sans nettoyer).
                            barrieres.add(bloc);
                        }
                    }
                }
            }
        }
    }

    private void retirerBarrieres() {
        for (org.bukkit.block.Block bloc : barrieres) {
            if (bloc.getType() == Material.BARRIER) bloc.setType(Material.AIR, false);
        }
        barrieres.clear();
    }

    /**
     * Le fusil du tour du joueur, couche sur le feutre de son cote, un peu
     * de biais. L'Interaction invisible au-dessus est ce que le client sait
     * viser : les ItemDisplay n'ont pas de hitbox.
     */
    @Override
    public void poserFusilAPrendre() {
        if (config == null || fusilPoseCote == Acteur.JOUEUR) return;
        retirerFusilAPrendre();
        Location lieu = poserFusilTable(-0.45, 0.30f);
        Location socle = lieu.clone();
        socle.setY(config.centre().getY() + 0.90);
        priseFusil = lieu.getWorld().spawn(socle, org.bukkit.entity.Interaction.class, zone -> {
            marquer(zone, "prise");
            zone.setInteractionWidth(1.3f);
            zone.setInteractionHeight(0.30f);
            zone.setResponsive(true);
        });
        fusilPoseCote = Acteur.JOUEUR;
    }

    @Override
    public void poserFusilDealer() {
        if (config == null || fusilPoseCote == Acteur.DEALER) return;
        retirerFusilAPrendre();
        // Crosse vers lui : le fusil du cote dealer pointe a l'oppose de
        // celui du joueur, comme s'il venait d'y etre glisse.
        poserFusilTable(0.45, (float) Math.PI - 0.30f);
        fusilPoseCote = Acteur.DEALER;
    }

    /** Le fusil couche a plat sur le feutre, a {@code v} du centre. */
    private Location poserFusilTable(double v, float biais) {
        Location centre = config.centre();
        float rotation = (float) Math.toRadians(-config.yaw());
        double cos = Math.cos(rotation), sin = Math.sin(rotation);
        Location lieu = centre.clone().add(v * sin, 0.94, v * cos);
        fusilAPrendre = lieu.getWorld().spawn(lieu, ItemDisplay.class, display -> {
            marquer(display, "prise");
            display.setItemStack(net.thundranode.buckshot.Fusil.creer());
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            display.setBillboard(Display.Billboard.FIXED);
            display.setTransformation(new Transformation(new Vector3f(),
                    new Quaternionf().rotationYXZ(rotation + biais, (float) (Math.PI / 2), 0),
                    new Vector3f(0.6f), new Quaternionf()));
        });
        poserFusilBedrock(lieu);
        lieu.getWorld().playSound(lieu, Sound.BLOCK_WOOD_PLACE, 0.6f, 1.2f);
        return lieu;
    }

    @Override
    public void retirerFusilAPrendre() {
        if (fusilAPrendre != null) { fusilAPrendre.remove(); fusilAPrendre = null; }
        if (priseFusil != null) { priseFusil.remove(); priseFusil = null; }
        retirerFusilBedrock();
        fusilPoseCote = null;
    }

    // ==== Miroir Bedrock ====
    // Geyser ne transmet AUCUNE display entity aux clients Bedrock : pour eux
    // la table (BlockDisplays etires) et le fusil pose (ItemDisplay)
    // n'existent pas. Le miroir leur montre a la place les blocs barriere de
    // l'emprise habilles en vrais blocs (sendBlockChange, purement client
    // par joueur : les Java gardent la table en displays) et une entite item
    // fantome portant le fusil, cachee aux clients Java.

    /** Renvoie la table en blocs a un client Bedrock (repete : un resend de
     *  chunk cote serveur ecrase silencieusement les blocs client-side). */
    void envoyerTableBedrock(Player joueur) {
        if (config == null) return;
        Location centre = config.centre();
        float rotation = (float) Math.toRadians(-config.yaw());
        double cos = Math.cos(rotation), sin = Math.sin(rotation);
        var feutre = Material.GREEN_TERRACOTTA.createBlockData();
        var bois = Material.DARK_OAK_PLANKS.createBlockData();
        for (org.bukkit.block.Block bloc : barrieres) {
            double wx = bloc.getX() + 0.5 - centre.getX();
            double wz = bloc.getZ() + 0.5 - centre.getZ();
            double lx = wx * cos - wz * sin;
            double lz = wx * sin + wz * cos;
            // Feutre au coeur, bois sur tout le pourtour : plus etroit que le
            // vrai feutre (2.35 x 1.10) pour garantir un anneau de bois malgre
            // la resolution d'un bloc.
            joueur.sendBlockChange(bloc.getLocation(),
                    Math.abs(lx) <= 1.85 && Math.abs(lz) <= 0.6 ? feutre : bois);
        }
    }

    private void poserFusilBedrock(Location lieu) {
        retirerFusilBedrock();
        fusilBedrock = lieu.getWorld().dropItem(lieu.clone().add(0, 0.05, 0),
                net.thundranode.buckshot.Fusil.creer(), item -> {
            marquer(item, "miroir");
            item.setGravity(false);
            item.setVelocity(new org.bukkit.util.Vector());
            item.setPickupDelay(32767);
            item.setCanMobPickup(false);
            item.setCanPlayerPickup(false);
            item.setUnlimitedLifetime(true);
            item.setPersistent(false);
        });
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!EcouteurPartie.estBedrock(p)) p.hideEntity(plugin, fusilBedrock);
        }
    }

    private void retirerFusilBedrock() {
        if (fusilBedrock != null) { fusilBedrock.remove(); fusilBedrock = null; }
    }

    /** A l'arrivee d'un joueur : cacher la doublure du fusil aux clients Java. */
    void masquerMiroirPour(Player joueur) {
        if (fusilBedrock != null && !EcouteurPartie.estBedrock(joueur)) {
            joueur.hideEntity(plugin, fusilBedrock);
        }
    }

    @Override
    public boolean estPriseFusil(org.bukkit.entity.Entity entite) {
        return priseFusil != null && priseFusil.equals(entite);
    }

    /**
     * Le choix de relance se pose sur le feutre, pas seulement dans la
     * hotbar : fusil a gauche, mallette a droite, du point de vue du joueur
     * assis (demande user 2026-08-27).
     *
     * <p>Repere du feutre, le meme que la deco : {@code u} va vers la GAUCHE
     * du joueur, {@code v} vers le fond de la table. La gauche est donc
     * u positif, la droite u negatif.
     */
    @Override
    public void montrerChoixRelance() {
        masquerChoixRelance();
        if (config == null) return;
        Location centre = config.centre();
        float rotation = (float) Math.toRadians(-config.yaw());
        double cos = Math.cos(rotation), sin = Math.sin(rotation);
        relanceFusil = poserChoixRelance(centre, cos, sin, 1.05, -0.30,
                rotation, itemFusilTable(), 0.55f);
        relanceMallette = poserChoixRelance(centre, cos, sin, -1.05, -0.30,
                rotation, itemMalletteTable(), 0.55f);
    }

    /**
     * L'objet pris en main quitte le feutre.
     *
     * <p>L'ItemStack est vide plutot que l'entite retiree : le display reste
     * en place, donc reposer l'objet ne coute pas un respawn et ne clignote
     * pas quand le joueur fait tourner la molette entre les deux.
     */
    @Override
    public void choixRelanceEnMain(String type) {
        if (relanceFusil != null) {
            relanceFusil.setItemStack("relance:continuer".equals(type)
                    ? ItemStack.empty() : itemFusilTable());
        }
        if (relanceMallette != null) {
            relanceMallette.setItemStack("relance:cashout".equals(type)
                    ? ItemStack.empty() : itemMalletteTable());
        }
    }

    @Override
    public void masquerChoixRelance() {
        if (relanceFusil != null) relanceFusil.remove();
        if (relanceMallette != null) relanceMallette.remove();
        relanceFusil = null;
        relanceMallette = null;
    }

    private ItemDisplay poserChoixRelance(Location centre, double cos, double sin,
                                          double u, double v, float rotation,
                                          ItemStack item, float echelle) {
        Location lieu = centre.clone().add(u * cos + v * sin, 0.94, -u * sin + v * cos);
        return lieu.getWorld().spawn(lieu, ItemDisplay.class, display -> {
            marquer(display, "relance");
            display.setItemStack(item);
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            display.setBillboard(Display.Billboard.FIXED);
            // Couche a plat sur le feutre (quart de tour sur X) et aligne sur
            // l'axe de la table, comme les blocs du plateau.
            display.setTransformation(new Transformation(new Vector3f(),
                    new Quaternionf().rotationYXZ(rotation, (float) (Math.PI / 2), 0),
                    new Vector3f(echelle), new Quaternionf()));
        });
    }

    private static ItemStack itemFusilTable() {
        return net.thundranode.buckshot.Fusil.creer();
    }

    private static ItemStack itemMalletteTable() {
        ItemStack item = new ItemStack(Material.PAPER);
        item.setData(io.papermc.paper.datacomponent.DataComponentTypes.ITEM_MODEL,
                net.kyori.adventure.key.Key.key("rr", "mallette"));
        return item;
    }

    /** Petit objet pose sur la table, place dans le repere du feutre. */
    private void poserDecoTable(Location centre, double cos, double sin, double u,
                                double haut, double v, ItemStack item,
                                float lacet, float surLeDos, float echelle) {
        Location lieu = centre.clone().add(u * cos + v * sin, haut, -u * sin + v * cos);
        lieu.getWorld().spawn(lieu, ItemDisplay.class, display -> {
            marquer(display, "deco");
            display.setItemStack(item);
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            display.setBillboard(Display.Billboard.FIXED);
            display.setTransformation(new Transformation(new Vector3f(),
                    new Quaternionf().rotationYXZ(lacet, surLeDos, 0),
                    new Vector3f(echelle), new Quaternionf()));
        });
    }

    private void spawnBlocData(Location lieu, org.bukkit.block.data.BlockData bloc,
                               Vector3f translation, Vector3f echelle, float rotation) {
        lieu.getWorld().spawn(lieu, BlockDisplay.class, display -> {
            marquer(display, "deco");
            display.setBlock(bloc);
            display.setBillboard(Display.Billboard.FIXED);
            display.setTransformation(new Transformation(translation,
                    new AxisAngle4f(rotation, 0, 1, 0), echelle, new AxisAngle4f()));
        });
    }

    private void spawnBloc(Location lieu, Material materiau, Vector3f translation,
                            Vector3f echelle, float rotation) {
        lieu.getWorld().spawn(lieu, BlockDisplay.class, display -> {
            marquer(display, "bloc");
            display.setBlock(materiau.createBlockData());
            display.setBillboard(Display.Billboard.FIXED);
            display.setTransformation(new Transformation(translation,
                    new AxisAngle4f(rotation, 0, 1, 0), echelle, new AxisAngle4f()));
        });
    }

    private void marquer(Entity entite, String type) {
        entite.getPersistentDataContainer().set(cleTable, PersistentDataType.STRING, type);
        if (config != null) {
            entite.getPersistentDataContainer().set(cleTableId, PersistentDataType.STRING, config.id());
        }
    }

    private void nettoyerDisplays() {
        if (config == null) return;
        Location centre = centreConfigure();
        if (centre == null) return;
        String id = config.id();
        for (Entity entite : centre.getWorld().getEntities()) {
            if (!entite.getPersistentDataContainer().has(cleTable, PersistentDataType.STRING)) continue;
            String idEntite = entite.getPersistentDataContainer().get(cleTableId, PersistentDataType.STRING);
            // Multi-tables : ne balayer que SES entites. Les entites sans id
            // (posees avant la mise a jour) sont rattrapees si elles sont
            // proches du centre — au-dela, elles appartiennent a une autre
            // table ou a un orphelin que /rr table retirer ramassera.
            boolean aMoi = id.equals(idEntite)
                    || (idEntite == null && entite.getLocation().distanceSquared(centre) <= 12 * 12);
            if (aMoi) entite.remove();
        }
        retirerBarrieres();
        fusil = null;
        relanceFusil = null;
        relanceMallette = null;
        malletteVictoire = null;
        teteMort = null;
        fusilAPrendre = null;
        priseFusil = null;
        fusilBedrock = null;
        fusilPoseCote = null;
        viesJoueur = null;
        viesJoueur2 = null;
        sang.clear();
        sangChute.clear();
    }

    @Override public boolean configuree() { return config != null && fusil != null; }

    /**
     * Auto-reparation : les references aux displays meurent quand les chunks
     * du monde se dechargent (constate le 2026-08-27 : table visible mais
     * "not configured" apres un aller-retour au Casino, plus aucune mise
     * proposee). Si la table est configuree mais que le fusil n'est plus
     * valide, tout est reconstruit -- a n'appeler qu'entre deux parties.
     */
    @Override
    public void reparerSiNecessaire() {
        if (config == null || (fusil != null && fusil.isValid())) return;
        // Chunks decharges : reconstruire y spawnerait des entites qui
        // s'invalident aussitot, et la boucle d'approche re-tenterait toutes
        // les 10 ticks (spam constate au boot du 2026-08-27, personne dans
        // le monde). On attend qu'un joueur charge la zone. Centre nul =
        // monde absent, on attend pareil.
        Location centre = centreConfigure();
        if (centre == null || !centre.isChunkLoaded()) return;
        plugin.getLogger().info("[Buckshot] table invalide (chunks decharges ?), reconstruction.");
        reconstruire();
    }

    @Override
    public boolean aPortee(Player joueur) {
        Location centre = centreConfigure();
        return centre != null && joueur.getWorld().equals(centre.getWorld())
                && joueur.getLocation().distanceSquared(centre) <= rayonArret * rayonArret;
    }

    @Override
    public void installerJoueur(Player joueur) {
        // La session est deja posee : le teleport doit etre marque comme
        // venant du plugin, sinon l'ecouteur anti-warp l'annule.
        TeleportAutorise.pendant(joueur, () -> joueur.teleport(config.placeJoueur()));
        joueur.setRotation(config.yaw(), 0);
    }

    @Override
    public void montrerFusil(Acteur acteur, Player joueur) {
        if (fusil != null) fusil.setItemStack(ItemStack.empty());
        if (acteur == Acteur.DEALER) dealer.equiperFusil();
        else retirerFusilDealer();
    }

    /**
     * Vide la main du dealer -- et la lui rend AUSSITOT s'il est menotte.
     *
     * <p>Le suivi des menottes ne repassait que toutes les 2 ticks : entre
     * les deux, ses bras crossbow retombaient le temps d'un battement, tres
     * visible apres chaque tir du joueur. Le re-equipement immediat supprime
     * la fenetre sans reposer l'item en boucle (ce qui casserait l'usage).
     */
    private void retirerFusilDealer() {
        dealer.retirerFusil();
        if (menottesPortees.containsKey(Acteur.DEALER)) {
            dealer.entite().ifPresent(e ->
                    e.getInventory().setItemInMainHand(arbaleteInvisible()));
            dealer.masquerMainBedrock();
        }
    }

    @Override public void leverBrasDealer() { dealer.leverBras(); }

    @Override public void baisserBrasDealer() { dealer.baisserBras(); }

    @Override public void dealerRegarde(Location lieu) { dealer.regarder(lieu); }

    @Override public void montrerViesDealer(int vies, int viesClope, int plafond) {
        dealer.afficherVies(vies, viesClope, plafond);
    }

    @Override public void masquerViesDealer() { dealer.masquerVies(); }

    /**
     * Les vies du joueur assis, en ligne de coeurs au-dessus de sa tete :
     * sa barre de vie ne se voit que de son propre ecran, les spectateurs
     * n'avaient aucun moyen de suivre. Meme code couleur que la ligne de
     * DrDonutt (rouges, noirs de cigarette, conteneurs vides), et cachee au
     * joueur lui-meme -- elle ne parle qu'aux autres.
     */
    @Override
    public void montrerViesJoueur(Player joueur, int vies, int viesClope, int plafond) {
        if (config == null) return;
        int noirs = Math.max(0, Math.min(viesClope, vies));
        int rouges = Math.max(0, vies - noirs);
        var texte = Component.text()
                .append(Component.text("❤".repeat(rouges),
                        net.kyori.adventure.text.format.NamedTextColor.RED));
        if (noirs > 0) {
            texte.append(Component.text("❤".repeat(noirs),
                    net.kyori.adventure.text.format.NamedTextColor.BLACK));
        }
        if (vies < plafond) {
            texte.append(Component.text("❤".repeat(plafond - Math.max(0, vies)),
                    net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY));
        }
        if (viesJoueur == null || viesJoueur.isDead()) {
            Location perchoir = config.placeJoueur().clone().add(0, 2.25, 0);
            viesJoueur = perchoir.getWorld().spawn(perchoir,
                    org.bukkit.entity.TextDisplay.class, display -> {
                        display.setBillboard(Display.Billboard.CENTER);
                        display.setShadowed(true);
                        display.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                        marquer(display, "viesjoueur");
                    });
            joueur.hideEntity(plugin, viesJoueur);
        }
        viesJoueur.text(texte.build());
    }

    @Override
    public void masquerViesJoueur() {
        if (viesJoueur != null) viesJoueur.remove();
        viesJoueur = null;
    }

    // ---- Duel PvP : le second joueur occupe la place du dealer ----

    /** Assoit le second joueur du duel a la place du dealer, face au premier. */
    public void installerJoueur2(Player joueur) {
        Location place = config.placeDealer().clone();
        place.setYaw(config.yaw() + 180.0f);
        place.setPitch(0);
        TeleportAutorise.pendant(joueur, () -> joueur.teleport(place));
    }

    /** Les coeurs du second joueur, meme ligne que ceux du premier mais a la
     *  place du dealer. Cachee au porteur : elle ne parle qu'aux autres. */
    public void montrerViesJoueur2(Player joueur, int vies, int viesClope, int plafond) {
        if (config == null) return;
        int noirs = Math.max(0, Math.min(viesClope, vies));
        int rouges = Math.max(0, vies - noirs);
        var texte = Component.text()
                .append(Component.text("❤".repeat(rouges),
                        net.kyori.adventure.text.format.NamedTextColor.RED));
        if (noirs > 0) {
            texte.append(Component.text("❤".repeat(noirs),
                    net.kyori.adventure.text.format.NamedTextColor.BLACK));
        }
        if (vies < plafond) {
            texte.append(Component.text("❤".repeat(plafond - Math.max(0, vies)),
                    net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY));
        }
        if (viesJoueur2 == null || viesJoueur2.isDead()) {
            Location perchoir = config.placeDealer().clone().add(0, 2.25, 0);
            viesJoueur2 = perchoir.getWorld().spawn(perchoir,
                    org.bukkit.entity.TextDisplay.class, display -> {
                        display.setBillboard(Display.Billboard.CENTER);
                        display.setShadowed(true);
                        display.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                        marquer(display, "viesjoueur2");
                    });
            joueur.hideEntity(plugin, viesJoueur2);
        }
        viesJoueur2.text(texte.build());
    }

    public void masquerViesJoueur2() {
        if (viesJoueur2 != null) viesJoueur2.remove();
        viesJoueur2 = null;
    }

    /**
     * Range DrDonutt le temps d'un duel : ses coeurs d'abord (l'hologramme ne
     * s'enleve que sur un PNJ encore spawne), puis le corps. Le drapeau mort
     * du PNJ suffit, la boucle solo est suspendue pendant le duel.
     */
    public void cacherDealerPourDuel() {
        dealer.masquerVies();
        dealer.mourir();
    }

    /** Fin de duel : DrDonutt revient a son poste, pret pour le solo. */
    public void retablirDealerApresDuel() {
        dealer.ranimer();
    }

    /**
     * Le corps du joueur pour les spectateurs : passe en mode spectateur pour
     * sa cinematique de chute, le joueur disparait net de leur ecran. Ce PNJ
     * a son skin le remplace, debout, et s'effondrera a la chute. Cache au
     * mourant : sa camera tombe exactement la ou git le corps.
     */
    @Override
    public void poserCorpsChute(Player joueur) {
        masquerChuteJoueur();
        // Un demi-bloc en retrait de la table : couche, le corps s'etend
        // depuis son point d'ancrage et clipperait le feutre sinon.
        double radians = Math.toRadians(joueur.getLocation().getYaw());
        Location lieu = joueur.getLocation().add(
                Math.sin(radians) * 0.5, 0, -Math.cos(radians) * 0.5);
        corpsJoueur.apparaitre(joueur, lieu);
        joueur.getWorld().spawnParticle(Particle.DUST,
                joueur.getLocation().add(0, 1.62, 0), 60, 0.22, 0.28, 0.22,
                new Particle.DustOptions(Color.fromRGB(0x8a1010), 1.4f));
        // Filet : le corps traine apres le respawn pour que la salle le voie,
        // mais jamais plus de 30 s -- sauf si une mort plus recente a deja
        // repris la scene.
        int generation = ++generationCorps;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (generation == generationCorps) masquerChuteJoueur();
        }, 600L);
    }

    @Override
    public void coucherCorpsChute() {
        corpsJoueur.coucher(config.yaw());
    }

    /**
     * Apercu de l'animation de mort sans partie (/rr mort) : le corps
     * apparait la ou se tient l'invocateur, s'effondre et saigne aux memes
     * ticks que la cinematique reelle, puis disparait au meme filet de 30 s.
     * Contrairement a la vraie mort, le corps lui reste VISIBLE : ici c'est
     * lui le spectateur.
     */
    public void apercuMort(Player joueur) {
        if (config == null) return;
        Location sol = joueur.getLocation().clone();
        poserCorpsChute(joueur);
        corpsJoueur.entite().ifPresent(corps -> joueur.showEntity(plugin, corps));
        Bukkit.getScheduler().runTaskLater(plugin, this::coucherCorpsChute, 28L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> sangChuteJoueur(sol), 36L);
    }

    /** Sang frais au sol autour du corps, a l'instant de l'impact. */
    @Override
    public void sangChuteJoueur(Location sol) {
        sol.getWorld().playSound(sol, Sound.ENTITY_GENERIC_BIG_FALL, 0.9f, 0.65f);
        // Meme decalque que le feutre, sol comme origine, axes du monde --
        // mais dans sa propre liste : ces taches accompagnent le corps et lui
        // survivent jusqu'au masquerChuteJoueur, pas jusqu'a l'annulation.
        for (int i = 0; i < 9; i++) {
            double u = (dessin.nextDouble() * 2 - 1) * 0.85;
            double v = (dessin.nextDouble() * 2 - 1) * 0.85;
            sangChute.add(creerTacheSang(sol, 1, 0, u, v, 0.02 + i * 0.001,
                    0.45f + dessin.nextFloat() * 0.7f));
        }
    }

    @Override
    public void masquerChuteJoueur() {
        corpsJoueur.retirer();
        for (Entity tache : sangChute) tache.remove();
        sangChute.clear();
    }

    /**
     * Plus aucune barriere posee par le plugin : l'utilisateur gere son
     * enclos a la main dans le monde (demande du 2026-08-27, apres deux
     * allers-retours -- "enleve toutes tes barrieres"). La methode reste
     * pour l'interface mais ne touche plus un seul bloc.
     */
    @Override
    public void poserCage() {
    }

    @Override
    public void retirerCage() {
        for (org.bukkit.block.Block bloc : cage) {
            if (bloc.getType() == Material.BARRIER) bloc.setType(Material.AIR, false);
        }
        cage.clear();
    }

    /** Peaux par round : {round -> [valeur, signature]}, remplie par le plugin. */
    private final Map<Integer, String[]> peauxParRound = new HashMap<>();

    public void definirPeauRound(int round, String valeur, String signature) {
        peauxParRound.put(round, new String[]{valeur, signature});
    }

    @Override
    public void peauDealerPourRound(int round) {
        String[] peau = peauxParRound.get(round);
        if (peau != null) dealer.changerPeau(peau[0], peau[1]);
        else dealer.peauParDefaut();
    }

    /**
     * Montre le fusil vise du dealer via un ItemDisplay pilote serveur.
     *
     * <p>C'est la lecon de la nuit du 2026-08-22 : regler une pose d'item en
     * main de PNJ via le pack exige un cycle rebuild-upload-redemarrage par
     * essai, et le repere d'affichage de la main a trahi chaque prediction.
     * Ici la transformation est relue de la config a chaque rafraichissement,
     * donc {@code /rr pose} regle tout en direct, en coordonnees honnetes :
     * avant / droite / haut par rapport au corps du dealer, lacet / tangage /
     * roulis en degres autour du canon.
     */
    @Override
    public void viserDealer(String pose) {
        poseVise = pose;
        // Posture inconnue : force le prochain rafraichissement a la
        // reappliquer. L'ancien code vidait la main ici sans invalider la
        // posture : des la deuxieme commande /rr pose, main vide et bras
        // jamais releve, la posture croyant etre deja en place.
        brasVise = -1;
        rafraichirVise();
        if (tacheVise == null) {
            tacheVise = Bukkit.getScheduler().runTaskTimer(plugin, this::rafraichirVise, 2L, 2L);
        }
    }

    @Override
    public void finViseeDealer() {
        poseVise = null;
        dealer.pitchAnimation(0);
        if (brasVise != 0) {
            brasVise = 0;
            dealer.baisserBras();
            dealer.retirerFusil();
        }
        // Un dealer menotte garde ses bras crossbow : la fin de visee vide
        // sa main, on lui rend l'arbalete invisible des menottes.
        if (menottesPortees.containsKey(Acteur.DEALER)) {
            dealer.entite().ifPresent(e ->
                    e.getInventory().setItemInMainHand(arbaleteInvisible()));
            dealer.masquerMainBedrock();
        }
        if (tacheVise != null) { tacheVise.cancel(); tacheVise = null; }
        if (fusilVise != null) { fusilVise.remove(); fusilVise = null; }
    }

    private void rafraichirVise() {
        Player entite = dealer.entite().orElse(null);
        if (poseVise == null || entite == null) { finViseeDealer(); return; }
        String base = "pose." + poseVise + ".";
        var cfg = plugin.getConfig();

        // Pitch du corps par pose (pitch-npc, degres) : le regard maison
        // garde la tete droite par defaut, mais une pose peut imposer une
        // legere inclinaison (ex: menottes, tete baissee d'un degre vers
        // les poignets).
        dealer.pitchAnimation(cfg.getDouble(base + "pitch-npc", 0.0));

        // Posture du bras, par pose et persistante dans la config :
        // 0 = main vide, 1 = item en main (le pli leger d'un objet tenu,
        // fusil invisible a l'etat "cache"), 2 = bras tendus en joue.
        //
        // Le 2 n'utilise PAS l'etat d'usage : startUsingItem joue l'animation
        // de CHARGEMENT d'arbalete, qui remue et ne suit pas le fusil. La
        // pose en joue, figee et bras tendus, est celle que le client donne a
        // quiconque TIENT une arbalete chargee -- donc une vraie arbalete,
        // chargee d'une fleche, rendue invisible par item_model + etat cache.
        int brasVoulu = Math.max(0, Math.min(2,
                (int) Math.round(cfg.getDouble(base + "bras", 0.0))));
        if (brasVoulu != brasVise) {
            brasVise = brasVoulu;
            dealer.baisserBras();
            if (brasVoulu == 0) {
                dealer.retirerFusil();
            } else if (brasVoulu == 1) {
                dealer.entite().ifPresent(e -> {
                    var item = net.thundranode.buckshot.Fusil.creer();
                    net.thundranode.buckshot.Fusil.poser(item, "cache");
                    e.getInventory().setItemInMainHand(item);
                });
                dealer.masquerMainBedrock();
            } else {
                dealer.entite().ifPresent(e -> {
                    var arba = new org.bukkit.inventory.ItemStack(Material.CROSSBOW);
                    arba.setData(io.papermc.paper.datacomponent.DataComponentTypes.ITEM_MODEL,
                            net.kyori.adventure.key.Key.key("rr", "shotgun"));
                    // Normalement "cache" : le fusil visible est l'ItemDisplay.
                    // Un etat reel sert a MESURER le repere de la main bras
                    // leve -- on affiche alors les deux et on les superpose.
                    arba.setData(io.papermc.paper.datacomponent.DataComponentTypes.CUSTOM_MODEL_DATA,
                            io.papermc.paper.datacomponent.item.CustomModelData
                                    .customModelData()
                                    .addString(cfg.getString(base + "main", "cache"))
                                    .build());
                    arba.setData(io.papermc.paper.datacomponent.DataComponentTypes.CHARGED_PROJECTILES,
                            io.papermc.paper.datacomponent.item.ChargedProjectiles
                                    .chargedProjectiles(java.util.List.of(
                                            new org.bukkit.inventory.ItemStack(Material.ARROW))));
                    e.getInventory().setItemInMainHand(arba);
                });
                dealer.masquerMainBedrock();
            }
        }

        // Le fusil suit le cap REEL du dealer : LookClose le tourne vers le
        // joueur et l'ancien cap fige laissait l'arme plantee dans le vide
        // a cote de lui (meme correction que la cigarette, 2026-08-23).
        // L'agitation qui avait justifie le gel venait des sauts secs de
        // position/rotation ; l'interpolation ajoutee a poserFusilPose lisse
        // le suivi.
        fusilVise = poserFusilPose(fusilVise, entite, entite.getLocation().getYaw(), poseVise);
    }

    /**
     * Pose l'ItemDisplay d'une posture reglee par {@code /rr pose} sur
     * {@code porteur}, et retourne l'affichage (cree au premier appel).
     */
    private ItemDisplay poserFusilPose(ItemDisplay affichage, Player porteur,
                                       float cap, String pose) {
        return poserFusilPose(affichage, porteur, cap, pose, false);
    }

    /**
     * @param colle vrai pour un accessoire PORTE (bracelets) suivi chaque tick :
     *              teleport et lissage d'un tick au lieu de deux. NB 2026-09-02 :
     *              monter le display en passager du porteur a ete tente et ne
     *              marche PAS -- Entity.startRiding refuse cote serveur tout
     *              vehicule non sauvegardable, et EntityType.PLAYER ne l'est pas ;
     *              addPassenger renvoie false en silence et le display reste
     *              au sol. Ne pas reessayer sur un joueur (PNJ Citizens inclus).
     */
    private ItemDisplay poserFusilPose(ItemDisplay affichage, Player porteur,
                                       float cap, String pose, boolean colle) {
        String base = "pose." + pose + ".";
        var cfg = plugin.getConfig();
        double avant = cfg.getDouble(base + "avant");
        double droite = cfg.getDouble(base + "droite");
        double haut = cfg.getDouble(base + "haut");
        double lacet = Math.toRadians(cfg.getDouble(base + "lacet"));
        double tangage = Math.toRadians(cfg.getDouble(base + "tangage"));
        double roulis = Math.toRadians(cfg.getDouble(base + "roulis"));
        float echelle = (float) cfg.getDouble(base + "echelle", 0.55);

        double yaw = Math.toRadians(cap);
        // Conventions Minecraft : avant = (-sin, 0, cos), droite = (-cos, 0, -sin).
        Location ancre = porteur.getLocation().clone().add(
                -Math.sin(yaw) * avant - Math.cos(yaw) * droite,
                haut,
                Math.cos(yaw) * avant - Math.sin(yaw) * droite);
        ancre.setYaw(0); ancre.setPitch(0);
        // Le canon du modele part sur +x ; l'angle qui l'envoie sur l'avant du
        // porteur est -(yaw + 90 degres), verifie par calcul sur les deux
        // conventions ci-dessus. Ordre Y-X-Z : cap, puis roulis autour du
        // canon (+x), puis tangage (Rz leve +x vers +y).
        Quaternionf q = new Quaternionf().rotationYXZ(
                (float) (-(yaw + Math.PI / 2) - lacet), (float) roulis, (float) tangage);

        // Le modele affiche depend de la pose : l'inspection doit montrer la
        // cartouche dans le port, ce que seul shotgun_inspect dessine. Un
        // ItemDisplay en transform NONE ignore les display du modele, donc
        // poser un etat d'animation ici ne change que la geometrie.
        String modele = cfg.getString(base + "modele", "hold");
        // La pose "cigarette" reutilise tout le banc de reglage du fusil,
        // mais change d'item et suit AUSSI le pitch du regard (la bouche
        // monte et descend avec la tete, le fusil garde ses angles calibres).
        boolean clope = pose.startsWith("cigarette");
        if (clope) {
            // Relecture avec repli sur la pose dealer : une pose -soi jamais
            // initialisee previsualise quand meme quelque chose de sense.
            double av = lireClope(cfg, base, "avant", 0.23);
            double dr = lireClope(cfg, base, "droite", -0.01);
            double ht = lireClope(cfg, base, "haut", 1.50);
            double lc = Math.toRadians(lireClope(cfg, base, "lacet", 2.0));
            double tg = Math.toRadians(lireClope(cfg, base, "tangage", -8.0));
            double rl = Math.toRadians(lireClope(cfg, base, "roulis", 0.0));
            echelle = (float) lireClope(cfg, base, "echelle", 0.35);
            ancre = ancreBouche(porteur, av, dr, ht,
                    lireClope(cfg, base, "pivot", 1.67));
            q = rotationBouche(porteur, lc, rl, tg);
        }
        if (affichage == null || !affichage.isValid()) {
            affichage = ancre.getWorld().spawn(ancre, ItemDisplay.class, d -> {
                marquer(d, "fusil-vise");
                d.setItemStack(clope ? Cigarette.creer("s0") : Fusil.creer());
                d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                d.setBillboard(Display.Billboard.FIXED);
                d.setTeleportDuration(2);
            });
        } else {
            if (colle) affichage.setTeleportDuration(1);
            affichage.teleport(ancre);
        }
        ItemStack affiche;
        if (clope) {
            affiche = Cigarette.creer(modele);
        } else if (pose.equals("menottes")) {
            // Meme habillage que la hotbar et la vitrine du dealer :
            // crochet + item_model rr:menottes. L'etat "portees" bascule
            // sur menottes_portees (anneaux fermes sur les poignets de la
            // pose arbalete) ; tout autre etat retombe sur le modele item.
            affiche = InventairePartie.vitrine(net.thundranode.buckshot.jeu.Objet.MENOTTES);
            affiche.setData(io.papermc.paper.datacomponent.DataComponentTypes.CUSTOM_MODEL_DATA,
                    io.papermc.paper.datacomponent.item.CustomModelData.customModelData()
                            .addString(modele));
        } else {
            affiche = Fusil.creer();
            Fusil.poser(affiche, modele);
        }
        affichage.setItemStack(affiche);
        // Sans interpolation, un rafraichissement qui suit le cap du porteur
        // saute d'un angle a l'autre et agite l'arme -- c'est ce qui avait
        // fait geler le cap a l'epoque. Deux ticks = la cadence du suivi.
        Vector3f translation = clope ? priseLevres(porteur, q, echelle) : new Vector3f();
        affichage.setInterpolationDuration(colle ? 1 : 2);
        affichage.setInterpolationDelay(0);
        affichage.setTransformation(new Transformation(
                translation, q, new Vector3f(echelle), new Quaternionf()));
        return affichage;
    }

    /**
     * Ancre de la cigarette a la bouche, qui suit le cap ET le pitch du
     * porteur : la bouche tourne autour du pivot de la tete (le cou, a 1.5
     * bloc des pieds) quand il leve ou baisse le regard. A pitch nul le
     * calcul redonne exactement (avant, droite, haut) : les valeurs
     * calibrees restent valables telles quelles.
     */
    /**
     * Lecture d'un axe de pose cigarette avec repli : la cle de la pose
     * demandee, sinon celle de la pose dealer, sinon le defaut fige.
     */
    private static double lireClope(org.bukkit.configuration.Configuration cfg,
                                    String base, String cle, double defaut) {
        return cfg.getDouble(base + cle,
                cfg.getDouble("pose.cigarette." + cle, defaut));
    }

    /**
     * Pitch du regard, assaini : un PNJ Citizens peut porter un pitch
     * aberrant, et une valeur non finie donnerait une ancre NaN, donc une
     * entite invisible. Borne a +/-60 degres, au-dela la bouche du modele
     * ne suit de toute facon plus vraiment.
     */
    private static double pitchSur(Player porteur) {
        float p = porteur.getLocation().getPitch();
        if (!Float.isFinite(p)) return 0;
        return Math.toRadians(Math.max(-60, Math.min(60, p)));
    }

    /** Pitch du regard sans borne (bras crossbow : suivent jusqu'a +/-90). */
    private static double pitchBrut(Player porteur) {
        float p = porteur.getLocation().getPitch();
        return Float.isFinite(p) ? Math.toRadians(p) : 0;
    }

    private static Location ancreBouche(Player porteur, double avant,
                                        double droite, double haut, double pivot) {
        return ancrePivot(porteur, avant, droite, haut, pivot, pitchSur(porteur));
    }

    /**
     * Ancre (avant, droite, haut) calibree a pitch nul, tournee autour d'un
     * pivot sur l'axe du corps (cou, epaules...) par le pitch donne : ce qui
     * est devant et au-dessus du pivot descend quand le porteur baisse la tete.
     */
    private static Location ancrePivot(Player porteur, double avant, double droite,
                                       double haut, double pivot, double pitch) {
        double yaw = Math.toRadians(porteur.getLocation().getYaw());
        double u0 = haut - pivot;
        double f = avant * Math.cos(pitch) + u0 * Math.sin(pitch);
        double u = pivot - avant * Math.sin(pitch) + u0 * Math.cos(pitch);
        Location a = porteur.getLocation().clone().add(
                -Math.sin(yaw) * f - Math.cos(yaw) * droite,
                u,
                Math.cos(yaw) * f - Math.sin(yaw) * droite);
        a.setYaw(0); a.setPitch(0);
        return a;
    }

    /**
     * Translation qui visse la cigarette aux levres : le modele tourne
     * autour de son extremite cote visage, pas autour de son centre. Sans
     * ca, baisser la tete faisait basculer l'extremite arriere vers le haut
     * et l'enfoncait dans la camera -- interieur du modele visible. Le bout
     * tenu est detecte (celui qui pointe vers le visage), donc le reglage
     * de lacet du calibrage reste libre.
     */
    private static Vector3f priseLevres(Player porteur, Quaternionf q, float echelle) {
        double yaw = Math.toRadians(porteur.getLocation().getYaw());
        Vector3f e = new Vector3f(0.25f * echelle, 0, 0).rotate(q);
        Vector3f devant = new Vector3f((float) -Math.sin(yaw), 0, (float) Math.cos(yaw));
        if (e.dot(devant) > 0) e.mul(-1);
        return e.mul(-1);
    }

    /** Rotation de la cigarette : le tangage compose le pitch du regard. */
    private static Quaternionf rotationBouche(Player porteur, double lacet,
                                              double roulis, double tangage) {
        double yaw = Math.toRadians(porteur.getLocation().getYaw());
        double pitch = pitchSur(porteur);
        return new Quaternionf().rotationYXZ(
                (float) (-(yaw + Math.PI / 2) - lacet),
                (float) roulis, (float) (tangage - pitch));
    }

    /**
     * Pose statique d'essai posee sur SOI, visible par soi-meme, rafraichie
     * de la config toutes les 2 ticks : combinee a {@code /rr pose <pose>
     * <axe> <delta>}, elle bouge sous les yeux du regleur sans rien rejouer.
     * Suit le cap courant, donc se juge aussi bien en F5 qu'en premiere
     * personne.
     */
    public void essayerPoseSurSoi(Player joueur, String pose) {
        arreterEssaiPose(joueur);
        UUID id = joueur.getUniqueId();
        BukkitTask tache = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!joueur.isValid()) { arreterEssaiPose(joueur); return; }
            ItemDisplay a = essais.get(id);
            a = poserFusilPose(a != null && a.isValid() ? a : null,
                    joueur, joueur.getLocation().getYaw(), pose);
            essais.put(id, a);
        }, 0L, 2L);
        tachesEssai.put(id, tache);
    }

    public void arreterEssaiPose(Player joueur) {
        UUID id = joueur.getUniqueId();
        BukkitTask tache = tachesEssai.remove(id);
        if (tache != null) tache.cancel();
        ItemDisplay a = essais.remove(id);
        if (a != null) a.remove();
    }

    /**
     * Donne a un vrai joueur la meme posture reglee qu'au dealer.
     *
     * <p>L'affichage est masque au joueur lui-meme : il garde l'animation en
     * premiere personne du pack, qui reste la plus lisible pour celui qui
     * tient l'arme. Les autres ne voient QUE cette pose, la troisieme personne
     * des etats concernes etant a l'echelle zero dans le pack.
     *
     * <p>Appeler avec la meme pose est sans effet : c'est ce qui permet au
     * suivi de visee de l'appeler a chaque tick sans recreer l'entite.
     */
    @Override
    public void montrerPose(Player joueur, String pose) {
        UUID id = joueur.getUniqueId();
        if (!pose.equals(posesJoueur.get(id))) {
            cacherPose(joueur);
            posesJoueur.put(id, pose);
            // Le cap est fige a l'entree dans la pose : la tete reste libre
            // pendant la partie, mais le corps ne tourne pas, donc laisser le
            // fusil suivre le regard le ferait pivoter dans le vide.
            capsJoueur.put(id, joueur.getLocation().getYaw());
        }
        ItemDisplay a = affichages.get(id);
        boolean neuf = a == null || !a.isValid();
        a = poserFusilPose(neuf ? null : a, joueur, capsJoueur.get(id), pose);
        if (neuf) joueur.hideEntity(plugin, a);
        affichages.put(id, a);
    }

    @Override
    public void cacherPose(Player joueur) {
        UUID id = joueur.getUniqueId();
        posesJoueur.remove(id);
        capsJoueur.remove(id);
        ItemDisplay affichage = affichages.remove(id);
        if (affichage != null) affichage.remove();
    }

    /**
     * La cigarette se cale a la bouche par {@code /rr pose cigarette}, comme
     * les poses du fusil : le repere ne se deduit pas, il se mesure. Les
     * valeurs par defaut ci-dessous doublent celles de la commande pour que
     * la scene marche meme si la pose n'a jamais ete reglee.
     *
     * <p>Avec lacet 180, le +x du modele (le filtre) pointe vers les levres
     * et la braise part devant le visage.
     */
    @Override
    public void fumerCigarette(Player porteur, boolean masquerAuPorteur, Runnable fin) {
        if (porteur == null || !porteur.isValid()) { fin.run(); return; }
        var cfg = plugin.getConfig();
        // Deux poses independantes : "cigarette" habille le dealer,
        // "cigarette-soi" le fumeur humain -- regler l'une ne touche plus
        // l'autre (regler les deux avec les memes cles rendait le calibrage
        // impossible : chaque retouche premiere personne dereglait le
        // dealer). Tant que la pose -soi n'a pas ete touchee, elle herite
        // du sweet spot du dealer, fige en dur ci-dessous (2026-08-23).
        boolean surDealer = dealer.entite().map(porteur::equals).orElse(false);
        String base = surDealer ? "pose.cigarette." : "pose.cigarette-soi.";
        double avant = lireClope(cfg, base, "avant", 0.23);
        double droite = lireClope(cfg, base, "droite", -0.01);
        double haut = lireClope(cfg, base, "haut", 1.50);
        double lacet = Math.toRadians(lireClope(cfg, base, "lacet", 2.0));
        double tangage = Math.toRadians(lireClope(cfg, base, "tangage", -8.0));
        double roulis = Math.toRadians(lireClope(cfg, base, "roulis", 0.0));
        float echelle = (float) lireClope(cfg, base, "echelle", 0.35);
        double pivot = lireClope(cfg, base, "pivot", 1.67);
        Location ancre = ancreBouche(porteur, avant, droite, haut, pivot);
        Quaternionf q = rotationBouche(porteur, lacet, roulis, tangage);
        // Trace de mesure : le repere du porteur ne se deduit pas, il se lit.
        // C'est ce qui permet de diagnostiquer un pitch aberrant du PNJ sans
        // rejouer dix parties.
        plugin.getLogger().info(String.format(java.util.Locale.ROOT,
                "fumette %s yaw=%.1f pitch=%.1f ancre=(%.2f %.2f %.2f)",
                porteur.getName(), porteur.getLocation().getYaw(),
                porteur.getLocation().getPitch(),
                ancre.getX(), ancre.getY(), ancre.getZ()));

        World monde = ancre.getWorld();
        ItemDisplay clope = monde.spawn(ancre, ItemDisplay.class, d -> {
            marquer(d, "cigarette");
            d.setItemStack(Cigarette.creer("s0"));
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setBillboard(Display.Billboard.FIXED);
            d.setTeleportDuration(2);
            d.setTransformation(new Transformation(
                    priseLevres(porteur, q, echelle), q,
                    new Vector3f(echelle), new Quaternionf()));
        });
        // Par defaut le fumeur voit sa propre cigarette (demande utilisateur :
        // elle doit se VOIR a la bouche, F5 compris). Si elle gene trop la
        // premiere personne en partie, pose.cigarette.cache=true retablit le
        // masquage pour le porteur humain.
        boolean cacher = masquerAuPorteur && cfg.getBoolean(base + "cache", false);
        if (cacher) porteur.hideEntity(plugin, clope);

        // Clip fourni par l'utilisateur (2,0 s = 40 ticks) : la sequence
        // entiere est calee dessus, allumage compris.
        monde.playSound(ancre, "rr:cigarette.fume", org.bukkit.SoundCategory.PLAYERS, 0.9f, 1.0f);
        // La main monte a la bouche et y reste le temps de la taffe (geste
        // vanilla, visible par tous les clients), puis redescend avant que
        // le megot tombe.
        geste.fumer(porteur, plugin.getConfig().getInt("animations.taffe-ticks", 24));

        var planning = Bukkit.getScheduler();
        int parEtape = 8;
        for (int i = 1; i < Cigarette.ETAPES.length; i++) {
            String etape = Cigarette.ETAPES[i];
            planning.runTaskLater(plugin, () -> {
                if (clope.isValid()) clope.setItemStack(Cigarette.creer(etape));
            }, (long) parEtape * i);
        }
        // Tant qu'elle est a la bouche, la cigarette SUIT le cap du porteur :
        // LookClose fait pivoter le dealer vers le joueur, et une ancre figee
        // la laissait plantee dans le vide a cote de son visage. La fumee
        // repart du meme calcul pour rester au bout.
        BukkitTask suivi = planning.runTaskTimer(plugin, new Runnable() {
            private int tic;
            @Override public void run() {
                if (!clope.isValid() || !porteur.isValid()) return;
                Location a = ancreBouche(porteur, avant, droite, haut, pivot);
                Quaternionf rot = rotationBouche(porteur, lacet, roulis, tangage);
                Vector3f prise = priseLevres(porteur, rot, echelle);
                // Suivi chaque tick, lisse sur un tick (2/2 auparavant : la
                // cigarette flottait un temps derriere la tete).
                clope.setTeleportDuration(1);
                clope.teleport(a);
                clope.setInterpolationDuration(1);
                clope.setInterpolationDelay(0);
                clope.setTransformation(new Transformation(
                        prise, rot, new Vector3f(echelle), new Quaternionf()));
                if ((tic++ & 1) == 0) {
                    // Le bout oppose aux levres est a 2x la translation de
                    // prise : c'est de la que part la fumee.
                    monde.spawnParticle(Particle.SMOKE, a.clone().add(
                            2 * prise.x, 2 * prise.y + 0.03, 2 * prise.z),
                            1, 0.02, 0.02, 0.02, 0.004);
                }
            }
        }, 1L, 1L);

        long chuteTick = (long) parEtape * Cigarette.ETAPES.length;
        planning.runTaskLater(plugin, () -> {
            suivi.cancel();
            if (!clope.isValid()) return;
            if (cacher && porteur.isValid()) porteur.showEntity(plugin, clope);
            // Le point de chute part du cap FINAL, pas de celui de l'allumage.
            Location depart = porteur.isValid() ? porteur.getLocation() : clope.getLocation();
            double cap = Math.toRadians(porteur.isValid()
                    ? porteur.getLocation().getYaw() : 0.0);
            Location sol = depart.clone().add(
                    -Math.sin(cap) * (Math.abs(avant) + 0.25),
                    porteur.isValid() ? 0.03 : -haut,
                    Math.cos(cap) * (Math.abs(avant) + 0.25));
            sol.setYaw(0); sol.setPitch(0);
            clope.setTeleportDuration(6);
            clope.teleport(sol);
        }, chuteTick);
        planning.runTaskLater(plugin, () -> {
            if (clope.isValid()) {
                monde.spawnParticle(Particle.SMOKE,
                        clope.getLocation().clone().add(0, 0.05, 0),
                        4, 0.04, 0.02, 0.04, 0.006);
            }
            fin.run();
        }, chuteTick + 7);
        // Le megot traine un moment sur le feutre avant de disparaitre.
        planning.runTaskLater(plugin, () -> {
            if (clope.isValid()) clope.remove();
        }, chuteTick + 67);
    }

    @Override
    public void montrerObjetDealer(ItemStack item) {
        if (fusil != null) fusil.setItemStack(ItemStack.empty());
        dealer.equiperObjet(item);
    }

    @Override
    public void reposerFusil(Player joueur) {
        retirerFusilDealer();
        // Plus de fusil de decor entre les parties (demande user 2026-08-23) :
        // la table reste nue, l'arme n'existe que dans les mains des acteurs.
        if (fusil != null && fusil.isValid()) fusil.setItemStack(ItemStack.empty());
    }

    @Override
    public void ejecterCartouche(Acteur acteur, boolean reelle, Player humain) {
        if (config == null) return;
        // Duel PvP : le role DEALER est tenu par un vrai joueur et le PNJ est
        // range — la douille part alors de la main du tireur humain passe en
        // parametre. En solo le PNJ est toujours la, rien ne change.
        Player source = acteur == Acteur.DEALER ? dealer.entite().orElse(humain) : humain;
        if (source == null) return;
        Location centre = config.centre();
        // Tireur dans un autre monde que la table (warp en plein tour) :
        // Location.add entre deux mondes leve une exception qui annulait la
        // partie. Pas de douille, mais la partie continue.
        if (!source.getWorld().equals(centre.getWorld())) return;
        float rotation = (float) Math.toRadians(-config.yaw());
        double cos = Math.cos(rotation), sin = Math.sin(rotation);
        // Depart : la main du tireur. Arrivee : un point du feutre de son
        // cote de la table, un peu au hasard pour que les douilles ne
        // s'empilent pas au meme endroit.
        // Depart : la fenetre d'ejection, donc a DROITE de l'arme, pas au
        // milieu du corps. Un tir en l'air part de plus haut qu'une biere,
        // mais l'ecart ne se voit pas : une seule hauteur suffit.
        org.bukkit.util.Vector regard = source.getEyeLocation().getDirection();
        org.bukkit.util.Vector droite = new org.bukkit.util.Vector(
                -regard.getZ(), 0, regard.getX()).normalize();
        Location depart = source.getEyeLocation().add(0, -0.30, 0)
                .add(regard.clone().multiply(0.35)).add(droite.multiply(0.22));
        double u = (dessin.nextDouble() * 2 - 1) * 1.5;
        double v = (acteur == Acteur.DEALER ? 0.5 : -0.5) + (dessin.nextDouble() - 0.5) * 0.45;
        // Le feutre est a +0,90 ; la douille couchee fait 0,075 bloc de haut
        // a l'echelle appliquee, son centre se pose donc juste au-dessus.
        Location arrivee = centre.clone().add(u * cos + v * sin, 0.938, -u * sin + v * cos);
        Location sommet = depart.clone().add(arrivee).multiply(0.5).add(0, 0.22, 0);

        var douille = new ItemStack(Material.PAPER);
        douille.setData(io.papermc.paper.datacomponent.DataComponentTypes.ITEM_MODEL,
                net.kyori.adventure.key.Key.key("rr", "shell"));
        douille.setData(io.papermc.paper.datacomponent.DataComponentTypes.CUSTOM_MODEL_DATA,
                io.papermc.paper.datacomponent.item.CustomModelData.customModelData()
                        .addString(reelle ? "rouge" : "blanche").build());
        float lacetFinal = dessin.nextFloat() * (float) (Math.PI * 2);
        var vrille = new org.joml.Vector3f(dessin.nextFloat() - 0.5f,
                dessin.nextFloat() - 0.5f, dessin.nextFloat() - 0.5f).normalize();
        final float echelle = 0.5f;
        ItemDisplay affichage = depart.getWorld().spawn(depart, ItemDisplay.class, display -> {
            marquer(display, "douille");
            display.setItemStack(douille);
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            display.setBillboard(Display.Billboard.FIXED);
            display.setTeleportDuration(1);
            display.setPersistent(false);
            // Echelle posee des l'apparition : sans elle, la douille
            // apparait a taille 1 le temps d'un tick.
            display.setTransformation(new Transformation(new Vector3f(),
                    new Quaternionf().rotationAxis(0f, vrille),
                    new Vector3f(echelle), new Quaternionf()));
        });
        source.getWorld().playSound(depart, Sound.ITEM_SPYGLASS_STOP_USING, 0.5f, 1.8f);
        // Le modele fait 0,35 bloc de long a l'echelle 1 : cinq fois trop
        // pour une cartouche de 12. A 0,5 elle fait 17 cm, lisible sans
        // ecraser le feutre.
        final float taille = echelle;
        // Vol raccourci a 5 ticks (demande user 2026-08-24) : a 8 ticks la
        // douille flottait, on la voyait planer au lieu d'etre chassee.
        final int vol = 5;
        final int repos = vol + 80;
        BukkitTask[] tache = new BukkitTask[1];
        tache[0] = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            int tick;

            @Override
            public void run() {
                if (!affichage.isValid() || tick > repos) {
                    affichage.remove();
                    tache[0].cancel();
                    return;
                }
                if (tick <= vol) {
                    // Bezier quadratique depart -> sommet -> feutre, avec
                    // culbute continue pendant le vol.
                    double t = (double) tick / vol;
                    double x = (1 - t) * (1 - t) * depart.getX() + 2 * (1 - t) * t * sommet.getX() + t * t * arrivee.getX();
                    double y = (1 - t) * (1 - t) * depart.getY() + 2 * (1 - t) * t * sommet.getY() + t * t * arrivee.getY();
                    double z = (1 - t) * (1 - t) * depart.getZ() + 2 * (1 - t) * t * sommet.getZ() + t * t * arrivee.getZ();
                    affichage.teleport(new Location(depart.getWorld(), x, y, z));
                    affichage.setTransformation(new Transformation(new Vector3f(),
                            new Quaternionf().rotationAxis((float) (t * Math.PI * 4), vrille),
                            new Vector3f(taille), new Quaternionf()));
                } else if (tick == vol + 1) {
                    // Posee a plat sur le feutre, lacet au hasard.
                    affichage.setTransformation(new Transformation(new Vector3f(),
                            new Quaternionf().rotationY(lacetFinal),
                            new Vector3f(taille), new Quaternionf()));
                    affichage.getWorld().playSound(arrivee, Sound.BLOCK_LEVER_CLICK, 0.4f, 1.9f);
                }
                tick++;
            }
        }, 1L, 1L);
    }

    @Override
    public java.util.List<Player> spectateurs(double rayon) {
        if (config == null) return java.util.List.of();
        Location centre = config.centre();
        java.util.List<Player> proches = new java.util.ArrayList<>();
        for (Player joueur : Bukkit.getOnlinePlayers()) {
            if (joueur.getWorld().equals(centre.getWorld())
                    && joueur.getLocation().distanceSquared(centre) <= rayon * rayon) {
                proches.add(joueur);
            }
        }
        return proches;
    }

    @Override public void annoncer(Player joueur, Component message) { joueur.sendMessage(message); }

    @Override
    public void demarrerMusique(Player joueur) {
        bloqueeJusquaSortie.remove(joueur.getUniqueId());
        jouerMusique(joueur);
    }

    @Override
    public void arreterMusique(Player joueur) {
        couperPistes(joueur);
        musique.remove(joueur.getUniqueId());
        bloqueeJusquaSortie.add(joueur.getUniqueId());
    }

    /**
     * Les deux palettes, posees dans l'axe du REGARD du joueur au moment ou
     * elles apparaissent : ancrees a la table, elles seraient hors champ des
     * qu'il tourne la tete. Elles tremblent un instant l'une contre l'autre,
     * puis chacune part de son cote en accelerant, pivote vers l'exterieur
     * et quitte l'ecran (demande user 2026-08-27).
     *
     * <p>Anime au tick par teleport interpole (teleportDuration 1), comme la
     * douille : la premiere version posait UNE teleportation longue apres
     * coup, et le changement de duree n'atteignait pas le client avant le
     * saut -- les palettes disparaissaient sans bouger.
     *
     * <p>Le lacet des displays vaut celui du joueur plus 180 : le modele est
     * dessine electrode vers +Z, elle regarde donc sa camera. Le tangage est
     * mire pour la meme raison -- s'il baisse les yeux sur la table, les
     * palettes doivent lever les leurs.
     */
    @Override
    public void defibrillation(Player joueur) {
        org.bukkit.util.Vector regard = joueur.getEyeLocation().getDirection();
        org.bukkit.util.Vector droite = new org.bukkit.util.Vector(
                -regard.getZ(), 0, regard.getX()).normalize();
        var palette = new ItemStack(Material.PAPER);
        palette.setData(io.papermc.paper.datacomponent.DataComponentTypes.ITEM_MODEL,
                net.kyori.adventure.key.Key.key("rr", "defib"));
        for (int cote : new int[]{-1, 1}) {
            Location ancre = joueur.getEyeLocation()
                    .add(regard.clone().multiply(0.62))
                    .add(droite.clone().multiply(0.17 * cote))
                    .add(0, -0.10, 0);
            ancre.setYaw(joueur.getYaw() + 180f);
            ancre.setPitch(-joueur.getPitch());
            // Inclinees en miroir, comme tenues par deux mains : sans ce
            // roulis elles se lisent comme un seul objet coupe en deux.
            float roulisBase = (float) Math.toRadians(16.0 * cote);
            ItemDisplay affichage = ancre.getWorld().spawn(ancre, ItemDisplay.class, d -> {
                marquer(d, "defib");
                d.setItemStack(palette);
                d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                d.setBillboard(Display.Billboard.FIXED);
                d.setTeleportDuration(1);
                d.setInterpolationDuration(1);
                d.setPersistent(false);
                d.setTransformation(new Transformation(new Vector3f(),
                        new Quaternionf().rotationYXZ(0, 0, roulisBase),
                        new Vector3f(0.34f), new Quaternionf()));
            });
            org.bukkit.util.Vector lateral = droite.clone().multiply(cote);
            BukkitTask[] tache = new BukkitTask[1];
            tache[0] = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
                int tick;

                @Override
                public void run() {
                    if (!affichage.isValid() || tick > TREMBLE_TICKS + ECART_TICKS) {
                        affichage.remove();
                        tache[0].cancel();
                        return;
                    }
                    if (tick <= TREMBLE_TICKS) {
                        // La secousse de la decharge : un fremissement
                        // lateral amorti, en opposition entre les deux mains.
                        double amorti = 1.0 - (double) tick / TREMBLE_TICKS;
                        double onde = Math.sin(tick * Math.PI * 0.9) * 0.035 * amorti;
                        affichage.teleport(ancre.clone().add(
                                lateral.clone().multiply(onde)));
                    } else {
                        // Depart : acceleration franche (ease-in cubique),
                        // legere montee, et la palette pivote vers
                        // l'exterieur comme si le bras s'ouvrait.
                        double t = (double) (tick - TREMBLE_TICKS) / ECART_TICKS;
                        double avance = t * t * t;
                        Location pas = ancre.clone()
                                .add(lateral.clone().multiply(1.7 * avance))
                                .add(0, 0.25 * avance, 0);
                        affichage.teleport(pas);
                        affichage.setInterpolationDelay(0);
                        affichage.setTransformation(new Transformation(new Vector3f(),
                                new Quaternionf().rotationYXZ(
                                        (float) Math.toRadians(-55.0 * cote * avance), 0,
                                        roulisBase + (float) Math.toRadians(30.0 * cote * avance)),
                                new Vector3f(0.34f), new Quaternionf()));
                    }
                    tick++;
                }
            }, 1L, 1L);
        }
    }

    /**
     * Tableau de mort : le corps despawn, sa TETE roule sur le feutre cote
     * dealer, et le tapis est repeint de sang frais -- amas dense autour de
     * la tete, projections partout. Les taches rejoignent la liste
     * {@code sang} : sangPourRound(1) les emporte au nettoyage.
     *
     * <p>La tete est un PLAYER_HEAD dont le profil est COPIE sur l'entite
     * du PNJ avant le despawn : Citizens y a deja applique la texture, la
     * copie est synchrone et suit meme les peaux par round. La v1 passait
     * par une completion Mojang async qui echouait en silence -- tete Steve.
     */
    @Override
    public void mortDealer() {
        Player corps = dealer.entite().orElse(null);
        if (corps == null || config == null || dealer.estMort()) return;
        Location centre = config.centre();
        float rotation = (float) Math.toRadians(-config.yaw());
        double cos = Math.cos(rotation), sin = Math.sin(rotation);
        World monde = corps.getWorld();
        monde.spawnParticle(Particle.DUST, corps.getEyeLocation(), 70, 0.25, 0.30, 0.25,
                new Particle.DustOptions(Color.fromRGB(0x8a1010), 1.5f));
        monde.playSound(centre, Sound.ENTITY_GENERIC_BIG_FALL, 0.9f, 0.65f);
        // AVANT le despawn : le profil (texture comprise) vit sur l'entite.
        profilTete = corps.getPlayerProfile();
        dealer.mourir();
        // La tete : le modele de crane occupe la moitie basse de son espace
        // de bloc, le display se pose donc a felt + 0,5 x echelle pour que
        // le crane REPOSE sur le feutre. Lacet legerement de biais, comme
        // une tete qui a roule avant de s'arreter.
        // 0.8 et non 1.2 : grandeur nature elle mangeait la table, et le
        // biais repasse cote joueur -- a +155 le visage tournait le dos.
        float echelleTete = 0.8f;
        Location lieuTete = centre.clone().add(
                0.15 * cos + 0.40 * sin, 0.90 + 0.5 * echelleTete - 0.02,
                -0.15 * sin + 0.40 * cos);
        float biais = rotation + (float) Math.toRadians(-25.0);
        teteMort = monde.spawn(lieuTete, ItemDisplay.class, display -> {
            marquer(display, "victoire");
            display.setItemStack(teteDrDonutt());
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            display.setBillboard(Display.Billboard.FIXED);
            display.setTransformation(new Transformation(new Vector3f(),
                    new AxisAngle4f(biais, 0, 1, 0), new Vector3f(echelleTete),
                    new AxisAngle4f()));
        });

        // Sang frais : douze taches serrees autour de la tete, huit
        // projections sur le reste du feutre. Hauteurs au-dessus des taches
        // de round (0,902-0,928) pour ne pas z-fighter avec elles.
        for (int i = 0; i < 20; i++) {
            boolean autourTete = i < 12;
            double u = autourTete ? (dessin.nextDouble() * 2 - 1) * 0.9
                    : (dessin.nextDouble() * 2 - 1) * 2.2;
            double v = autourTete ? 0.05 + dessin.nextDouble() * 0.95
                    : (dessin.nextDouble() * 2 - 1) * 1.0;
            poserTacheSang(centre, cos, sin, u, v, 0.932 + i * 0.001,
                    autourTete ? 0.6f + dessin.nextFloat() * 0.8f
                            : 0.35f + dessin.nextFloat() * 0.5f);
        }
    }

    private ItemStack teteDrDonutt() {
        ItemStack tete = new ItemStack(Material.PLAYER_HEAD);
        if (profilTete != null) {
            tete.editMeta(org.bukkit.inventory.meta.SkullMeta.class,
                    meta -> meta.setPlayerProfile(profilTete));
        }
        return tete;
    }

    /** Une tache de sang frais posee a plat sur le feutre, ajoutee a {@code sang}. */
    private void poserTacheSang(Location centre, double cos, double sin,
                                double u, double v, double hauteur, float taille) {
        sang.add(creerTacheSang(centre, cos, sin, u, v, hauteur, taille));
    }

    private ItemDisplay creerTacheSang(Location centre, double cos, double sin,
                                       double u, double v, double hauteur, float taille) {
        Location lieu = centre.clone().add(u * cos + v * sin, hauteur, -u * sin + v * cos);
        var pivot = new AxisAngle4f(dessin.nextFloat() * (float) (Math.PI * 2), 0, 1, 0);
        var decalque = new ItemStack(Material.PAPER);
        decalque.setData(io.papermc.paper.datacomponent.DataComponentTypes.ITEM_MODEL,
                net.kyori.adventure.key.Key.key("rr", "blood"));
        decalque.setData(io.papermc.paper.datacomponent.DataComponentTypes.CUSTOM_MODEL_DATA,
                io.papermc.paper.datacomponent.item.CustomModelData.customModelData()
                        .addString("blood_" + dessin.nextInt(VARIANTES_SANG / 2)).build());
        return lieu.getWorld().spawn(lieu, ItemDisplay.class, display -> {
            marquer(display, "sang");
            display.setItemStack(decalque);
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            display.setBillboard(Display.Billboard.FIXED);
            display.setTransformation(new Transformation(new Vector3f(),
                    pivot, new Vector3f(taille), new AxisAngle4f()));
        });
    }

    @Override
    public boolean dealerMort() {
        return dealer.estMort();
    }

    /**
     * La mallette des gains, COUCHEE a plat sur le feutre cote joueur
     * (debout elle s'enfoncait dans la table, demande user 2026-08-27).
     * Quart de tour sur X comme le paquet de cigarettes de la deco ; a plat
     * la valise fait ~0,2 bloc d'epaisseur a cette echelle, le display se
     * pose un demi-epaisseur au-dessus du feutre.
     */
    @Override
    public void poserMalletteVictoire() {
        if (config == null || malletteVictoire != null) return;
        Location centre = config.centre();
        float rotation = (float) Math.toRadians(-config.yaw());
        Location lieu = centre.clone().add(-0.55 * Math.sin(rotation), 1.00,
                -0.55 * Math.cos(rotation));
        malletteVictoire = lieu.getWorld().spawn(lieu, ItemDisplay.class, display -> {
            marquer(display, "victoire");
            display.setItemStack(itemMalletteTable());
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            display.setBillboard(Display.Billboard.FIXED);
            display.setTransformation(new Transformation(new Vector3f(),
                    new Quaternionf().rotationYXZ(rotation, (float) (Math.PI / 2), 0),
                    new Vector3f(0.8f), new Quaternionf()));
        });
        lieu.getWorld().playSound(lieu, Sound.BLOCK_WOOD_PLACE, 0.8f, 0.9f);
    }

    @Override
    public void ranimerDealer() {
        dealer.ranimer();
        if (malletteVictoire != null) {
            malletteVictoire.remove();
            malletteVictoire = null;
        }
        if (teteMort != null) {
            teteMort.remove();
            teteMort = null;
        }
    }

    @Override public Optional<Player> dealerEntite() { return dealer.entite(); }

    public DrDonuttNpc dealer() { return dealer; }

    private void verifierMusique() {
        secondes++;
        if (config == null) return;
        Location centreMusique = centreConfigure();
        if (centreMusique == null) return;
        for (Player joueur : Bukkit.getOnlinePlayers()) {
            if (!joueur.getWorld().equals(centreMusique.getWorld())) {
                stopperSiNecessaire(joueur);
                continue;
            }
            double distance = joueur.getLocation().distance(centreMusique);
            // Miroir Bedrock : la table en blocs est renvoyee toutes les 5 s
            // aux clients Bedrock a portee. Repete car un resend de chunk
            // (mouvement, tp) ecrase les blocs client-side sans prevenir.
            if (distance <= rayonArret && EcouteurPartie.estBedrock(joueur)) {
                if (secondes % 5 == 0) envoyerTableBedrock(joueur);
                // Et chaque seconde, la main du dealer re-videe : couvre les
                // arrivees en cours de partie et tout equipement rate.
                dealer.masquerMainPour(joueur);
            }
            if (distance > rayonArret) {
                stopperSiNecessaire(joueur);
                bloqueeJusquaSortie.remove(joueur.getUniqueId());
            } else if (distance <= rayonDepart && !bloqueeJusquaSortie.contains(joueur.getUniqueId())) {
                jouerMusique(joueur);
                // Le jukebox vanilla ne se coordonne avec rien : il peut lancer
                // un morceau par-dessus le notre a tout moment. Il n'existe pas
                // d'evenement pour l'en empecher, seulement cet arret, repete
                // chaque seconde. Notre theme est dans la categorie RECORDS et
                // n'est donc pas touche. JAMAIS pour un joueur Bedrock : Geyser
                // traduit un stop de categorie en stop TOTAL, qui coupait
                // musiques et effets chaque seconde (constat user 2026-08-29).
                if (!EcouteurPartie.estBedrock(joueur)) {
                    joueur.stopSound(SoundCategory.MUSIC);
                }
            }
        }
    }

    private void jouerMusique(Player joueur) {
        Long debut = musique.get(joueur.getUniqueId());
        long duree = piste.equals(PISTE_DERNIER_COEUR) ? DUREE_DERNIER_COEUR_S
                : piste.equals(PISTE_DERNIER_COEUR_FINAL) ? DUREE_DERNIER_COEUR_FINAL_S
                : piste.equals(PISTE_FINALE) ? DUREE_FINALE_S : DUREE_MUSIQUE_S;
        if (debut != null && secondes - debut < duree) return;
        musique.put(joueur.getUniqueId(), secondes);
        // Le theme suit l'oreille du joueur (Emitter.self) au lieu d'etre
        // ancre a la table : joue positionnel, il devenait inaudible dans
        // les pieces eloignees du batiment (toilettes du spawn). Le volume
        // est constant partout dans la zone ; la frontiere, c'est le rayon.
        joueur.playSound(net.kyori.adventure.sound.Sound.sound(
                        net.kyori.adventure.key.Key.key(piste),
                        net.kyori.adventure.sound.Sound.Source.RECORD,
                        (float) plugin.getConfig().getDouble("sons.volume-musique", 0.7), 1f),
                net.kyori.adventure.sound.Sound.Emitter.self());
    }

    private void stopperSiNecessaire(Player joueur) {
        if (musique.remove(joueur.getUniqueId()) != null) couperPistes(joueur);
    }

    /**
     * Coupe les deux themes sans se demander lequel tourne : au changement de
     * round, la piste courante a deja change quand on veut arreter l'ancienne.
     */
    private void couperPistes(Player joueur) {
        // TOUTES les pistes, lastlife comprise : l'oublier ici l'a laissee
        // jouer sous le theme final au passage round 2 -> 3 (bug 2026-08-27,
        // deux musiques en meme temps).
        joueur.stopSound(PISTE_NORMALE, SoundCategory.RECORDS);
        joueur.stopSound(PISTE_FINALE, SoundCategory.RECORDS);
        joueur.stopSound(PISTE_FINALE_TOUCHEE, SoundCategory.RECORDS);
        joueur.stopSound(PISTE_DERNIER_COEUR, SoundCategory.RECORDS);
        joueur.stopSound(PISTE_DERNIER_COEUR_FINAL, SoundCategory.RECORDS);
    }

    /**
     * Le round final a son propre theme. Le changement coupe ce qui joue et
     * relance aussitot, sinon les deux morceaux se superposeraient jusqu'a la
     * fin du premier.
     */
    @Override
    public void musiquePourRound(int round) {
        basculerPiste(round >= 3 ? PISTE_FINALE : PISTE_NORMALE);
    }

    @Override
    public void musiqueSituation(ScenePartie.Musique situation) {
        basculerPiste(switch (situation) {
            case CALME -> PISTE_NORMALE;
            case FINALE -> PISTE_FINALE;
            case FINALE_TOUCHEE -> PISTE_FINALE_TOUCHEE;
            case DERNIER_COEUR -> PISTE_DERNIER_COEUR;
            case DERNIER_COEUR_FINAL -> PISTE_DERNIER_COEUR_FINAL;
        });
    }

    private void basculerPiste(String voulue) {
        if (voulue.equals(piste)) return;
        piste = voulue;
        for (Player joueur : Bukkit.getOnlinePlayers()) {
            if (musique.remove(joueur.getUniqueId()) == null) continue;
            couperPistes(joueur);
            jouerMusique(joueur);
        }
    }

    /**
     * Pose ou retire les menottes portees selon l'etat des deux participants.
     * Idempotent : appele apres chaque action traitee, il ne refait rien tant
     * que l'etat ne change pas. A la pose : claquement de chaine et bracelets
     * qui apparaissent 40 % trop grands, resorbes par l'interpolation du
     * premier suivi — l'effet "menottes qui se referment". Au retrait :
     * chaine cassee. Entre les deux, suivi du cap du porteur toutes les 2
     * ticks, comme le fusil vise.
     */
    @Override
    public void synchroniserMenottes(boolean dealerMenotte, boolean joueurMenotte, Player humain,
                                     Player humain2) {
        if (humain != null) porteurMenottesJoueur = humain;
        porteurMenottesJoueur2 = humain2;
        appliquerMenottes(Acteur.DEALER, dealerMenotte);
        appliquerMenottes(Acteur.JOUEUR, joueurMenotte);
        if (!menottesPortees.isEmpty() && tacheMenottes == null) {
            // Chaque tick : la position est rendue par le client (passager),
            // seule la rotation est a tenir a jour, et un tick de retard sur
            // l'angle ne se voit pas.
            tacheMenottes = Bukkit.getScheduler().runTaskTimer(plugin, this::suivreMenottes, 1L, 1L);
        } else if (menottesPortees.isEmpty() && tacheMenottes != null) {
            tacheMenottes.cancel();
            tacheMenottes = null;
        }
    }

    private Player porteurMenottes(Acteur acteur) {
        if (acteur != Acteur.DEALER) return porteurMenottesJoueur;
        return porteurMenottesJoueur2 != null ? porteurMenottesJoueur2 : dealer.entite().orElse(null);
    }

    /** Vrai si ce camp est tenu par le PNJ DrDonutt (et non un humain). */
    private boolean estLePnj(Acteur acteur) {
        return acteur == Acteur.DEALER && porteurMenottesJoueur2 == null;
    }

    /**
     * Arbalete chargee invisible : la pose bras 2 (crossbow) sans rien de
     * visible dans la main. Meme habillage que la visee du dealer, etat
     * "cache" fige.
     */
    static ItemStack arbaleteInvisible() {
        var arba = new ItemStack(Material.CROSSBOW);
        arba.setData(io.papermc.paper.datacomponent.DataComponentTypes.ITEM_MODEL,
                net.kyori.adventure.key.Key.key("rr", "shotgun"));
        arba.setData(io.papermc.paper.datacomponent.DataComponentTypes.CUSTOM_MODEL_DATA,
                io.papermc.paper.datacomponent.item.CustomModelData
                        .customModelData().addString("cache").build());
        arba.setData(io.papermc.paper.datacomponent.DataComponentTypes.CHARGED_PROJECTILES,
                io.papermc.paper.datacomponent.item.ChargedProjectiles
                        .chargedProjectiles(java.util.List.of(new ItemStack(Material.ARROW))));
        return arba;
    }

    private void appliquerMenottes(Acteur acteur, boolean menotte) {
        // Bracelets 3D : PNJ seulement. Sur un humain (solo ou duel) l'essai
        // du 2026-09-02 est abandonne, il tient les menottes en main (item
        // CUFFED de la hotbar).
        if (!estLePnj(acteur)) return;
        ItemDisplay affichage = menottesPortees.get(acteur);
        Player porteur = porteurMenottes(acteur);
        if (menotte && (affichage == null || !affichage.isValid()) && porteur != null) {
            affichage = poserFusilPose(null, porteur, porteur.getLocation().getYaw(), "menottes", true);
            marquer(affichage, "menottes");
            Transformation t = affichage.getTransformation();
            affichage.setInterpolationDuration(0);
            affichage.setTransformation(new Transformation(t.getTranslation(), t.getLeftRotation(),
                    new Vector3f(t.getScale()).mul(1.4f), t.getRightRotation()));
            porteur.getWorld().playSound(porteur.getLocation(), Sound.BLOCK_CHAIN_PLACE, 1.0f, 0.7f);
            porteur.getWorld().playSound(porteur.getLocation(), Sound.BLOCK_IRON_TRAPDOOR_CLOSE, 0.5f, 1.6f);
            // Le joueur menotte ne voit PAS ses propres bracelets : ancres a
            // 40 cm sous ses yeux, ils rempliraient la camera en premiere
            // personne. Le claquement et le message suffisent de son cote,
            // le dealer et les autres joueurs les voient sur lui.
            if (estLePnj(acteur)) {
                // Sans arbalete chargee en main, pas de pose crossbow : les
                // bracelets flottaient devant un dealer aux bras ballants.
                porteur.getInventory().setItemInMainHand(arbaleteInvisible());
                dealer.masquerMainBedrock();
            }
            menottesPortees.put(acteur, affichage);
        } else if (!menotte && affichage != null) {
            if (affichage.isValid()) {
                affichage.getWorld().playSound(affichage.getLocation(), Sound.BLOCK_CHAIN_BREAK, 0.8f, 1.1f);
                affichage.remove();
            }
            // Liberation du dealer : les bras retombent, sauf si une visee
            // est en cours (c'est alors elle qui gouverne sa main).
            if (estLePnj(acteur) && poseVise == null) {
                dealer.baisserBras();
                dealer.retirerFusil();
            }
            menottesPortees.remove(acteur);
        }
    }

    private void suivreMenottes() {
        for (Acteur acteur : Acteur.values()) {
            ItemDisplay affichage = menottesPortees.get(acteur);
            if (affichage == null) continue;
            Player porteur = porteurMenottes(acteur);
            if (porteur == null || !porteur.isValid() || !affichage.isValid()) {
                if (affichage.isValid()) affichage.remove();
                menottesPortees.remove(acteur);
                continue;
            }
            menottesPortees.put(acteur,
                    poserFusilPose(affichage, porteur, porteur.getLocation().getYaw(), "menottes", true));
            // Tout ce qui vide la main du dealer entre les tours (montrerFusil,
            // fin de visee...) fait retomber ses bras crossbow. On ne re-equipe
            // QUE main vide -- jamais par-dessus un vrai item, et jamais en
            // boucle (reposer l'item a chaque tick casse l'animation d'usage).
            if (estLePnj(acteur)) {
                var main = porteur.getInventory().getItemInMainHand();
                if (main.isEmpty()) {
                    porteur.getInventory().setItemInMainHand(arbaleteInvisible());
                    dealer.masquerMainBedrock();
                }
            }
        }
    }

    public void fermer() {
        if (tacheMusique != null) tacheMusique.cancel();
        if (tacheMenottes != null) tacheMenottes.cancel();
        menottesPortees.clear();
        tachesEssai.values().forEach(BukkitTask::cancel);
        tachesEssai.clear();
        essais.clear();
        for (Player joueur : Bukkit.getOnlinePlayers()) stopperSiNecessaire(joueur);
        dealer.fermer();
        corpsJoueur.retirer();
        nettoyerDisplays();
    }

    @Override
    public void boireBiere(Player porteur, int ticks, Runnable fin) {
        if (porteur == null || !porteur.isValid()) { fin.run(); return; }
        ItemStack bouteille = InventairePartie.vitrine(net.thundranode.buckshot.jeu.Objet.BIERE);
        geste.boire(porteur, bouteille, ticks);
        porteur.getWorld().playSound(porteur.getLocation(), Sound.ENTITY_GENERIC_DRINK,
                org.bukkit.SoundCategory.PLAYERS, 0.8f, 1.0f);
        Bukkit.getScheduler().runTaskLater(plugin, fin, Math.max(1, ticks));
    }

    @Override
    public void brasInspection(Player porteur, boolean leves) {
        if (porteur == null || !porteur.isValid()) return;
        if (!plugin.getConfig().getBoolean("animations.inspect-bras", true)) return;
        if (leves) {
            try {
                porteur.startUsingItem(org.bukkit.inventory.EquipmentSlot.HAND);
            } catch (RuntimeException ignore) {
                // cosmetique
            }
        } else {
            porteur.clearActiveItem();
        }
    }
}
