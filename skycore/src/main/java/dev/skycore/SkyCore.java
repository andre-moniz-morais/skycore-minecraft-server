package dev.skycore;

import dev.skycore.auto.*;
import dev.skycore.cmd.Commands;
import dev.skycore.config.Settings;
import dev.skycore.crops.CropService;
import dev.skycore.data.DataStore;
import dev.skycore.data.Database;
import dev.skycore.economy.Economy;
import dev.skycore.economy.VaultEconomy;
import dev.skycore.gen.GeneratorService;
import dev.skycore.goals.*;
import dev.skycore.gui.ChatInput;
import dev.skycore.gui.MenuListener;
import dev.skycore.hud.Hud;
import dev.skycore.island.*;
import dev.skycore.items.SoulboundListener;
import dev.skycore.items.SpecialItems;
import dev.skycore.market.AuctionHouse;
import dev.skycore.market.PlayerShopService;
import dev.skycore.ops.Alerts;
import dev.skycore.ops.Operations;
import dev.skycore.pets.PetService;
import dev.skycore.ranking.WeeklyRanking;
import dev.skycore.shop.Pricing;
import dev.skycore.shop.SellService;
import dev.skycore.shop.ShopMenus;
import dev.skycore.skills.Activity;
import dev.skycore.skills.PlacedTracker;
import dev.skycore.skills.SkillListener;
import dev.skycore.skills.SkillService;
import dev.skycore.util.Keys;
import dev.skycore.world.Worlds;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;

import java.io.File;
import java.util.logging.Level;

/**
 * SkyCore - every server-specific mechanic of the skyblock design lives here.
 * Golden rules enforced in code: Roses only from active skill actions; Roses never move between accounts;
 * automation never gives XP or Roses; everything has a limit; no I/O on the main thread.
 */
public final class SkyCore extends JavaPlugin {
    public Settings settings;
    public Database db;
    public DataStore data;
    public Economy eco;
    public Worlds worlds;
    public IslandService islands;
    public UpgradeService upgrades;
    public IslandMenus islandMenus;
    public PlacedTracker placed;
    public Activity activity;
    public SkillService skills;
    public SkillListener skillListener;
    public GeneratorService generators;
    public SpecialItems items;
    public Pricing pricing;
    public SellService sell;
    public ShopMenus shopMenus;
    public SellChestService sellChests;
    public MinionService minions;
    public ChunkHopperService chunkHoppers;
    public SpawnerService spawners;
    public CropService crops;
    public PetService pets;
    public AuctionHouse ah;
    public PlayerShopService playerShops;
    public RewardService rewards;
    public DailyMissions daily;
    public IslandMissions islandMissions;
    public TierPath path;
    public Prestige prestige;
    public GoalMenus goalMenus;
    public WeeklyRanking ranking;
    public Hud hud;
    public Alerts alerts;
    public Operations ops;
    public ChatInput chatInput;

