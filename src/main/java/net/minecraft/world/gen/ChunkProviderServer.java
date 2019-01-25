package net.minecraft.world.gen;

import akka.util.Unsafe;
import catserver.server.CatServer;
import catserver.server.utils.GenTask;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import io.netty.util.internal.ConcurrentSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.ObjectCollection;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.io.IOException;
import java.util.*;
import javax.annotation.Nullable;

import it.unimi.dsi.fastutil.objects.ObjectSet;
import net.minecraft.crash.CrashReport;
import net.minecraft.crash.CrashReportCategory;
import net.minecraft.entity.EnumCreatureType;
import net.minecraft.server.management.PlayerChunkMapEntry;
import net.minecraft.util.ReportedException;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.MinecraftException;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.chunk.storage.IChunkLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.event.world.ChunkUnloadEvent;

// TODO: This class needs serious testing.
public class ChunkProviderServer implements IChunkProvider
{
    private static final Logger LOGGER = LogManager.getLogger();
    public final Set<Long> droppedChunksSet = new ConcurrentSet<>();
    public final IChunkGenerator chunkGenerator;
    public final IChunkLoader chunkLoader;
    public final Long2ObjectMap<Chunk> id2ChunkMap = Long2ObjectMaps.synchronize(new Long2ObjectOpenHashMap<Chunk>(8192) {
        final String message = "插件/MOD尝试异步操作Long2ObjectMaps已拦截,请与插件/MOD作者反馈!";
        @Override
        public ObjectSet<Map.Entry<Long, Chunk>> entrySet() {
            if (! Bukkit.isPrimaryThread()) {
                new UnsupportedOperationException(message).printStackTrace();
                return new Long2ObjectOpenHashMap<>(id2ChunkMap).entrySet();
            }
            return super.entrySet();
        }

        @Override
        public FastEntrySet<Chunk> long2ObjectEntrySet() {
            if (! Bukkit.isPrimaryThread()) {
                new UnsupportedOperationException(message).printStackTrace();
                return new Long2ObjectOpenHashMap<>(id2ChunkMap).long2ObjectEntrySet();
            }
            return super.long2ObjectEntrySet();
        }
    }); // CatServer - Async comp
    public final WorldServer world;
    private final Set<Long> loadingChunks = com.google.common.collect.Sets.newHashSet();

    public ChunkProviderServer(WorldServer worldObjIn, IChunkLoader chunkLoaderIn, IChunkGenerator chunkGeneratorIn)
    {
        this.world = worldObjIn;
        this.chunkLoader = chunkLoaderIn;
        this.chunkGenerator = chunkGeneratorIn;
    }

    public Collection<Chunk> getLoadedChunks()
    {
        return this.id2ChunkMap.values();
    }

    public void queueUnload(Chunk chunkIn)
    {
        if (this.world.provider.canDropChunk(chunkIn.x, chunkIn.z))
        {
            this.droppedChunksSet.add(ChunkPos.asLong(chunkIn.x, chunkIn.z));
            chunkIn.unloadQueued = true;
        }
    }

    public void queueUnloadAll()
    {
        ObjectIterator objectiterator = this.id2ChunkMap.values().iterator();

        while (objectiterator.hasNext())
        {
            Chunk chunk = (Chunk)objectiterator.next();
            this.queueUnload(chunk);
        }
    }

    @Nullable
    public Chunk getLoadedChunk(int x, int z)
    {
        long i = ChunkPos.asLong(x, z);
        Chunk chunk = this.id2ChunkMap.get(i);

        if (chunk != null)
        {
            chunk.unloadQueued = false;
        }

        return chunk;
    }

    // Is it copy of method above?
    public Chunk getChunkIfLoaded(int x, int z) {
        return id2ChunkMap.get(ChunkPos.asLong(x, z));
    }

    @Nullable
    public Chunk loadChunk(int x, int z)
    {
        return loadChunk(x, z, null);
    }

