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

import com.flowpowered.nbt.*;
import cubicchunks.converter.lib.conf.command.EditTaskContext;
import cubicchunks.converter.lib.util.BoundingBox;
import cubicchunks.converter.lib.util.ImmutablePair;
import cubicchunks.converter.lib.util.Vector3i;
import cubicchunks.regionlib.impl.EntryLocation2D;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.lang.NotImplementedException;
import org.bukkit.Material;
import org.bukkit.material.Directional;
import org.bukkit.material.MaterialData;

public class RotateEditTask extends TranslationEditTask {
    private final Vector3i origin;
    public final int degrees;
    public RotateEditTask(BoundingBox srcBox, Vector3i origin, int degrees){
        srcBoxes.add(srcBox);
        dstBoxes.add(srcBox);
        this.origin = origin;
        if (degrees <= 0 || degrees > 360 || degrees % 90 != 0) throw new IllegalArgumentException("Degrees must be between (0,360) and divisible by 90");
        if (degrees != 90) throw new NotImplementedException("Rotator only works for 90 degree rotations"); //TODO
        this.degrees = degrees;
    }

    private int[] rotateChunkCoordinate(int x, int z){
        //Subtract origin from points
        x-=this.origin.getX();
        z-=this.origin.getZ();

        //Swap X and Z
        for(int i=0; i<this.degrees; i+=90){
            x = x ^ z ^ (z = x);
            z*=-1;
        }

        //Add origin to points
        x+=this.origin.getX();
        z+=this.origin.getZ();

        return new int[]{x, z};
    }

    private Vector3i rotateDstVector(Vector3i dstPos){
        int[] rotatedCoordinates = rotateChunkCoordinate(dstPos.getX(), dstPos.getZ());
        return new Vector3i(rotatedCoordinates[0], dstPos.getY(), rotatedCoordinates[1]);
    }

    public EntryLocation2D rotateDstEntryLocation(EntryLocation2D dstPos){
        int[] rotatedCoordinates = rotateChunkCoordinate(dstPos.getEntryX(), dstPos.getEntryZ());
        return new EntryLocation2D(rotatedCoordinates[0], rotatedCoordinates[1]);
    }

    private boolean isFloorSkull(String blockName, byte metaData){
        return blockName.equals("SKULL") && metaData == 1;
    }

    private byte handleRotationalMetadata(MaterialData blockData, String blockName){
        //int degree = degrees;
        int metaData=blockData.getData();
        int rotationalCount = this.degrees / 90;

        if (isFloorSkull(blockName, (byte) metaData)) return (byte) metaData;

        switch (blockName){
            case "SIGN_POST":
                return (byte) Math.floorMod(metaData-(4*rotationalCount), 16);
            case "WALL_SIGN":
                return (byte) Math.abs((metaData-3+(2*rotationalCount)) % 4 + 3);
            default:
                // TODO optimize this switch into 1-2 lines if possible
                int valueOffset;
                switch (metaData){
                    case 5:
                        valueOffset=-3;
                        break;
                    case 4:
                        valueOffset=-1;
                        break;
                    default:
                        valueOffset=2;
                }
                return (byte) (Math.floorMod((metaData-2)+(valueOffset*rotationalCount), 6)+2);
        }
    }

    private int rotateMetadata(int blockId, int metaData){
        if (blockId < 0){
            blockId+=256;
        }
        Material block = Material.getMaterial(blockId);
        MaterialData blockData = block.getNewData((byte) metaData);
        if (!(blockData instanceof Directional)){
            return metaData;
        }
        return this.handleRotationalMetadata(blockData, block.name());
    }

    private void handleSkullTileEntities(CompoundMap tileEntity){
        int rot = (int) ((Byte) tileEntity.get("Rot").getValue());
        rot = (byte) Math.floorMod(rot-4,16); //TODO this only works for 90 degrees
        tileEntity.put(new IntTag("Rot", rot));
    }

    private void rotateTileEntities(CompoundMap level){
        for (int i=0; i< ((List<?>) (level).get("TileEntities").getValue()).size(); i++){
            CompoundMap tileEntity = ((CompoundTag) ((List<?>) (level).get("TileEntities").getValue()).get(i)).getValue();
            int x = ((Integer) tileEntity.get("z").getValue()); //TODO this only works for 90 degrees
            int z = ((((Integer) tileEntity.get("x").getValue())-8)*-1)+7; //TODO this only works for 90 degrees
            tileEntity.put(new IntTag("x", x));
            tileEntity.put(new IntTag("z", z));

            String blockName = ((String) tileEntity.get("id").getValue());
            if (blockName.equals("minecraft:skull")) handleSkullTileEntities(tileEntity);
        }
    }

