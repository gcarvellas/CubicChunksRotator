/*
 *  This file is part of CubicChunksConverter, licensed under the MIT License (MIT).
 *
 *  Copyright (c) 2017-2021 contributors
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in
 *  all copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *  THE SOFTWARE.
 */
package cubicchunks.converter.lib.convert.io;

import com.carrotsearch.hppc.IntArrayList;
import com.carrotsearch.hppc.cursors.IntCursor;
import cubicchunks.converter.lib.Dimension;
import cubicchunks.converter.lib.Dimensions;
import cubicchunks.converter.lib.conf.ConverterConfig;
import cubicchunks.converter.lib.convert.data.PriorityCubicChunksColumnData;
import cubicchunks.converter.lib.util.*;
import cubicchunks.converter.lib.util.edittask.EditTask;
import cubicchunks.regionlib.api.region.IRegionProvider;
import cubicchunks.regionlib.api.region.key.RegionKey;
import cubicchunks.regionlib.impl.EntryLocation2D;
import cubicchunks.regionlib.impl.EntryLocation3D;
import cubicchunks.regionlib.impl.SaveCubeColumns;
import cubicchunks.regionlib.impl.save.SaveSection2D;
import cubicchunks.regionlib.impl.save.SaveSection3D;
import cubicchunks.regionlib.lib.ExtRegion;
import cubicchunks.regionlib.lib.provider.SimpleRegionProvider;
import cubicchunks.regionlib.util.CheckedConsumer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static cubicchunks.converter.lib.util.Utils.interruptibleConsumer;

public class PriorityCubicChunkReader extends BaseMinecraftReader<PriorityCubicChunksColumnData, SaveCubeColumns> {

    private final CompletableFuture<ChunkList> chunkList = new CompletableFuture<>();
    private final Thread loadThread;
    private static final Map<SaveCubeColumns, List<IRegionProvider<EntryLocation2D>>> providers2d = new WeakHashMap<>();
    private static final Map<SaveCubeColumns, List<IRegionProvider<EntryLocation3D>>> providers3d = new WeakHashMap<>();

    private final Map<String, List<BoundingBox>> regionBoundingBoxes = new HashMap<>();
    private final Map<String, List<BoundingBox>> createIfMissingBoxes = new HashMap<>();

    public PriorityCubicChunkReader(Path srcDir, ConverterConfig config) {
        super(srcDir, (dim, path) -> Files.exists(getDimensionPath(dim, path)) ? createSave(getDimensionPath(dim, path)) : null);
        loadThread = Thread.currentThread();
        if(config.hasValue("relocations")) {
            @SuppressWarnings("unchecked") List<EditTask> tasks = (List<EditTask>) config.getValue("relocations");

            for (EditTask task : tasks) {
                // TODO: handle dimensions better
                for (Dimension dim : Dimensions.getDimensions()) {
                    if(!task.handlesDimension(dim.getDirectory())) {
                        continue;
                    }
                    regionBoundingBoxes.computeIfAbsent(dim.getDirectory(), x -> new ArrayList<>());
                    createIfMissingBoxes.computeIfAbsent(dim.getDirectory(), x -> new ArrayList<>());

                    List<BoundingBox> srcBoxes = task.getSrcBoxes();
                    srcBoxes.forEach(box -> regionBoundingBoxes.get(dim.getDirectory()).add(box.asRegionCoords(new Vector3i(16, 16, 16))));
                    task.getDstBoxes().forEach(box -> regionBoundingBoxes.get(dim.getDirectory()).add(box.asRegionCoords(new Vector3i(16, 16, 16))));
                    if(task.createSrcCubesIfMissing()) {
                        this.createIfMissingBoxes.get(dim.getDirectory()).addAll(srcBoxes);
                    }
                }
            }
        }
    }

    private static Path getDimensionPath(Dimension d, Path worldDir) {
        if (!d.getDirectory().isEmpty()) {
            worldDir = worldDir.resolve(d.getDirectory());
        }
        return worldDir;
    }

    @Override public void countInputChunks(Runnable increment) throws IOException {
        try {
            Map<Dimension, List<Map.Entry<EntryLocation2D, IntArrayList>>> dimensions = doCountChunks(increment);
            chunkList.complete(new ChunkList(dimensions));
        } catch (UncheckedInterruptedException ex) {
            chunkList.complete(null);
        }
    }

