package dev.skycore.config;

import dev.skycore.skills.Skill;
import dev.skycore.world.Dim;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.time.ZoneId;
import java.util.*;

/**
 * Typed view over every YAML file. Re-created on /sc reload.
 * Parsing errors are fatal at startup so bad balance numbers never go live silently.
 */
public final class Settings {

    public record Cost(double value, long roses, double dollars) {
        public static final Cost FREE = new Cost(0, 0, 0);
    }

    public record IslandLevel(int level, int size, int minions, int hoppers, int chunkHoppers, int sellChests,
                              int shops, int spawnerStack, int spawnersTotal, Cost cost) {}

    public record GenLevel(int level, int islandLevel, double frameChance, Cost cost, Map<Material, Double> blocks) {}

    public record SimpleUpgrade(int level, double amount, int islandLevel, Cost cost) {}

    public record ExtraIsland(int number, int mainLevel, long roses, double dollars) {}

    public record CropType(String id, String name, Material seed, Material block, int farming, long roses,
                           double dollars, Material drop, int amount, boolean netherOnly) {}

    public record MinionTier(int tier, int cycleSeconds, int storageStacks, long roses, double dollars, int skillLevel) {}

    public record MinionResource(String id, String name, Skill skill, Material output, Dim dimension,
                                 Material generatorBlock, String crop, int skillLevel, boolean expensive,
                                 Dim placeDimension) {}

    public record Loot(Material item, double amount, double chance) {}

    public record SpawnerType(String id, String name, EntityType entity, Dim dimension, int combat, long roses,
                              double dollars, List<Loot> loot) {}

    public record WandTier(int tier, int uses, long cooldownMs, int radius, double factor, long roses, double dollars) {}

    public record PetRarity(String id, int order, String name, String color, long roses, long evolveCost,
                            double skillXp, double skillSell, double generalSell, double regrow) {}

    public record PetType(String id, String name, String kind, Skill skill, Material icon) {}

    public record PriceGroup(String id, String name, Material icon, Skill skill, LinkedHashMap<Material, Double> items) {}

    // ---------------------------------------------------------------- raw files
    public final YamlConfiguration config, prices, skills, islands, generators, automation, spawners, pets, goals;

    // ---------------------------------------------------------------- general
    public final ZoneId zone;
    public final String worldOverworld, worldNether, worldEnd;
    public final int spacing, islandHeight, maxMembers, miniMinSize;
    public final double miniRatio;

    // ---------------------------------------------------------------- prices
    public final Map<Material, Double> sellPrice = new HashMap<>();
    public final Map<Material, Skill> priceSkill = new HashMap<>();
    public final List<PriceGroup> priceGroups = new ArrayList<>();
    public final LinkedHashMap<Material, Double> buyOnly = new LinkedHashMap<>();
    public final Map<Material, Double> storageBlockValue = new HashMap<>();

    // ---------------------------------------------------------------- islands
    public final TreeMap<Integer, IslandLevel> islandLevels = new TreeMap<>();
    public final Map<Dim, Double> spawnerDimFactor = new EnumMap<>(Dim.class);
    public final Map<Integer, ExtraIsland> extraIslands = new TreeMap<>();
    public final TreeMap<Integer, SimpleUpgrade> sellMultiplier = new TreeMap<>();
    public final TreeMap<Integer, SimpleUpgrade> cropGrowth = new TreeMap<>();

    // ---------------------------------------------------------------- generators
    public final Map<Dim, TreeMap<Integer, GenLevel>> gens = new EnumMap<>(Dim.class);

    // ---------------------------------------------------------------- automation
    public final LinkedHashMap<String, CropType> crops = new LinkedHashMap<>();
    public final Map<Material, CropType> cropBySeed = new HashMap<>();
    public final TreeMap<Integer, MinionTier> minionTiers = new TreeMap<>();
    public final LinkedHashMap<String, MinionResource> minionResources = new LinkedHashMap<>();
    public final TreeMap<Integer, WandTier> wandTiers = new TreeMap<>();

