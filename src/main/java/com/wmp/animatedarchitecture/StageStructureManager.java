package com.wmp.animatedarchitecture;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.decoration.DisplayEntity.BlockDisplayEntity;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.*;

public class StageStructureManager {

    public static class SavedCuboid {
        public BlockPos min;
        public BlockPos max;
        public boolean isAnimating = false;

        public SavedCuboid(BlockPos p1, BlockPos p2) {
            this.min = new BlockPos(Math.min(p1.getX(), p2.getX()), Math.min(p1.getY(), p2.getY()), Math.min(p1.getZ(), p2.getZ()));
            this.max = new BlockPos(Math.max(p1.getX(), p2.getX()), Math.max(p1.getY(), p2.getY()), Math.max(p1.getZ(), p2.getZ()));
        }
    }

    private static class AnimatedPiece {
        final BlockDisplayEntity display;
        final BlockPos originalPos;
        final BlockState state;
        AnimatedPiece(BlockDisplayEntity display, BlockPos originalPos, BlockState state) {
            this.display = display; this.originalPos = originalPos; this.state = state;
        }
    }

    private static class ActiveAnimation {
        final ServerWorld world;
        final SavedCuboid cuboid;
        final List<AnimatedPiece> pieces = new ArrayList<>();
        final int totalTicks;
        int currentTick = 0;
        final Vec3d totalOffset;

        ActiveAnimation(ServerWorld world, SavedCuboid cuboid, Vec3d totalOffset, int totalTicks) {
            this.world = world; this.cuboid = cuboid; this.totalOffset = totalOffset; this.totalTicks = totalTicks;
        }
    }

    private static final Map<String, SavedCuboid> STRUCTURES = new HashMap<>();
    private static final List<ActiveAnimation> ACTIVE_ANIMATIONS = new ArrayList<>();

    public static void registerStructure(String name, BlockPos p1, BlockPos p2) {
        STRUCTURES.put(name.toLowerCase(Locale.ROOT), new SavedCuboid(p1, p2));
    }

    public static boolean moveNearestStructure(ServerWorld world, BlockPos controllerPos, Direction dir, double distance, int ticks) {
        String nearestName = null; double bestDist = 256.0;
        for (Map.Entry<String, SavedCuboid> entry : STRUCTURES.entrySet()) {
            SavedCuboid c = entry.getValue();
            BlockPos center = new BlockPos((c.min.getX() + c.max.getX()) / 2, (c.min.getY() + c.max.getY()) / 2, (c.min.getZ() + c.max.getZ()) / 2);
            double d = center.getSquaredDistance(controllerPos);
            if (d < bestDist) { bestDist = d; nearestName = entry.getKey(); }
        }
        if (nearestName == null) return false;
        return animateTranslation(world, nearestName, Vec3d.of(dir.getVector()).multiply(distance), ticks);
    }

    public static boolean animateTranslation(ServerWorld world, String name, Vec3d offset, int ticks) {
        SavedCuboid cuboid = STRUCTURES.get(name.toLowerCase(Locale.ROOT));
        if (cuboid == null || cuboid.isAnimating) return false;
        ActiveAnimation anim = new ActiveAnimation(world, cuboid, offset, ticks);
        cuboid.isAnimating = true;

        for (int x = cuboid.min.getX(); x <= cuboid.max.getX(); x++) {
            for (int y = cuboid.min.getY(); y <= cuboid.max.getY(); y++) {
                for (int z = cuboid.min.getZ(); z <= cuboid.max.getZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = world.getBlockState(pos);
                    if (state.isAir() || state.isOf(Blocks.LODESTONE)) continue;
                    BlockDisplayEntity display = new BlockDisplayEntity(EntityType.BLOCK_DISPLAY, world);
                    display.setPosition(x, y, z);
                    display.setBlockState(state);
                    world.spawnEntity(display);
                    world.setBlockState(pos, Blocks.AIR.getDefaultState(), 18);
                    anim.pieces.add(new AnimatedPiece(display, pos, state));
                }
            }
        }
        ACTIVE_ANIMATIONS.add(anim);
        return true;
    }

    public static void tickAll(MinecraftServer server) {
        Iterator<ActiveAnimation> it = ACTIVE_ANIMATIONS.iterator();
        while (it.hasNext()) {
            ActiveAnimation anim = it.next();
            anim.currentTick++;
            double progress = (double) anim.currentTick / anim.totalTicks;
            double prevProgress = (double) (anim.currentTick - 1) / anim.totalTicks;
            Vec3d currentOffset = anim.totalOffset.multiply(progress);
            Vec3d stepDelta = anim.totalOffset.multiply(progress - prevProgress);

            for (AnimatedPiece piece : anim.pieces) {
                Vec3d target = Vec3d.of(piece.originalPos).add(currentOffset);
                piece.display.setPosition(target.x, target.y, target.z);
            }

            Box cabinBox = new Box(
                anim.cuboid.min.getX() - 0.5, anim.cuboid.min.getY() - 0.5, anim.cuboid.min.getZ() - 0.5,
                anim.cuboid.max.getX() + 1.5, anim.cuboid.max.getY() + 2.5, anim.cuboid.max.getZ() + 1.5
            ).offset(anim.totalOffset.multiply(prevProgress));

            for (Entity entity : anim.world.getOtherEntities(null, cabinBox)) {
                if (entity instanceof BlockDisplayEntity) continue;
                if (entity instanceof ServerPlayerEntity player) {
                    player.networkHandler.requestTeleport(
                        player.getX() + stepDelta.x, player.getY() + stepDelta.y, player.getZ() + stepDelta.z,
                        player.getYaw(), player.getPitch(), EnumSet.noneOf(PositionFlag.class)
                    );
                } else {
                    entity.move(MovementType.SHULKER_BOX, stepDelta);
                }
            }

            if (anim.currentTick >= anim.totalTicks) {
                for (AnimatedPiece piece : anim.pieces) {
                    BlockPos finalPos = BlockPos.ofFloored(piece.display.getPos().add(0.5, 0.5, 0.5));
                    anim.world.setBlockState(finalPos, piece.state, 3);
                    piece.display.discard();
                }
                BlockPos delta = BlockPos.ofFloored(anim.totalOffset.add(0.5, 0.5, 0.5));
                anim.cuboid.min = anim.cuboid.min.add(delta);
                anim.cuboid.max = anim.cuboid.max.add(delta);
                anim.cuboid.isAnimating = false;
                it.remove();
            }
        }
    }
}
