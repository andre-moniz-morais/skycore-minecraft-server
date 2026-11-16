package dev.skycore.world;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

import java.util.List;
import java.util.Random;

/** Empty world generator with a single biome. */
public final class VoidGenerator extends ChunkGenerator {
    private final Biome biome;

    public VoidGenerator(Biome biome) {
        this.biome = biome;
    }

    @Override public boolean shouldGenerateNoise() { return false; }
    @Override public boolean shouldGenerateSurface() { return false; }
    @Override public boolean shouldGenerateBedrock() { return false; }
    @Override public boolean shouldGenerateCaves() { return false; }
    @Override public boolean shouldGenerateDecorations() { return false; }
    @Override public boolean shouldGenerateMobs() { return false; }
    @Override public boolean shouldGenerateStructures() { return false; }
    @Override public boolean canSpawn(World world, int x, int z) { return true; }

    @Override
    public Location getFixedSpawnLocation(World world, Random random) {
        return new Location(world, 0.5, 101, 0.5);
    }

    @Override
    public BiomeProvider getDefaultBiomeProvider(WorldInfo worldInfo) {
        return new BiomeProvider() {
            @Override
            public Biome getBiome(WorldInfo info, int x, int y, int z) {
                return biome;
            }

            @Override
            public List<Biome> getBiomes(WorldInfo info) {
                return List.of(biome);
            }
        };
    }
}