    // ---------------------------------------------------------------- spawners / pets
    public final LinkedHashMap<String, SpawnerType> spawnerTypes = new LinkedHashMap<>();
    public final LinkedHashMap<String, PetRarity> petRarities = new LinkedHashMap<>();
    public final LinkedHashMap<String, PetType> petTypes = new LinkedHashMap<>();

    // ---------------------------------------------------------------- skills
    public final Map<Material, Double> miningXp = new HashMap<>();

    public Settings(JavaPlugin plugin) {
        config = load(plugin, "config.yml");
        prices = load(plugin, "prices.yml");
        skills = load(plugin, "skills.yml");
        islands = load(plugin, "islands.yml");
        generators = load(plugin, "generators.yml");
        automation = load(plugin, "automation.yml");
        spawners = load(plugin, "spawners.yml");
        pets = load(plugin, "pets.yml");
        goals = load(plugin, "goals.yml");

        zone = ZoneId.of(config.getString("timezone", "Europe/Lisbon"));
        worldOverworld = config.getString("worlds.overworld", "skyblock");
        worldNether = config.getString("worlds.nether", "skyblock_nether");
        worldEnd = config.getString("worlds.end", "skyblock_end");
        spacing = config.getInt("islands.spacing", 1000);
        islandHeight = config.getInt("islands.height", 100);
        maxMembers = config.getInt("islands.max-members", 4);
        miniRatio = config.getDouble("islands.mini-island-ratio", 0.4);
        miniMinSize = config.getInt("islands.mini-island-min-size", 24);

        parsePrices();
        parseIslands();
        parseGenerators();
        parseAutomation();
        parseSpawners();
        parsePets();
        ConfigurationSection mx = skills.getConfigurationSection("mining.xp");
        if (mx != null) for (String k : mx.getKeys(false)) miningXp.put(mat(k), mx.getDouble(k));
    }

    private static YamlConfiguration load(JavaPlugin plugin, String name) {
        File f = new File(plugin.getDataFolder(), name);
        if (!f.exists()) plugin.saveResource(name, false);
        YamlConfiguration y = new YamlConfiguration();
        try {
            y.load(f);
        } catch (Exception e) {
            throw new IllegalStateException("Could not parse " + name + ": " + e.getMessage(), e);
        }
        return y;
    }

    public static Material mat(String s) {
        Material m = Material.matchMaterial(s.trim());
        if (m == null) throw new IllegalArgumentException("Unknown material: " + s);
        return m;
    }

    private static Cost cost(ConfigurationSection s) {
        if (s == null) return Cost.FREE;
        return new Cost(s.getDouble("value"), s.getLong("roses"), s.getDouble("dollars"));
    }

    private void parsePrices() {
        ConfigurationSection groups = prices.getConfigurationSection("groups");
        for (String id : groups.getKeys(false)) {
            ConfigurationSection g = groups.getConfigurationSection(id);
            Skill skill = Skill.parse(g.getString("skill"));
            LinkedHashMap<Material, Double> items = new LinkedHashMap<>();
            ConfigurationSection is = g.getConfigurationSection("items");
            for (String k : is.getKeys(false)) {
                Material m = mat(k);
                double p = is.getDouble(k);
                items.put(m, p);
                sellPrice.put(m, p);
                priceSkill.put(m, skill);
            }
            priceGroups.add(new PriceGroup(id, g.getString("name", id), mat(g.getString("icon", "STONE")), skill, items));
        }
        ConfigurationSection sb = prices.getConfigurationSection("storage-blocks");
        if (sb != null) for (String k : sb.getKeys(false)) {
            Material block = mat(k);
            Material item = mat(sb.getString(k + ".item"));
            double v = sellPrice.getOrDefault(item, 0d) * sb.getInt(k + ".count");
            storageBlockValue.put(block, v);
            if (!sellPrice.containsKey(block) && v > 0) {
                sellPrice.put(block, v);
                Skill s = priceSkill.get(item);
                if (s != null) priceSkill.put(block, s);
            }
        }
        ConfigurationSection bo = prices.getConfigurationSection("buy-only");
        if (bo != null) for (String k : bo.getKeys(false)) buyOnly.put(mat(k), bo.getDouble(k));
    }

