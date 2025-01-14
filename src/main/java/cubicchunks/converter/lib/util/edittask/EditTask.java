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
package cubicchunks.converter.lib.util.edittask;

import com.flowpowered.nbt.CompoundMap;
import com.flowpowered.nbt.CompoundTag;
import cubicchunks.converter.lib.conf.command.EditTaskContext;
import cubicchunks.converter.lib.util.BoundingBox;
import cubicchunks.converter.lib.util.ImmutablePair;
import cubicchunks.converter.lib.util.Vector2i;
import cubicchunks.converter.lib.util.Vector3i;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public interface EditTask {
    /**
     * @param cubeTag The cube to be modified. The {@link Vector3i} is in cube coordinates, not block
     * @return The modified cube/s. The {@link CompoundMap} can be null, if so the cube will be regenerated the next time it's loaded by the game
     */
    @Nonnull List<ImmutablePair<Vector3i, ImmutablePair<Long, CompoundTag>>> actOnCube(Vector3i cubePos, EditTaskContext.EditTaskConfig config, CompoundTag cubeTag, long inCubePriority);

    /**
     * @param columnTag The column to be modified. The {@link Vector2i} is in chunk coordinates, not block
     * @return The modified column/s. The {@link CompoundMap} can be null, if so the column will be regenerated the next time it's loaded by the game
     *
     * The default behaviour is to just return the column unchanged.
     */
    @Nonnull default List<ImmutablePair<Vector2i, ImmutablePair<Long, CompoundTag>>> actOnColumn(Vector2i columnPos, EditTaskContext.EditTaskConfig config, CompoundTag columnTag, long inColumnPriority) {
        return Collections.singletonList(new ImmutablePair<>(columnPos, new ImmutablePair<>(inColumnPriority, columnTag)));
    }

    // TODO: have each task actually specify dimensions somehow
    default boolean handlesDimension(String directoryName) {
        return directoryName.isEmpty(); // overworld
    }

    /** 
     * Called ONCE per task, before any cubes are sent
     */
    default void initialise(EditTaskContext.EditTaskConfig config) {}

    /**
     * @return boolean indicating whether cubes that don't exist should be created by the reader
     */
    default boolean createSrcCubesIfMissing() {
        return false;
    }

    /**
     * @return A list of bounding boxes describing which cubes this task wants to recieve in {@link EditTask#actOnCube(Vector3i, EditTaskContext.EditTaskConfig, CompoundTag, long)}
     */
    @Nonnull List<BoundingBox> getSrcBoxes();

    /**
     * @return A list of bounding boxes describing which cubes this task will modify from {@link EditTask#actOnCube(Vector3i, EditTaskContext.EditTaskConfig, CompoundTag, long)}
     */
    @Nonnull List<BoundingBox> getDstBoxes();

    /**
     * @return A task/list of tasks that would entirely undo any operation done by this task from a backup world
     */
    @Nonnull default List<EditTask> getInverse() {
        List<EditTask> tasks = new ArrayList<>();
        getDstBoxes().forEach(box -> tasks.add(new MoveEditTask(box, new Vector3i(0, 0, 0))));
        return tasks;
    }

    /**
     * @return Whether this task requires the cube data at all. If this returns false {@link EditTask#actOnCube(Vector3i, EditTaskContext.EditTaskConfig, CompoundTag, long)}
     * will never be called. Only {@link EditTask#getSrcBoxes()}
     * This is used in tasks such as {@link KeepEditTask}, as no cube data is required.
     */
    default boolean readsCubeData() {
        return true;
    }

    default boolean isCubeSrc(int x, int y, int z) {
        for (BoundingBox box : getSrcBoxes()) {
            if (box.intersects(x, y, z))
                return true;
        }
        return false;
    }
    default boolean isCubeDst(int x, int y, int z) {
        for (BoundingBox box : getDstBoxes()) {
            if (box.intersects(x, y, z))
                return true;
        }
        return false;
    }

//    private boolean isCubeDstExclusive(int x, int y, int z) {
//        if (editTask.getOffset() != null)
//            return editTask.getSourceBox().add(editTask.getOffset()).intersects(x, y, z);
//        return false;
//    }

    static int nibbleGetAtIndex(byte[] arr, int index)
    {
        int i = index >> 1;
        return (index & 1) == 0 ? arr[i] & 0xf : arr[i] >> 4 & 0xf;
    }

    static void nibbleSetAtIndex(byte[] arr, int index, int value)
    {
        int i = index >> 1;

        if ((index & 1) == 0) {
            arr[i] = (byte)(arr[i] & 0xf0 | value & 0xf);
        }
        else {
            arr[i] = (byte)(arr[i] & 0xf | (value & 0xf) << 4);
        }
    }
}