    @Override
    public void onEnable() {
        Keys.init(this);
        getDataFolder().mkdirs();
        try {
            settings = new Settings(this);
            db = new Database(new File(getDataFolder(), "skycore.db"), getLogger());
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "SkyCore failed to start: " + e.getMessage(), e);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        data = new DataStore(db, getLogger());
        data.loadAll();
        if (data.global.spacingAtCreation != 0 && data.global.spacingAtCreation != settings.spacing)
            getLogger().severe("islands.spacing changed from " + data.global.spacingAtCreation + " to " + settings.spacing
                    + " after islands were created! Restore the original value.");

        eco = new Economy(this);
        alerts = new Alerts(this);
        items = new SpecialItems(this);
        worlds = new Worlds(this);
        worlds.init();
        islands = new IslandService(this);
        islands.reindex();
        upgrades = new UpgradeService(this);
        islandMenus = new IslandMenus(this);
        placed = new PlacedTracker(this);
        activity = new Activity(this);
        skills = new SkillService(this);
        skillListener = new SkillListener(this);
        generators = new GeneratorService(this);
        pricing = new Pricing(this);
        sell = new SellService(this);
        shopMenus = new ShopMenus(this);
        sellChests = new SellChestService(this);
        minions = new MinionService(this);
        chunkHoppers = new ChunkHopperService(this);
        spawners = new SpawnerService(this);
        crops = new CropService(this);
        pets = new PetService(this);
        ah = new AuctionHouse(this);
        playerShops = new PlayerShopService(this);
        rewards = new RewardService(this);
        daily = new DailyMissions(this);
        islandMissions = new IslandMissions(this);
        path = new TierPath(this);
        prestige = new Prestige(this);
        goalMenus = new GoalMenus(this);
        ranking = new WeeklyRanking(this);
        hud = new Hud(this);
        ops = new Operations(this);
        chatInput = new ChatInput(this);

        buildSpawn();

        register(new MenuListener(), chatInput, new SoulboundListener(this), new ProtectionListener(this), new PortalListener(this),
                placed, activity, skillListener, generators, new AutomationListener(this), chunkHoppers, spawners, crops,
                sell, pets, hud);
        new Commands(this).register();
        VaultEconomy.register(this);
        scheduleTasks();
        getLogger().info("SkyCore enabled: " + data.islands.size() + " islands, " + settings.priceGroups.size() + " price groups.");
    }

    private void register(Listener... listeners) {
        for (Listener l : listeners) Bukkit.getPluginManager().registerEvents(l, this);
    }

    private void buildSpawn() {
        if (data.global.spawnBuilt) return;
        Location s = worlds.spawn();
        s.getWorld().getChunkAtAsync(s).thenAccept(c -> {
            islands.builder.spawn(s);
            data.global.spawnBuilt = true;
            data.dirtyGlobal();
            getLogger().info("Spawn platform built.");
        });
    }

    private void scheduleTasks() {
        BukkitScheduler s = Bukkit.getScheduler();
        var a = settings.config;
        s.runTaskTimer(this, data::flush, 600L, 600L);
        s.runTaskTimer(this, islands::tickBorders, 20L, 20L);
        s.runTaskTimer(this, skillListener::tickHaste, 100L, 100L);
        s.runTaskTimer(this, minions::tick, 200L, a.getLong("automation.minion-batch-seconds", 10) * 20L);
        s.runTaskTimer(this, sellChests::tick, 100L, a.getLong("automation.sell-chest-interval-seconds", 5) * 20L);
        s.runTaskTimer(this, chunkHoppers::tick, 40L, a.getLong("automation.chunk-hopper-interval-seconds", 2) * 20L);
        s.runTaskTimer(this, sellChests::flushSummaries, 1200L, a.getLong("automation.sale-summary-seconds", 60) * 20L);
        s.runTaskTimer(this, spawners::tick, 40L, 20L);
        s.runTaskTimer(this, crops::tick, 20L, 20L);
        s.runTaskTimer(this, islandMissions::tick, 200L, 200L);
        s.runTaskTimer(this, path::tick, 220L, 200L);
        s.runTaskTimer(this, hud::tick, 40L, Math.max(10L, a.getLong("hud.update-ticks", 40)));
        s.runTaskTimer(this, () -> {
            ranking.tick();
            activity.tickMinute();
            ah.expire();
            ops.tickMinute();
            hud.cleanTitles();
        }, 1200L, 1200L);
    }

    /** Persists everything now (used before backups and on shutdown). */
    public void saveAll() {
        chunkHoppers.saveAll();
        placed.flushAll();
        data.flush();
    }

    public void reloadSettings() {
        settings = new Settings(this);
        generators.reload();
        placed.reload();
        islandMissions.reload();
        path.reload();
    }

    @Override
    public void onDisable() {
        if (data == null) return;
        try {
            ranking.removeHologram();
            saveAll();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Error while saving on shutdown", e);
        }
        db.close();
    }
}
