/*
 *  This file is part of CubicChunksConverter, licensed under the MIT License (MIT).
 *
 *  Copyright (c) 2017 contributors
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
package cubicchunks.converter.lib.convert.cc2ccrelocating;

import com.flowpowered.nbt.*;
import cubicchunks.converter.lib.conf.ConverterConfig;
import cubicchunks.converter.lib.conf.command.EditTaskCommands;
import cubicchunks.converter.lib.conf.command.EditTaskContext;
import cubicchunks.converter.lib.convert.ChunkDataConverter;
import cubicchunks.converter.lib.convert.data.CubicChunksColumnData;
import cubicchunks.converter.lib.util.*;
import cubicchunks.converter.lib.util.edittask.EditTask;
import cubicchunks.regionlib.impl.EntryLocation2D;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static cubicchunks.converter.lib.util.Utils.readCompressedCC;
import static cubicchunks.converter.lib.util.Utils.writeCompressed;

public class CC2CCRelocatingDataConverter implements ChunkDataConverter<CubicChunksColumnData, CubicChunksColumnData> {

    private final List<EditTask> relocateTasks;

    private static final Logger LOGGER = Logger.getLogger(CC2CCRelocatingDataConverter.class.getSimpleName());

    @SuppressWarnings("unchecked")
    public CC2CCRelocatingDataConverter(ConverterConfig config) {
        relocateTasks = (List<EditTask>) config.getValue("relocations");
    }

    public static ConverterConfig loadConfig(Consumer<Throwable> throwableConsumer) {
        ConverterConfig conf = new ConverterConfig(new HashMap<>());
        try {
            conf.set("relocations", loadDataFromFile("relocatingConfig.txt"));
        } catch (IOException | RuntimeException e) {
            throwableConsumer.accept(e);
            return null;
        }
        return conf;
    }

    private static List<EditTask> loadDataFromFile(String filename) throws IOException {
        List<String> lines = Files.readAllLines(Paths.get(filename));

        EditTaskContext context = new EditTaskContext();
        for(String line : lines) {
            line = line.trim();
            if(line.isEmpty() || line.startsWith("//") || line.startsWith("#")) continue;

            EditTaskCommands.handleCommand(context, line);
        }

        return context.getTasks();
    }

    @Override public Set<CubicChunksColumnData> convert(CubicChunksColumnData input) {
        Map<Integer, ByteBuffer> inCubes = input.getCubeData();
        Map<Integer, ByteBuffer> cubes = new HashMap<>();

        //Split out cubes that are only in a keep tasked bounding box
        Map<Integer, ByteBuffer> noReadCubes = new HashMap<>();
        EntryLocation2D inPosition = input.getPosition();
        for(Map.Entry<Integer, ByteBuffer> entry : inCubes.entrySet()) {
            boolean anyBoxNeedsData = false;
            boolean intersectsAnyBoxes = false;

            for(EditTask task : relocateTasks) {
                List<BoundingBox> srcBoxes = task.getSrcBoxes();
                for (BoundingBox srcBox : srcBoxes) {
                    if(srcBox.intersects(inPosition.getEntryX(), entry.getKey(), inPosition.getEntryZ())) {
                        intersectsAnyBoxes = true;
                        if(task.readsCubeData()) {
                            anyBoxNeedsData = true;
                            break;
                        }
                    }
                }

                List<BoundingBox> dstBoxes = task.getDstBoxes();
                for (BoundingBox dstBox : dstBoxes) {
                    if(dstBox.intersects(inPosition.getEntryX(), entry.getKey(), inPosition.getEntryZ())) {
                        intersectsAnyBoxes = true;
                        if(task.readsCubeData()) {
                            anyBoxNeedsData = true;
                            break;
                        }
                    }
                }

            }
            if(intersectsAnyBoxes) {
                if (anyBoxNeedsData)
                    cubes.put(entry.getKey(), entry.getValue());
                else
                    noReadCubes.put(entry.getKey(), entry.getValue());
            } else
                noReadCubes.put(entry.getKey(), entry.getValue());
        }

        Map<Integer, CompoundTag> inCubeData = new HashMap<>();
        cubes.forEach((key, value) -> {
            try {
                inCubeData.put(key, readCompressedCC(new ByteArrayInputStream(value.array())));
            } catch (IOException e) {
                e.printStackTrace();
            }
        });

        try {
            Map<Vector2i, Map<Integer, CompoundTag>> outCubeData = relocateCubeData(inCubeData);

            Set<CubicChunksColumnData> columnData = new HashSet<>();
            for (Map.Entry<Vector2i, Map<Integer, CompoundTag>> entry : outCubeData.entrySet()) {
                Vector2i key = entry.getKey();
                ByteBuffer column = key.getX() != inPosition.getEntryX() || key.getY() != inPosition.getEntryZ() ? null : input.getColumnData();

                EntryLocation2D location = new EntryLocation2D(key.getX(), key.getY());
                columnData.add(new CubicChunksColumnData(input.getDimension(), location, column, compressCubeData(entry.getValue())));
            }
            if (!noReadCubes.isEmpty()) {
                CubicChunksColumnData currentColumnData = columnData.stream()
                        .filter(x -> x.getPosition().equals(inPosition))
                        .findAny()
                        .orElseGet(() -> new CubicChunksColumnData(input.getDimension(), inPosition, input.getColumnData(), new HashMap<>()));
                currentColumnData.getCubeData().putAll(noReadCubes);
                columnData.add(currentColumnData);
            }
            return columnData;

        } catch (IOException e) {
            throw new Error("Compressing cube data failed!", e);
        }
    }

    Map<Integer, ByteBuffer> compressCubeData(Map<Integer, CompoundTag> cubeData) throws IOException {
        Map<Integer, ByteBuffer> compressedData = new HashMap<>();
        for(Map.Entry<Integer, CompoundTag> entry : cubeData.entrySet()) {
            compressedData.put(entry.getKey(), writeCompressed(entry.getValue(), false));
        }
        return compressedData;
    }

    Map<Vector2i, Map<Integer, CompoundTag>> relocateCubeData(Map<Integer, CompoundTag> cubeDataOld) throws IOException {
        Map<Vector2i, Map<Integer, CompoundTag>> tagMap = new HashMap<>();

        for(Map.Entry<Integer, CompoundTag> entry : cubeDataOld.entrySet()) {
            CompoundMap level = (CompoundMap)entry.getValue().getValue().get("Level").getValue();

            int cubeX = (Integer) level.get("x").getValue();
            int cubeY = (Integer) level.get("y").getValue();
            int cubeZ = (Integer) level.get("z").getValue();

            //TODO: figure out why this is needed
//            if(!isCubeSrc(this.relocateTasks, cubeX, cubeY, cubeZ) && !isCubeDst(this.relocateTasks, cubeX, cubeY, cubeZ)) {
//                tagMap.computeIfAbsent(new Vector2i(cubeX, cubeZ), key->new HashMap<>()).put(cubeY, entry.getValue());
//                continue;
//            }

            for (EditTask task : this.relocateTasks) {
                if(!task.readsCubeData()) {
                    continue;
                }

                boolean cubeIsSrc = false;
                for (BoundingBox sourceBox : task.getSrcBoxes()) {
                    if(sourceBox.intersects(cubeX, cubeY, cubeZ)) {
                        cubeIsSrc = true;
                        break;
                    }
                }
                if(!cubeIsSrc)
                    continue;

                List<ImmutablePair<Vector3i, CompoundTag>> outputCubes = task.actOnCube(new ImmutablePair<>(new Vector3i(cubeX, cubeY, cubeZ), entry.getValue()));

                outputCubes.forEach(pair -> {
                    Vector3i cubePos = pair.getKey();
                    CompoundTag tag = pair.getValue();
                    if(tag == null)
                        tagMap.computeIfAbsent(new Vector2i(cubePos.getX(), cubePos.getZ()), pos -> new HashMap<>()).remove(cubePos.getY());
                    else
                        tagMap.computeIfAbsent(new Vector2i(cubePos.getX(), cubePos.getZ()), pos -> new HashMap<>()).put(cubePos.getY(), tag);
                });
            }
        }

        return tagMap;
    }

//    private static boolean isCubeSrc(List<EditTask> tasks, int x, int y, int z) {
//        for (EditTask editTask : tasks) {
//            if (isCubeSrc(editTask, x, y, z))
//                return true;
//        }
//        return false;
//    }
//    private static boolean isCubeDst(List<EditTask> tasks, int x, int y, int z) {
//        for (EditTask editTask : tasks) {
//            if(isCubeDst(editTask, x, y, z))
//                return true;
//        }
//        return false;
//    }
//    private static boolean isCubeSrc(EditTask editTask, int x, int y, int z) {
//        return editTask.getSourceBox().intersects(x, y, z);
//    }
//    private static boolean isCubeDst(EditTask editTask, int x, int y, int z) {
//        if (editTask.getOffset() != null) {
//            if (editTask.getSourceBox().add(editTask.getOffset()).intersects(x, y, z))
//                return true;
//        }
//
//        if(editTask.getType() == EditTask.Type.CUT || editTask.getType() == EditTask.Type.REMOVE) {
//            return editTask.getSourceBox().intersects(x, y, z);
//        }
//        return false;
//    }
//    private static boolean isCubeDstExclusive(EditTask editTask, int x, int y, int z) {
//        if (editTask.getOffset() != null)
//            return editTask.getSourceBox().add(editTask.getOffset()).intersects(x, y, z);
//        return false;
//    }
//
//    public static boolean isRegionInCopyOrPasteLoc(List<EditTask> tasks, int x, int y, int z) {
//        for(EditTask task : tasks) {
//            if (task.getSourceBox().intersects(x, y, z)) {
//                if (task.getOffset() == null) continue;
//                if (task.getSourceBox().intersects(
//                        x - task.getOffset().getX(),
//                        y - task.getOffset().getY(),
//                        z - task.getOffset().getZ())) {
//                    return true;
//                }
//            }
//        }
//        return false;
//    }
}