    @Nullable
    public Chunk loadChunk(int x, int z, @Nullable Runnable runnable)
    {
        Chunk chunk = this.getLoadedChunk(x, z);
        if (chunk == null)
        {
            long pos = ChunkPos.asLong(x, z);
            chunk = net.minecraftforge.common.ForgeChunkManager.fetchDormantChunk(pos, this.world);
            if (chunk != null || !(this.chunkLoader instanceof net.minecraft.world.chunk.storage.AnvilChunkLoader))
            {
                if (!loadingChunks.add(pos)) net.minecraftforge.fml.common.FMLLog.bigWarning("There is an attempt to load a chunk ({},{}) in dimension {} that is already being loaded. This will cause weird chunk breakages.", x, z, this.world.provider.getDimension());
                if (chunk == null) chunk = this.loadChunkFromFile(x, z);

                if (chunk != null)
                {
                this.id2ChunkMap.put(ChunkPos.asLong(x, z), chunk);
                chunk.onLoad();
                chunk.populateCB(this, this.chunkGenerator, false);
                }

                loadingChunks.remove(pos);
            }
            else
            {
                net.minecraft.world.chunk.storage.AnvilChunkLoader loader = (net.minecraft.world.chunk.storage.AnvilChunkLoader) this.chunkLoader;
                if (runnable == null || !net.minecraftforge.common.ForgeChunkManager.asyncChunkLoading)
                    chunk = net.minecraftforge.common.chunkio.ChunkIOExecutor.syncChunkLoad(this.world, loader, this, x, z);
                else if (loader.isChunkGeneratedAt(x, z))
                {
                    // We can only use the async queue for already generated chunks
                    net.minecraftforge.common.chunkio.ChunkIOExecutor.queueChunkLoad(this.world, loader, this, x, z, runnable);
                    return null;
                }
            }
        }

        // If we didn't load the chunk async and have a callback run it now
        if (runnable != null) runnable.run();
        return chunk;
    }
    public void provideChunk__async(int x, int z, PlayerChunkMapEntry entry)
    {
        Chunk chunk = this.loadChunk(x, z);
        if (chunk == null) {
            world.timings.syncChunkLoadTimer.startTiming(); // Spigot
            long i = ChunkPos.asLong(x, z);
            world.addChunkGenQueue(new GenTask(this, chunkGenerator, x, z, i, entry));
            world.timings.syncChunkLoadTimer.stopTiming(); // Spigot
            return;
        }
        entry.chunk = chunk;
    }
    public Chunk provideChunk(int x, int z)
    {
        Chunk chunk = this.loadChunk(x, z);

        if (chunk == null)
        {
            world.timings.syncChunkLoadTimer.startTiming(); // Spigot
            long i = ChunkPos.asLong(x, z);

            try
            {
                chunk = this.chunkGenerator.generateChunk(x, z);
            }
            catch (Throwable throwable)
            {
                CrashReport crashreport = CrashReport.makeCrashReport(throwable, "Exception generating new chunk");
                CrashReportCategory crashreportcategory = crashreport.makeCategory("Chunk to be generated");
                crashreportcategory.addCrashSection("Location", String.format("%d,%d", x, z));
                crashreportcategory.addCrashSection("Position hash", Long.valueOf(i));
                crashreportcategory.addCrashSection("Generator", this.chunkGenerator);
                throw new ReportedException(crashreport);
            }

            this.id2ChunkMap.put(i, chunk);
            chunk.onLoad();
            chunk.populateCB(this, this.chunkGenerator, true);
            world.timings.syncChunkLoadTimer.stopTiming(); // Spigot
        }

        return chunk;
    }

    @Nullable
    private Chunk loadChunkFromFile(int x, int z)
    {
        try
        {
            Chunk chunk = this.chunkLoader.loadChunk(this.world, x, z);

            if (chunk != null)
            {
                chunk.setLastSaveTime(this.world.getTotalWorldTime());
                this.chunkGenerator.recreateStructures(chunk, x, z);
            }

            return chunk;
        }
        catch (Exception exception)
        {
            LOGGER.error("Couldn't load chunk", (Throwable)exception);
            return null;
        }
    }

    private void saveChunkExtraData(Chunk chunkIn)
    {
        try
        {
            this.chunkLoader.saveExtraChunkData(this.world, chunkIn);
        }
        catch (Exception exception)
        {
            LOGGER.error("Couldn't save entities", (Throwable)exception);
        }
    }

    private void saveChunkData(Chunk chunkIn)
    {
        try
        {
            chunkIn.setLastSaveTime(this.world.getTotalWorldTime());
            this.chunkLoader.saveChunk(this.world, chunkIn);
        }
        catch (IOException ioexception)
        {
            LOGGER.error("Couldn't save chunk", (Throwable)ioexception);
        }
        catch (MinecraftException minecraftexception)
        {
            LOGGER.error("Couldn't save chunk; already in use by another instance of Minecraft?", (Throwable)minecraftexception);
        }
    }