    private void parseIslands() {
        ConfigurationSection lv = islands.getConfigurationSection("levels");
        for (String k : lv.getKeys(false)) {
            ConfigurationSection s = lv.getConfigurationSection(k);
            int level = Integer.parseInt(k);
            islandLevels.put(level, new IslandLevel(level, s.getInt("size"), s.getInt("minions"), s.getInt("hoppers"),
                    s.getInt("chunk-hoppers"), s.getInt("sell-chests"), s.getInt("shops"), s.getInt("spawner-stack"),
                    s.getInt("spawners-total"), cost(s.getConfigurationSection("cost"))));
        }
        for (Dim d : Dim.values())
            spawnerDimFactor.put(d, islands.getDouble("spawner-stack-dimension-factor." + d.id, 1.0));
        ConfigurationSection ex = islands.getConfigurationSection("extra-islands");
        if (ex != null) for (String k : ex.getKeys(false)) {
            int n = Integer.parseInt(k);
            extraIslands.put(n, new ExtraIsland(n, ex.getInt(k + ".main-level"), ex.getLong(k + ".roses"), ex.getDouble(k + ".dollars")));
        }
        ConfigurationSection sm = islands.getConfigurationSection("sell-multiplier");
        for (String k : sm.getKeys(false)) {
            ConfigurationSection s = sm.getConfigurationSection(k);
            int l = Integer.parseInt(k);
            sellMultiplier.put(l, new SimpleUpgrade(l, s.getDouble("multiplier"), s.getInt("island-level"), cost(s.getConfigurationSection("cost"))));
        }
        ConfigurationSection cg = islands.getConfigurationSection("crop-growth");
        for (String k : cg.getKeys(false)) {
            ConfigurationSection s = cg.getConfigurationSection(k);
            int l = Integer.parseInt(k);
            cropGrowth.put(l, new SimpleUpgrade(l, s.getDouble("seconds"), s.getInt("island-level"), cost(s.getConfigurationSection("cost"))));
        }
    }

    private void parseGenerators() {
        for (Dim d : Dim.values()) {
            TreeMap<Integer, GenLevel> map = new TreeMap<>();
            ConfigurationSection sec = generators.getConfigurationSection(d.id);
            if (sec != null) for (String k : sec.getKeys(false)) {
                ConfigurationSection s = sec.getConfigurationSection(k);
                LinkedHashMap<Material, Double> blocks = new LinkedHashMap<>();
                ConfigurationSection b = s.getConfigurationSection("blocks");
                for (String m : b.getKeys(false)) blocks.put(mat(m), b.getDouble(m));
                int l = Integer.parseInt(k);
                map.put(l, new GenLevel(l, s.getInt("island-level"), s.getDouble("frame-chance", 0),
                        cost(s.getConfigurationSection("cost")), blocks));
            }
            gens.put(d, map);
        }
    }

