package xyz.nucleoid.plasmid.game.world.bubble;

import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.registry.RegistryKey;
import net.minecraft.world.World;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import xyz.nucleoid.plasmid.game.player.PlayerSnapshot;
import xyz.nucleoid.plasmid.game.world.generator.VoidChunkGenerator;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class BubbleWorld implements AutoCloseable {
    private static final Map<RegistryKey<World>, BubbleWorld> OPEN_WORLDS = new Reference2ObjectOpenHashMap<>();

    private final ServerWorld world;
    private final BubbleWorldConfig config;

    private final Map<ServerPlayerEntity, PlayerSnapshot> players = new HashMap<>();

    private BubbleWorld(ServerWorld world, BubbleWorldConfig config) {
        this.world = world;
        this.config = config;

        this.open();
    }

    @Nullable
    public static BubbleWorld forWorld(World world) {
        return OPEN_WORLDS.get(world.getRegistryKey());
    }

    @Nullable
    public static BubbleWorld tryOpen(MinecraftServer server, BubbleWorldConfig config) {
        ServerWorld world = findFreeWorld(server);
        if (world == null) {
            return null;
        }

        BubbleWorld bubbleWorld = BubbleWorld.tryCreate(world, config);
        if (bubbleWorld == null) {
            return null;
        }

        OPEN_WORLDS.put(world.getRegistryKey(), bubbleWorld);

        return bubbleWorld;
    }

    @Nullable
    private static ServerWorld findFreeWorld(MinecraftServer server) {
        for (ServerWorld world : server.getWorlds()) {
            RegistryKey<World> dimensionKey = world.getRegistryKey();
            if (!OPEN_WORLDS.containsKey(dimensionKey) && BubbleWorld.accepts(world)) {
                return world;
            }
        }
        return null;
    }

    @Nullable
    private static BubbleWorld tryCreate(ServerWorld world, BubbleWorldConfig config) {
        if (!BubbleWorld.accepts(world)) {
            return null;
        }

        return new BubbleWorld(world, config);
    }

    private static boolean accepts(ServerWorld world) {
        return world.getChunkManager().getChunkGenerator() instanceof BubbleChunkGenerator;
    }

    private void open() {
        this.clearPlayers();
        this.setWorldGenerator(this.config.getGenerator());

        ((BubbleChunkControl) this.world.getChunkManager()).enable();
    }

    @Override
    public void close() {
        this.clearPlayers();

        this.setWorldGenerator(VoidChunkGenerator.INSTANCE);
        ((BubbleChunkControl) this.world.getChunkManager()).disable();

        OPEN_WORLDS.remove(this.getDimensionKey(), this);
    }

    public boolean addPlayer(ServerPlayerEntity player) {
        if (!this.players.containsKey(player)) {
            this.players.put(player, PlayerSnapshot.take(player));
            this.joinPlayer(player);
            return true;
        }
        return false;
    }

    public boolean removePlayer(ServerPlayerEntity player) {
        PlayerSnapshot snapshot = this.players.remove(player);
        if (snapshot != null) {
            snapshot.restore(player);
            return true;
        }
        return false;
    }

    private void joinPlayer(ServerPlayerEntity player) {
        player.inventory.clear();
        player.getEnderChestInventory().clear();

        player.setHealth(20.0F);
        player.getHungerManager().setFoodLevel(20);
        player.fallDistance = 0.0F;
        player.clearStatusEffects();

        player.setFireTicks(0);
        player.inLava = false;
        player.stopFallFlying();

        player.setGameMode(this.config.getDefaultGameMode());

        Vec3d spawnPos = this.config.getSpawnPos();
        player.teleport(this.world, spawnPos.x, spawnPos.y, spawnPos.z, 0.0F, 0.0F);
    }

    private void clearPlayers() {
        PlayerManager playerManager = this.world.getServer().getPlayerManager();

        List<ServerPlayerEntity> players = new ArrayList<>(this.world.getPlayers());

        for (ServerPlayerEntity player : players) {
            if (!this.removePlayer(player) || player.world == this.world) {
                playerManager.respawnPlayer(player, true);
            }
        }
    }

    private void setWorldGenerator(ChunkGenerator generator) {
        ServerChunkManager chunkManager = this.world.getChunkManager();

        BubbleChunkGenerator dynamicGenerator = (BubbleChunkGenerator) chunkManager.getChunkGenerator();
        dynamicGenerator.setGenerator(generator);
    }

    public ServerWorld getWorld() {
        return this.world;
    }

    public RegistryKey<World> getDimensionKey() {
        return this.world.getRegistryKey();
    }

    public Set<ServerPlayerEntity> getPlayers() {
        return this.players.keySet();
    }
}