    private Map<Dimension, List<Map.Entry<EntryLocation2D, IntArrayList>>> doCountChunks(Runnable increment) throws IOException, UncheckedInterruptedException {
        Map<Dimension, List<Map.Entry<EntryLocation2D, IntArrayList>>> dimensions = new HashMap<>();
        for (Map.Entry<Dimension, SaveCubeColumns> entry : saves.entrySet()) {
            SaveCubeColumns save = entry.getValue();
            Dimension dim = entry.getKey();
            List<Map.Entry<EntryLocation2D, IntArrayList>> chunks = dimensions.computeIfAbsent(dim, p -> new ArrayList<>());
            Map<EntryLocation2D, IntArrayList> chunksMap = new ConcurrentHashMap<>();

            List<IRegionProvider<EntryLocation3D>> regionProviders = providers3d.get(save);

            // TODO: efficient hashset for this
            Set<Vector3i> toCreateIfMissing = new HashSet<>();
            List<BoundingBox> createIfMissingList = createIfMissingBoxes.get(dim.getDirectory());
            if (createIfMissingList != null) {
                createIfMissingList.forEach(box -> box.forEach(toCreateIfMissing::add));
            }

            CheckedConsumer<EntryLocation3D, IOException> addToMap = interruptibleConsumer(loc -> {
                EntryLocation2D loc2d = new EntryLocation2D(loc.getEntryX(), loc.getEntryZ());
                chunksMap.computeIfAbsent(loc2d, l -> {
                    increment.run();
                    IntArrayList arr = new IntArrayList();
                    chunks.add(new AbstractMap.SimpleEntry<>(loc2d, arr));
                    return arr;
                }).add(loc.getEntryY());
            });

            CheckedConsumer<EntryLocation3D, IOException> addFromDisk = interruptibleConsumer(loc -> {
                addToMap.accept(loc);
                if (!toCreateIfMissing.isEmpty()) {
                    toCreateIfMissing.remove(new Vector3i(loc.getEntryX(), loc.getEntryY(), loc.getEntryZ()));
                }
            });

            List<BoundingBox> regionBoundingBoxList = regionBoundingBoxes.get(dim.getDirectory());

            for (int i = 0; i < regionProviders.size(); i++) {
                IRegionProvider<EntryLocation3D> p = regionProviders.get(i);
                if (i == 0) {

                    p.forAllRegions((key, reg) -> {
                        Vector3i regionPos = toRegionPos(key);
                        boolean filtered = true;
                        try {
                            if(regionBoundingBoxList != null) {
                                for (BoundingBox regionBox: regionBoundingBoxList) {
                                    if (regionBox.intersects(regionPos.getX(), regionPos.getY(), regionPos.getZ())) {
                                        filtered = false;
                                    }
                                }
                            } else {
                                filtered = false;
                            }
                            if(!filtered) {
                                reg.forEachKey(addFromDisk);
                                reg.close();
                            }
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
                } else {
                    int max = i;
                    p.forAllRegions((regionKey, reg) -> {
                        Vector3i regionPos = toRegionPos(regionKey);
                        boolean filtered = true;

                        if(regionBoundingBoxList != null) {
                            for (BoundingBox regionBox: regionBoundingBoxList) {
                                if (regionBox.intersects(regionPos.getX(), regionPos.getY(), regionPos.getZ())) {
                                    filtered = false;
                                }
                            }
                        } else {
                            filtered = false;
                        }
                        if(filtered) {
                            return;
                        }

                        reg.forEachKey(key -> {
                            // cancel if any of the providers before contain this key
                            for (int j = 0; j < max; j++) {
                                EntryLocation3D superKey = regionProviders.get(j)
                                        .getExistingRegion(key)
                                        .flatMap(r -> r.hasValue(key) ? Optional.of(key) : Optional.empty())
                                        .orElse(null);
                                if (superKey != null) {
                                    return;
                                }
                            }
                            addFromDisk.accept(key);
                        });
                        reg.close();
                    });
                }
            }

            for (Vector3i vector3i : toCreateIfMissing) {
                addToMap.accept(new EntryLocation3D(vector3i.getX(), vector3i.getY(), vector3i.getZ()));
            }
        }
        return dimensions;
    }

    @Override public void loadChunks(Consumer<? super PriorityCubicChunksColumnData> consumer, Predicate<Throwable> errorHandler) throws IOException, InterruptedException {
        try {
            ChunkList list = chunkList.get();
            if (list == null) {
                return; // counting interrupted
            }
            doLoadChunks(consumer, list);
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    private void doLoadChunks(Consumer<? super PriorityCubicChunksColumnData> consumer, ChunkList list) throws IOException {
        for (Map.Entry<Dimension, List<Map.Entry<EntryLocation2D, IntArrayList>>> dimEntry : list.getChunks().entrySet()) {
            if (Thread.interrupted()) {
                return;
            }
            Dimension dim = dimEntry.getKey();
            SaveCubeColumns save = saves.get(dim);
            dimEntry.getValue().parallelStream().forEach(chunksEntry -> {
                if (Thread.interrupted()) {
                    return;
                }
                try {
                    EntryLocation2D pos2d = chunksEntry.getKey();
                    IntArrayList yCoords = chunksEntry.getValue();
                    ByteBuffer column = save.load(pos2d, true).orElse(null);
                    Map<Integer, ImmutablePair<Long, ByteBuffer>> cubes = new HashMap<>();
                    for (IntCursor yCursor : yCoords) {
                        if (Thread.interrupted()) {
                            return;
                        }
                        int y = yCursor.value;
                        EntryLocation3D location = new EntryLocation3D(pos2d.getEntryX(), y, pos2d.getEntryZ());
                        ByteBuffer cube = save.load(location, true).orElse(Utils.createAirCubeBuffer(location));
                        cubes.put(y, new ImmutablePair<>(0L, cube));
                    }
                    PriorityCubicChunksColumnData data = new PriorityCubicChunksColumnData(dim, pos2d, new ImmutablePair<>(0L, column), cubes, true);
                    consumer.accept(data);
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            });
        }
    }

    @Override public void stop() {
        loadThread.interrupt();
    }

    private static SaveCubeColumns createSave(Path path) {
        try {
            Utils.createDirectories(path);

            Path part2d = path.resolve("region2d");
            Utils.createDirectories(part2d);

            Path part3d = path.resolve("region3d");
            Utils.createDirectories(part3d);

            EntryLocation2D.Provider keyProv2d = new EntryLocation2D.Provider();
            EntryLocation3D.Provider keyProv3d = new EntryLocation3D.Provider();

            IRegionProvider<EntryLocation2D> prov2d1, prov2d2;
            IRegionProvider<EntryLocation3D> prov3d1, prov3d2;
            SaveSection2D section2d = new SaveSection2D(
                    prov2d1 = new RWLockingCachedRegionProvider<>(
                            new SimpleRegionProvider<>(keyProv2d, part2d, (keyProv, r) ->
                                    new MemoryReadRegion.Builder<EntryLocation2D>()
                                            .setDirectory(part2d)
                                            .setRegionKey(r)
                                            .setKeyProvider(keyProv2d)
                                            .setSectorSize(512)
                                            .build(),
                                    (file, key) -> Files.exists(file)
                            )
                    ),
                    prov2d2 = new RWLockingCachedRegionProvider<>(
                            new SimpleRegionProvider<>(new EntryLocation2D.Provider(), part2d,
                                    (keyProvider, regionKey) -> new ExtRegion<>(part2d, Collections.emptyList(), keyProvider, regionKey),
                                    (file, key) -> Files.exists(file.resolveSibling(key.getRegionKey().getName() + ".ext"))
                            )
                    ));
            SaveSection3D section3d = new SaveSection3D(
                    prov3d1 = new RWLockingCachedRegionProvider<>(
                            new SimpleRegionProvider<>(keyProv3d, part3d, (keyProv, r) ->
                                    new MemoryReadRegion.Builder<EntryLocation3D>()
                                            .setDirectory(part3d)
                                            .setRegionKey(r)
                                            .setKeyProvider(keyProv3d)
                                            .setSectorSize(512)
                                            .build(),
                                    (file, key) -> Files.exists(file)
                            )
                    ),
                    prov3d2 = new RWLockingCachedRegionProvider<>(
                            new SimpleRegionProvider<>(new EntryLocation3D.Provider(), part3d,
                                    (keyProvider, regionKey) -> new ExtRegion<>(part3d, Collections.emptyList(), keyProvider, regionKey),
                                    (dir, key) -> Files.exists(dir.resolveSibling(key.getRegionKey().getName() + ".ext"))
                            )
                    ));

            SaveCubeColumns saveCubeColumns = new SaveCubeColumns(section2d, section3d);
            providers2d.put(saveCubeColumns, Arrays.asList(prov2d1, prov2d2));
            providers3d.put(saveCubeColumns, Arrays.asList(prov3d1, prov3d2));
            return saveCubeColumns;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static Vector3i toRegionPos(RegionKey key) {
        String[] split = key.getName().split("\\.");
        return new Vector3i(
                Integer.parseInt(split[0]),
                Integer.parseInt(split[1]),
                Integer.parseInt(split[2])
        );
    }

    private static class ChunkList {

        private final Map<Dimension, List<Map.Entry<EntryLocation2D, IntArrayList>>> chunks;

        private ChunkList(Map<Dimension, List<Map.Entry<EntryLocation2D, IntArrayList>>> chunks) {
            this.chunks = chunks;
        }

        Map<Dimension, List<Map.Entry<EntryLocation2D, IntArrayList>>> getChunks() {
            return chunks;
        }
    }
}