    private void parseAutomation() {
        ConfigurationSection ct = automation.getConfigurationSection("special-crops.types");
        for (String id : ct.getKeys(false)) {
            ConfigurationSection s = ct.getConfigurationSection(id);
            CropType c = new CropType(id, s.getString("name", id), mat(s.getString("seed")), mat(s.getString("block")),
                    s.getInt("farming"), s.getLong("roses"), s.getDouble("dollars"), mat(s.getString("drop")),
                    s.getInt("amount"), s.getBoolean("nether-only"));
            crops.put(id, c);
            cropBySeed.put(c.seed(), c);
        }
        ConfigurationSection mt = automation.getConfigurationSection("minions.tiers");
        for (String k : mt.getKeys(false)) {
            ConfigurationSection s = mt.getConfigurationSection(k);
            int t = Integer.parseInt(k);
            minionTiers.put(t, new MinionTier(t, s.getInt("cycle-seconds"), s.getInt("storage-stacks"), s.getLong("roses"),
                    s.getDouble("dollars"), s.getInt("skill-level")));
        }
        ConfigurationSection mr = automation.getConfigurationSection("minions.resources");
        for (String id : mr.getKeys(false)) {
            ConfigurationSection s = mr.getConfigurationSection(id);
            String dim = s.getString("dimension");
            String gb = s.getString("generator-block");
            String pd = s.getString("place-dimension");
            minionResources.put(id, new MinionResource(id, s.getString("name", id), Skill.parse(s.getString("skill")),
                    mat(s.getString("output")), dim == null ? null : Dim.parse(dim), gb == null ? null : mat(gb),
                    s.getString("crop"), s.getInt("skill-level", 1), s.getBoolean("expensive"),
                    pd == null ? null : Dim.parse(pd)));
        }
        ConfigurationSection wt = automation.getConfigurationSection("sell-wands.tiers");
        for (String k : wt.getKeys(false)) {
            ConfigurationSection s = wt.getConfigurationSection(k);
            int t = Integer.parseInt(k);
            wandTiers.put(t, new WandTier(t, s.getInt("uses"), s.getLong("cooldown-ms"), s.getInt("radius"),
                    s.getDouble("factor"), s.getLong("roses"), s.getDouble("dollars")));
        }
    }

    @SuppressWarnings("unchecked")
    private void parseSpawners() {
        ConfigurationSection t = spawners.getConfigurationSection("types");
        for (String id : t.getKeys(false)) {
            ConfigurationSection s = t.getConfigurationSection(id);
            List<Loot> loot = new ArrayList<>();
            for (Map<?, ?> m : s.getMapList("loot")) {
                loot.add(new Loot(mat(String.valueOf(m.get("item"))),
                        ((Number) ((Map<String, Object>) m).getOrDefault("amount", 1)).doubleValue(),
                        ((Number) ((Map<String, Object>) m).getOrDefault("chance", 1.0)).doubleValue()));
            }
            EntityType et = EntityType.valueOf(s.getString("entity").toUpperCase());
            spawnerTypes.put(id, new SpawnerType(id, s.getString("name", id), et, Dim.parse(s.getString("dimension")),
                    s.getInt("combat"), s.getLong("roses"), s.getDouble("dollars"), loot));
        }
    }

    private void parsePets() {
        ConfigurationSection r = pets.getConfigurationSection("rarities");
        int order = 0;
        for (String id : r.getKeys(false)) {
            ConfigurationSection s = r.getConfigurationSection(id);
            petRarities.put(id, new PetRarity(id, order++, s.getString("name", id), s.getString("color", "<white>"),
                    s.getLong("roses"), s.getLong("evolve-cost"), s.getDouble("skill-xp"), s.getDouble("skill-sell"),
                    s.getDouble("general-sell"), s.getDouble("regrow")));
        }
        ConfigurationSection ty = pets.getConfigurationSection("types");
        for (String id : ty.getKeys(false)) {
            ConfigurationSection s = ty.getConfigurationSection(id);
            petTypes.put(id, new PetType(id, s.getString("name", id), s.getString("kind", "skill"),
                    s.getString("skill") == null ? null : Skill.parse(s.getString("skill")), mat(s.getString("icon", "BONE"))));
        }
    }

    // ---------------------------------------------------------------- helpers
    public IslandLevel islandLevel(int level) {
        IslandLevel l = islandLevels.get(Math.max(1, Math.min(level, islandLevels.lastKey())));
        return l;
    }

    public int maxIslandLevel() {
        return islandLevels.lastKey();
    }

    public double sellPrice(Material m) {
        return sellPrice.getOrDefault(m, 0d);
    }

    public PetRarity nextRarity(String current) {
        boolean found = false;
        for (PetRarity r : petRarities.values()) {
            if (found) return r;
            if (r.id().equals(current)) found = true;
        }
        return null;
    }
}
