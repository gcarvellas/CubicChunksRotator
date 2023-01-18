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
import java.util.*;

import org.apache.commons.lang.NotImplementedException;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.material.Bed;
import org.bukkit.material.Directional;
import org.bukkit.material.MaterialData;

public class RotateEditTask extends TranslationEditTask {
    private final Vector3i origin;
    private Set<Integer> currentWallSkulls;

    private final Set<String> PILLARS = new HashSet<>(Arrays.asList("LOG", "LOG_2", "QUARTZ_BLOCK", "PURPUR_PILLAR", "HAY_BLOCK"));
    public final int degrees;
    public RotateEditTask(BoundingBox srcBox, Vector3i origin, int degrees){
        srcBoxes.add(srcBox);
        dstBoxes.add(srcBox);
        this.origin = origin;
        if (degrees <= 0 || degrees > 360 || degrees % 90 != 0){
            throw new IllegalArgumentException("Degrees must be between (0,360) and divisible by 90");
        }
        if (degrees != 90){
            throw new NotImplementedException("Rotator only works for 90 degree rotations"); //TODO
        }
        this.degrees = degrees;
        this.currentWallSkulls = new HashSet<>();
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

    private boolean isWallSkull(String blockName, byte metaData) { return blockName.equals("SKULL") && metaData != 1; }

    private int handlePoweredRails(int metaData){
        switch (metaData){
            case 0: //0 - 1 straights
                return 1;
            case 1:
                return 0;
            case 2: //2 - 5 curves
            case 3:
                return (metaData+12) % 10;
            case 4:
                return 3;
            case 5:
                return 2;
            case 9:
                return 8;
            case 8:
                return 9;
            case 10:
                return 12;
            case 11:
                return 13;
            case 12:
                return 11;
            case 13:
                return 10;
            default:
                LOGGER.warning("Invalid powered rail metadata: " + metaData);
                return metaData;
        }
    }

    private int handleRails(int metaData){
        switch(metaData){
            case 0: //0 - 1 straights
                return 1;
            case 1:
                return 0;
            case 2: //2 - 5 curves
            case 3:
                return (metaData+12) % 10;
            case 4:
                return 3;
            case 5:
                return 2;
            case 6: //6 - 9elevates
                return 9;
            case 7:
            case 8:
            case 9:
                return metaData-1;
            default:
                LOGGER.warning("Invalid rail metadata " + metaData);
                return metaData;
        }

    }

    private int handleDefaultCaseNoBukkit(int metaData, int rotationalCount){
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
        return (Math.floorMod((metaData-2)+(valueOffset*rotationalCount), 6)+2);
    }

    private byte handleDefaultCase(MaterialData blockData) {
        Directional directionalBlockData = (Directional) blockData;
        BlockFace facing = ((Directional) blockData).getFacing();
        BlockFace newFacing = null;
        for (int i = 0; i < this.degrees; i += 90) {
            switch (facing) {
                case NORTH:
                    newFacing = BlockFace.EAST;
                    break;
                case EAST:
                    newFacing = BlockFace.SOUTH;
                    break;
                case SOUTH:
                    newFacing = BlockFace.WEST;
                    break;
                case WEST:
                    newFacing = BlockFace.NORTH;
                    break;
                case NORTH_EAST:
                    newFacing = BlockFace.SOUTH_EAST;
                    break;
                case SOUTH_EAST:
                    newFacing = BlockFace.SOUTH_WEST;
                    break;
                case SOUTH_WEST:
                    newFacing = BlockFace.NORTH_WEST;
                    break;
                case NORTH_WEST:
                    newFacing = BlockFace.NORTH_EAST;
                    break;
                case UP:
                case DOWN:
                    return blockData.getData();
                default:
                    throw new IllegalArgumentException("Unknown facing value: " + facing);

            }
            directionalBlockData.setFacingDirection(newFacing);
        }
        return ((MaterialData) directionalBlockData).getData();
    }

    private int handleBeds(int metaData, int rotationalCount, boolean isHeadOfBed){
        if (isHeadOfBed){
            return Math.floorMod(metaData -8 - rotationalCount, 4) + 8;
        }
        return Math.floorMod(metaData - rotationalCount,4);
    }

    private int handleJackOLanterns(int metaData, int rotationalCount){
        return Math.floorMod(metaData - rotationalCount, 4);
    }

    private int handleQuartzPillar(int metaData, int rotationalCount){
        if (metaData != 2){
            return Math.floorMod(metaData - 3 + rotationalCount, 2) + 3;
        }
        return metaData;
    }

    private int handleLogs(int metaData, int rotationalCount) {
        if (metaData != 0){
            for(int i=0; i< rotationalCount; i++){
                if (metaData == 4 || metaData == 8) {
                    metaData = metaData == 4 ? 8 : 4;
                }
                else if (metaData == 5 || metaData == 9) {
                    metaData = metaData == 5 ? 9 : 5;
                }
                else if (metaData == 10 || metaData == 6){
                    metaData = metaData == 10 ? 6 : 10;
                }
                else if (metaData == 11 || metaData == 7) {
                    metaData = metaData == 11 ? 7 : 11;
                }
            }
        }
        return metaData;
    }
    private int handleGlazedTerracotta(int metaData, int rotationalCount){
        return Math.floorMod(metaData - rotationalCount, 4);
    }

    private int handleRedstoneObjects(int metaData){
        switch(metaData){
            case 2:
                return 4;
            case 3:
                return 5;
            case 4:
                return 3;
            case 5:
                return 2;
        }
        return metaData;
    }

    private int handleButtons(int metaData){
        switch(metaData){
            case 2:
                return 3;
            case 3:
                return 1;
            case 4:
                return 2;
            case 1:
                return 4;
        }
        return metaData;
    }

    int handleRepeatersHooks(int metaData){
        switch(metaData){
            case 2:
                return 1;
            case 1:
                return 0;
            case 0:
                return 3;
            case 3:
                return 2;
        }
        return metaData;
    }

    private int handlePurpurPillars(int metaData, int rotationalCount){
        if (metaData != 0){
            for(int i=0; i< rotationalCount; i++){
                metaData = metaData == 4 ? 8 : 4;
            }
        }
        return metaData;
    }

    private int rotatePillars(String blockName, int metaData, int rotationalCount){
        if (blockName.equals("QUARTZ_BLOCK")){
            return handleQuartzPillar(metaData, rotationalCount);
        }
        if (blockName.contains("LOG") || blockName.equals("HAY_BLOCK")){
            return handleLogs(metaData, rotationalCount);
        }
        if (blockName.equals("PURPUR_PILLAR")){
            return handlePurpurPillars(metaData, rotationalCount);
        }
        return metaData;
    }

    private int handleTrapdoor(int metaData){
        switch (metaData){
            case 0:
                return 2;
            case 1:
                return 3;
            case 2:
                return 1;
            case 3:
                return 0;
            case 4:
                return 6;
            case 5:
                return 7;
            case 6:
                return 5;
            case 7:
                return 4;
            case 8:
                return 10;
            case 9:
                return 11;
            case 10:
                return 9;
            case 11:
                return 8;
            case 12:
                return 14;
            case 13:
                return 15;
            case 14:
                return 13;
            case 15:
                return 12;
        }
        return metaData;
    }
    private int handleDoors(int metaData){
        switch(metaData){
            case 0:
                return 3;
            case 1:
                return 0;
            case 2:
                return 1;
            case 3:
                return 2;
            case 8:
                return 9;
            case 9:
                return 8;
        }
        return metaData;
    }

    private int handleAnvils(int metaData, int rotationalCount){
        if (metaData >= 8){ //Very damaged anvil
            return Math.floorMod(metaData - 8 - rotationalCount, 4) + 8;
        }
        if (metaData >= 4){ // Slightly damaged anvil
            return Math.floorMod(metaData - 4 - rotationalCount, 4) + 4;
        }
        return Math.floorMod(metaData - rotationalCount, 4);
    }

    private int handleVine(int metaData){  //TODO this only works for 90 degrees
        switch (metaData){
            case 1: //south
                return 8;
            case 2: // west
                return 1;
            case 3: // south + west
                return 9;
            case 4: // north
                return 2;
            case 5: // north + south
                return 10;
            case 6: // north + west
                return 3;
            case 7: //north + west + south
                return 11;
            case 8: //east
                return 4;
            case 9: // south + east
                return 12;
            case 10:
                return 5;
            case 11: // south + east + west
                return 13;
            case 12:
                return 6;
            case 13: // north + south + east
                return 14;
            case 14: // east + west + north
                return 7;
            case 15:
                return 15;
            default:
                throw new IllegalArgumentException("Invalid Vine Metadata");
        }
    }

    private int handleComparitors(int metaData){
        switch(metaData){
            case 0:
                return 3;
            case 4:
                return 7;
            case 8:
                return 11;
            case 12:
                return 15;
            case 1:
            case 2:
            case 3:
            case 5:
            case 6:
            case 7:
            case 9:
            case 10:
            case 11:
            case 13:
            case 14:
            case 15:
                return metaData-1;
        }
        return metaData;
    }
    private int handlePistons(int metaData){
        switch(metaData){
            case 2:
                return 4;
            case 4:
                return 3;
            case 3:
                return 5;
            case 5:
                return 2;
            case 10:
                return 12;
            case 12:
                return 11;
            case 11:
                return 13;
            case 13:
                return 10;
        }
        return metaData;
    }

    private int handleTorches(int metaData){
        switch(metaData){
            case 4:
                return 2;
            case 2:
                return 3;
            case 3:
                return 1;
            case 1:
                return 4;
        }
        return metaData;
    }

    private int handleLevers(int metaData){
        switch(metaData){
            case 4:
                return 2;
            case 2:
                return 3;
            case 3:
                return 1;
            case 1:
                return 4;
            case 12:
                return 10;
            case 10:
                return 11;
            case 11:
                return 9;
            case 9:
                return 12;
        }
        return metaData;
    }

    private int handleWallSigns(int metaData){
        switch (metaData){
            case 2:
                return 4;
            case 3:
                return 5;
            case 4:
                return 3;
            case 5:
                return 2;
        }
        return metaData;
    }

    private int handleEndRod(int metaData){
        switch (metaData) {
            case 0:
                return 0;
            case 1:
                return 1;
            case 2:
                return 4;
            case 3:
                return 5;
            case 4:
                return 3;
            case 5:
                return 2;
        }
        return metaData;
    }
    private byte handleRotationalMetadata(MaterialData blockData, String blockName, int blocksNbtIndex){
        //int degree = degrees;
        int metaData=blockData.getData();
        int rotationalCount = this.degrees / 90;

        if (isFloorSkull(blockName, (byte) metaData)){
            return (byte) metaData;
        }

        if (isWallSkull(blockName, (byte) metaData)){
            this.currentWallSkulls.add(blocksNbtIndex);
        }
        if (blockName.contains("GLAZED_TERRACOTTA")) {
            return (byte) handleGlazedTerracotta(metaData, rotationalCount);
        }

        if (blockName.contains("ANVIL")) {
            return (byte) handleAnvils(metaData, rotationalCount);
        }

        if (blockName.contains("VINE")){
            return (byte) handleVine(metaData);
        }

        if (blockName.contains("TRAP_DOOR") || blockName.contains("IRON_TRAPDOOR")){
            return (byte) handleTrapdoor(metaData);
        }
        if (blockName.contains("_DOOR")){
            return (byte) handleDoors(metaData);
        }

        if (blockName.contains("PISTON")){
            return (byte) handlePistons(metaData);
        }

        if (blockName.contains("END_ROD")){
            return (byte) handleEndRod(metaData);
        }

        switch (blockName){
            case "LEVER":
                return (byte) handleLevers(metaData);
            case "REDSTONE_TORCH_OFF":
            case "REDSTONE_TORCH_ON":
            case "TORCH":
                return (byte) handleTorches(metaData);
            case "POWERED_RAIL":
            case "ACTIVATOR_RAIL":
                return (byte) handlePoweredRails(metaData);
            case "RAILS":
            case "DETECTOR_RAIL":
                return (byte) handleRails(metaData);
            case "SIGN_POST":
            case "STANDING_BANNER":
                return (byte) ((metaData-(4*rotationalCount)) % 16);
            case "BED_BLOCK":
                return (byte) handleBeds(metaData, rotationalCount, ((Bed) blockData).isHeadOfBed());
            case "JACK_O_LANTERN":
                return (byte) handleJackOLanterns(metaData, rotationalCount);
            case "OBSERVER":
            case "DROPPER":
            case "HOPPER":
            case "DISPENSER":
                return (byte) handleRedstoneObjects(metaData);
            case "STONE_BUTTON":
            case "WOOD_BUTTON":
                return (byte) handleButtons(metaData);
            case "DIODE_BLOCK_OFF":
            case "DIODE_BLOCK_ON":
            case "TRIPWIRE_HOOK":
                return (byte) handleRepeatersHooks(metaData);
            case "WALL_SIGN":
            case "WALL_BANNER":
                return (byte) handleWallSigns(metaData);
            case "ENDER_CHEST":
            case "CHEST":
            case "TRAPPED_CHEST":
            case "FURNACE":
            case "SKULL":
                return (byte) handleDefaultCaseNoBukkit(metaData, rotationalCount);
            case "REDSTONE_COMPARATOR_OFF":
                return (byte) handleComparitors(metaData);

            default:
                return handleDefaultCase(blockData);
        }
    }

    private int rotateMetadata(int blockId, int metaData, int blocksNbtIndex){
        if (blockId < 0){
            blockId+=256;
        }
        Material block = Material.getMaterial(blockId);
        MaterialData blockData = block.getNewData((byte) metaData);
        if (isPillar(block.name())){
            return this.rotatePillars(block.name(), metaData, this.degrees/90);
        }
        if (block.name().contains("END_ROD") || block.name().contains("ANVIL") || block.name().contains("VINE") || block.name().contains("RAIL") || block.name().contains("TERRACOTTA") || blockData instanceof Directional){ //Some blocks are not a part of Directional but do have rotational data
            return this.handleRotationalMetadata(blockData, block.name(), blocksNbtIndex);
        }
        return metaData;
    }

    private boolean isPillar(String blockName){
        return PILLARS.contains(blockName);
    }

    private void handleSkullTileEntities(CompoundMap tileEntity){
        int rot = (int) ((Byte) tileEntity.get("Rot").getValue());
        rot = (byte) Math.floorMod(rot-4,16); //TODO this only works for 90 degrees
        tileEntity.put(new IntTag("Rot", rot));
    }

    private boolean isNotWallSkull(int x, int y, int z){
        int blocksNbtIndex = (256*Math.floorMod(y, 16))+(16*Math.floorMod(z, 16))+Math.floorMod(x, 16);
        return !this.currentWallSkulls.contains(blocksNbtIndex);
    }
    private void rotateTileEntities(CompoundMap level){
        for (int i=0; i< ((List<?>) (level).get("TileEntities").getValue()).size(); i++){
            CompoundMap tileEntity = ((CompoundTag) ((List<?>) (level).get("TileEntities").getValue()).get(i)).getValue();
            int x = ((Integer) tileEntity.get("z").getValue()); //TODO this only works for 90 degrees
            int z = ((((Integer) tileEntity.get("x").getValue())-8)*-1)+7; //TODO this only works for 90 degrees
            int y = (Integer) tileEntity.get("y").getValue();
            tileEntity.put(new IntTag("x", x));
            tileEntity.put(new IntTag("z", z));

            String blockName = ((String) tileEntity.get("id").getValue());
            if (blockName.equals("minecraft:skull") && isNotWallSkull(x, y, z))  {
                handleSkullTileEntities(tileEntity);
            }
        }
    }
    private void handleItemFrames(double x, double z, CompoundMap entity){
        entity.put(new IntTag("TileX", (int) Math.floor(x)));
        entity.put(new IntTag("TileZ", (int) Math.floor(z)));

        int facing = (int) ((Byte) entity.get("Facing").getValue());
        entity.put(new ByteTag("Facing", (byte) ((facing +3) % 4)));

        }

    private void handleArmorStand(CompoundMap entity){
        int rotation = (int) ((float) (((FloatTag) ((List<?>) (entity).get("Rotation").getValue()).get(0)).getValue()));
        switch (rotation){ //TODO this only works for 90 degrees
            case -135:
                rotation = 135;
                break;
            case -180:
                rotation = 90;
                break;
            default:
                rotation -= 90;
        }

        List<FloatTag> newRotation = Arrays.asList(new FloatTag("", rotation), new FloatTag("", 0));
        entity.put("Rotation", new ListTag<>("Rotation", FloatTag.class, newRotation));
    }

    private void handlePainting(CompoundMap entity, double x, double z){
        int facing = ((ByteTag) (entity).get("Facing")).getValue();
        facing = Math.floorMod(facing - 1, 4);  //TODO this only works for 90 degrees
        entity.put("Facing", new ByteTag("Facing", (byte) facing));
        entity.put("TileX", new IntTag("", (int) Math.round(x))); //TODO changing tilex and tilez doesn't do anything
        entity.put("TileZ", new IntTag("", (int) Math.round(z)));
    }

    private void rotateEntities(CompoundMap level){
        for (int i=0; i< ((List<?>) (level).get("Entities").getValue()).size(); i++){
            CompoundMap entity = ((CompoundTag) ((List<?>) (level).get("Entities").getValue()).get(i)).getValue();
            List<DoubleTag> pos = (List<DoubleTag>) entity.get("Pos").getValue();
            double x = (pos.get(2).getValue()); //TODO this only works for 90 degrees
            double z = (((pos.get(0).getValue())-8)*-1)+8; //TODO this only works for 90 degrees
            double y = (pos.get(1).getValue());
            List<DoubleTag> newPos = Arrays.asList(new DoubleTag("", x), new DoubleTag("", y), new DoubleTag("", z));
            entity.put(new ListTag<>("Pos", DoubleTag.class, newPos));

            String blockName = ((String) entity.get("id").getValue());
            if (blockName.equals("minecraft:item_frame")){
                handleItemFrames(x, z, entity);
            }

            if (blockName.equals("minecraft:armor_stand")){
                handleArmorStand(entity);
            }
            if (blockName.equals("minecraft:painting")){
                handlePainting(entity, x, z);
            }
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
                    int metaData = rotateMetadata(newBlocks[newIndex], EditTask.nibbleGetAtIndex(meta, oldIndex), newIndex);
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

        this.rotateEntities(level);

        this.rotateBlocks(sectionDetails);

        this.rotateTileEntities(level);

        outCubes.add(new ImmutablePair<>(dstPos, new ImmutablePair<>(inCubePriority + 1, cubeTag)));
        return outCubes;
    }

}