    private void handleItemFrames(double x, double z, CompoundMap entity){
        entity.put(new IntTag("TileX", (int) Math.floor(x)));
        entity.put(new IntTag("TileZ", (int) Math.ceil(z)));

        int facing = (int) ((Byte) entity.get("Facing").getValue());
        entity.put(new ByteTag("Facing", ((byte) ((facing+2) %4)))); //TODO this only works for 90 degrees
    }

    private void rotateEntities(CompoundMap level){
        for (int i=0; i< ((List<?>) (level).get("Entities").getValue()).size(); i++){
            CompoundMap entity = ((CompoundTag) ((List<?>) (level).get("Entities").getValue()).get(i)).getValue();
            List<DoubleTag> pos = (List<DoubleTag>) entity.get("Pos").getValue();
            double x = (pos.get(2).getValue()); //TODO this only works for 90 degrees
            double z = (((pos.get(0).getValue())-8)*-1)+7; //TODO this only works for 90 degrees
            double y = (pos.get(1).getValue());
            List<DoubleTag> newPos = Arrays.asList(new DoubleTag("", x), new DoubleTag("", y), new DoubleTag("", z));
            entity.put(new ListTag<>("Pos", DoubleTag.class, newPos));

            String blockName = ((String) entity.get("id").getValue());
            if (blockName.equals("minecraft:item_frame")) handleItemFrames(x, z, entity);
        }
    }

    private void rotateBlocks(CompoundMap sectionDetails){
        final byte[] blocks = (byte[]) sectionDetails.get("Blocks").getValue();
        final byte[] meta = (byte[]) sectionDetails.get("Data").getValue();

        byte[] newBlocks = new byte[blocks.length];
        byte[] newMeta = new byte[meta.length];

        int sideLen=16;
        int squareLen=sideLen*sideLen;

        for (int y = 0; y < blocks.length / squareLen; y++) {
            for (int r = 0; r < sideLen; r++) {
                for (int c = 0; c < sideLen; c++) {
                    int newIndex;
                    switch (this.degrees){
                        case 90:
                            newIndex = (((sideLen - 1) - c)*sideLen) + r+ (y * squareLen);
                            break;
                        case 180:
                            throw new NotImplementedException("180 degree rotations not implemented yet."); //TODO implement
                        case 270:
                            newIndex = ((c*sideLen)+sideLen-1-r)+(y*squareLen); //TODO verify this works
                            break;
                        default:
                            throw new IllegalArgumentException("Invalid degrees");
                    }
                    int oldIndex = ((r * sideLen) + c) + (y * squareLen);
                    newBlocks[newIndex] = blocks[oldIndex];
                    int metaData = rotateMetadata(newBlocks[newIndex], EditTask.nibbleGetAtIndex(meta, oldIndex));
                    EditTask.nibbleSetAtIndex(newMeta, newIndex, metaData);
                }
            }
        }
        System.arraycopy(newBlocks, 0, blocks, 0, blocks.length);
        System.arraycopy(newMeta, 0, meta, 0, meta.length);
    }


    @Nonnull public List<ImmutablePair<Vector3i, ImmutablePair<Long, CompoundTag>>> actOnCube(Vector3i cubePos, EditTaskContext.EditTaskConfig config, CompoundTag cubeTag, long inCubePriority) {
        List<ImmutablePair<Vector3i, ImmutablePair<Long, CompoundTag>>> outCubes = new ArrayList<>();

        Vector3i dstPos = this.rotateDstVector(cubePos);

        // adjusting new cube data to be valid
        CompoundMap level = (CompoundMap) cubeTag.getValue().get("Level").getValue();

        CompoundMap sectionDetails;
        try {
            sectionDetails = ((CompoundTag) ((List<?>) (level).get("Sections").getValue()).get(0)).getValue(); //POSSIBLE ARRAY OUT OF BOUNDS EXCEPTION ON A MALFORMED CUBE//POSSIBLE ARRAY OUT OF BOUNDS EXCEPTION ON A MALFORMED CUBE
        }
        catch (NullPointerException | ArrayIndexOutOfBoundsException e) {
            LOGGER.warning("Malformed cube at position (" + cubePos.getX() + ", " + cubePos.getY() + ", " + cubePos.getZ() + "), skipping!");
            return outCubes;
        }

        level.put(new IntTag("x", dstPos.getX()));
        level.put(new IntTag("y", dstPos.getY()));
        level.put(new IntTag("z", dstPos.getZ()));

        if (config.shouldRelightDst()) {
            this.markCubeForLightUpdates(level);
        }
        this.markCubePopulated(level);

        this.rotateTileEntities(level);
        this.rotateEntities(level);

        this.rotateBlocks(sectionDetails);

        outCubes.add(new ImmutablePair<>(dstPos, new ImmutablePair<>(inCubePriority + 1, cubeTag)));
        return outCubes;
    }

}