    public boolean saveChunks(boolean all)
    {
        int i = 0;
        List<Chunk> list = Lists.newArrayList(this.id2ChunkMap.values());

        for (int j = 0; j < list.size(); ++j)
        {
            Chunk chunk = list.get(j);

            if (all)
            {
                this.saveChunkExtraData(chunk);
            }

            if (chunk.needsSaving(all))
            {
                this.saveChunkData(chunk);
                chunk.setModified(false);
                ++i;

                if (i == 24 && !all)
                {
                    return false;
                }
            }
        }

        return true;
    }

    public void flushToDisk()
    {
        this.chunkLoader.flush();
    }

    public boolean tick()
    {
        if (!this.world.disableLevelSaving)
        {
            if (!this.droppedChunksSet.isEmpty())
            {
                for (ChunkPos forced : this.world.getPersistentChunks().keySet())
                {
                    this.droppedChunksSet.remove(ChunkPos.asLong(forced.x, forced.z));
                }

                Iterator<Long> iterator = this.droppedChunksSet.iterator();

                for (int i = 0; i < 100 && iterator.hasNext(); iterator.remove())
                {
                    Long olong = iterator.next();
                    Chunk chunk = (Chunk)this.id2ChunkMap.get(olong);

                    if (chunk != null && chunk.unloadQueued)
                    {
//                        chunk.onUnload();
//                        net.minecraftforge.common.ForgeChunkManager.putDormantChunk(ChunkPos.asLong(chunk.x, chunk.z), chunk);
//                        this.saveChunkData(chunk);
//                        this.saveChunkExtraData(chunk);
//                        this.id2ChunkMap.remove(olong);
                        if (!unloadChunk(chunk, true)) {
                            continue;
                        }
                        ++i;
                    }
                }
            }

            //if (this.id2ChunkMap.isEmpty()) net.minecraftforge.common.DimensionManager.unloadWorld(this.world.provider.getDimension()); //CatServer - Disable forge unload world

            this.chunkLoader.chunkTick();
        }

        return false;
    }

    public boolean unloadChunk(Chunk chunk, boolean save) {
        ChunkUnloadEvent event = new ChunkUnloadEvent(chunk.bukkitChunk, save);
        this.world.getServer().getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return false;
        }
        save = event.isSaveChunk();

        // Update neighbor counts
        for (int x = -2; x < 3; x++) {
            for (int z = -2; z < 3; z++) {
                if (x == 0 && z == 0) {
                    continue;
                }

                Chunk neighbor = this.getChunkIfLoaded(chunk.x + x, chunk.z + z);
                if (neighbor != null) {
                    neighbor.setNeighborUnloaded(-x, -z);
                    chunk.setNeighborUnloaded(x, z);
                }
            }
        }
        // Moved from unloadChunks above
        chunk.onUnload();
        net.minecraftforge.common.ForgeChunkManager.putDormantChunk(ChunkPos.asLong(chunk.x, chunk.z), chunk);
        this.saveChunkData(chunk);
        this.saveChunkExtraData(chunk);
        this.id2ChunkMap.remove(chunk.chunkKey);
        return true;
    }

    public boolean canSave()
    {
        return !this.world.disableLevelSaving;
    }

    public String makeString()
    {
        return "ServerChunkCache: " + this.id2ChunkMap.size() + " Drop: " + this.droppedChunksSet.size();
    }

    public List<Biome.SpawnListEntry> getPossibleCreatures(EnumCreatureType creatureType, BlockPos pos)
    {
        return this.chunkGenerator.getPossibleCreatures(creatureType, pos);
    }

    @Nullable
    public BlockPos getNearestStructurePos(World worldIn, String structureName, BlockPos position, boolean findUnexplored)
    {
        return this.chunkGenerator.getNearestStructurePos(worldIn, structureName, position, findUnexplored);
    }

    public boolean isInsideStructure(World worldIn, String structureName, BlockPos pos)
    {
        return this.chunkGenerator.isInsideStructure(worldIn, structureName, pos);
    }

    public int getLoadedChunkCount()
    {
        return this.id2ChunkMap.size();
    }

    public boolean chunkExists(int x, int z)
    {
        return this.id2ChunkMap.containsKey(ChunkPos.asLong(x, z));
    }

    public boolean isChunkGeneratedAt(int x, int z)
    {
        return this.id2ChunkMap.containsKey(ChunkPos.asLong(x, z)) || this.chunkLoader.isChunkGeneratedAt(x, z);
    }
}