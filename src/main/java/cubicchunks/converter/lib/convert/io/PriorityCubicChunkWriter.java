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

import cubicchunks.converter.lib.Dimension;
import cubicchunks.converter.lib.convert.ChunkDataWriter;
import cubicchunks.converter.lib.convert.data.PriorityCubicChunksColumnData;
import cubicchunks.converter.lib.util.*;
import cubicchunks.regionlib.impl.EntryLocation2D;
import cubicchunks.regionlib.impl.EntryLocation3D;
import cubicchunks.regionlib.impl.SaveCubeColumns;
import cubicchunks.regionlib.impl.save.SaveSection2D;
import cubicchunks.regionlib.impl.save.SaveSection3D;
import cubicchunks.regionlib.lib.ExtRegion;
import cubicchunks.regionlib.lib.provider.SimpleRegionProvider;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PriorityCubicChunkWriter implements ChunkDataWriter<PriorityCubicChunksColumnData> {

    private final Path dstPath;
    private final Map<Dimension, SaveCubeColumns> saves = new ConcurrentHashMap<>();

    public PriorityCubicChunkWriter(Path dstPath) {
        this.dstPath = dstPath;
    }

    private Map<Vector2i, Long> columnPriorities = new HashMap<>();
    private Map<Vector3i, Long> cubePriorities = new HashMap<>();

    @Override public void accept(PriorityCubicChunksColumnData data) throws IOException {
        SaveCubeColumns save = saves.computeIfAbsent(data.getDimension(), dim -> {
            try {
                Path path = dstPath.resolve(dim.getDirectory());

                Utils.createDirectories(path);

                Path part2d = path.resolve("region2d");
                Utils.createDirectories(part2d);

                Path part3d = path.resolve("region3d");
                Utils.createDirectories(part3d);

                SaveSection2D section2d = new SaveSection2D(
                        new RWLockingCachedRegionProvider<>(
                                new SimpleRegionProvider<>(new EntryLocation2D.Provider(), part2d, (keyProv, r) ->
                                        new MemoryWriteRegion.Builder<EntryLocation2D>()
                                                .setDirectory(part2d)
                                                .setRegionKey(r)
                                                .setKeyProvider(keyProv)
                                                .setSectorSize(512)
                                                .build(),
                                        (file, key) -> Files.exists(file)
                                )
                        ),
                        new RWLockingCachedRegionProvider<>(
                                new SimpleRegionProvider<>(new EntryLocation2D.Provider(), part2d,
                                        (keyProvider, regionKey) -> new ExtRegion<>(part2d, Collections.emptyList(), keyProvider, regionKey),
                                        (dir, key) -> Files.exists(dir.resolveSibling(key.getRegionKey().getName() + ".ext"))
                                )
                        ));
                SaveSection3D section3d = new SaveSection3D(
                        new RWLockingCachedRegionProvider<>(
                                new SimpleRegionProvider<>(new EntryLocation3D.Provider(), part3d, (keyProv, r) ->
                                        new MemoryWriteRegion.Builder<EntryLocation3D>()
                                                .setDirectory(part3d)
                                                .setRegionKey(r)
                                                .setKeyProvider(keyProv)
                                                .setSectorSize(512)
                                                .build(),
                                        (file, key) -> Files.exists(file)
                                )
                        ),
                        new RWLockingCachedRegionProvider<>(
                                new SimpleRegionProvider<>(new EntryLocation3D.Provider(), part3d,
                                        (keyProvider, regionKey) -> new ExtRegion<>(part3d, Collections.emptyList(), keyProvider, regionKey),
                                        (dir, key) -> Files.exists(dir.resolveSibling(key.getRegionKey().getName() + ".ext"))
                                )
                        ));

                return new SaveCubeColumns(section2d, section3d);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        EntryLocation2D pos = data.getPosition();
        ImmutablePair<Long, ByteBuffer> columnData = data.getColumnData();
        if (columnData != null) {
            Vector2i columnPos = new Vector2i(pos.getEntryX(), pos.getEntryZ());
            Long priority = columnPriorities.get(columnPos);
            if(priority == null || columnData.getFirst() > priority) {
                columnPriorities.put(columnPos, columnData.getKey());
                save.save2d(new EntryLocation2D(pos.getEntryX(), pos.getEntryZ()), columnData.getValue());
            }
        }
        EntryLocation2D entryPos = data.getPosition();
        for (Map.Entry<Integer, ImmutablePair<Long, ByteBuffer>> entry : data.getCubeData().entrySet()) {
            Vector3i cubePos = new Vector3i(entryPos.getEntryX(), entry.getKey(), entryPos.getEntryZ());
            Long priority = cubePriorities.get(cubePos);
            if(priority == null || entry.getValue().getFirst() > priority) {
                cubePriorities.put(cubePos, entry.getValue().getKey());
                save.save3d(new EntryLocation3D(pos.getEntryX(), entry.getKey(), pos.getEntryZ()), entry.getValue().getValue());
            }
        }
    }

    @Override public void discardData() throws IOException {
        Utils.rm(dstPath);
    }

    @Override public void close() throws Exception {
        boolean exception = false;
        for (SaveCubeColumns save : saves.values()) {
            try {
                save.close();
            } catch (IOException e) {
                e.printStackTrace();
                exception = true;
            }
        }
        if (exception) {
            throw new IOException();
        }
    }
}