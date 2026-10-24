package com.wmp.animatedarchitecture;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public class WmpAnimatedArchitectureMod implements ModInitializer {

    @Override
    public void onInitialize() {
        ServerTickEvents.END_SERVER_TICK.register(StageStructureManager::tickAll);

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world.isClient || hand != Hand.MAIN_HAND) return ActionResult.PASS;

            BlockPos pos = hitResult.getBlockPos();
            BlockState state = world.getBlockState(pos);

            if (state.isOf(Blocks.LODESTONE)) {
                Direction face = hitResult.getSide();
                Vec3d localHit = hitResult.getPos().subtract(Vec3d.of(pos));

                double v = localHit.y - 0.5;
                double u = switch (face) {
                    case NORTH -> 0.5 - localHit.x;
                    case SOUTH -> localHit.x - 0.5;
                    case WEST  -> localHit.z - 0.5;
                    case EAST  -> 0.5 - localHit.z;
                    default -> 0.0;
                };

                Direction moveDir;
                if (Math.abs(v) >= Math.abs(u)) {
                    moveDir = (v > 0) ? Direction.UP : Direction.DOWN;
                } else {
                    moveDir = (u < 0) ? face.rotateYCounterclockwise() : face.rotateYClockwise();
                }

                boolean triggered = StageStructureManager.moveNearestStructure(
                    (ServerWorld) world, pos, moveDir, 4.0, 40
                );

                if (triggered) {
                    player.sendMessage(Text.literal("§a[WMP Controller] §fMoving stage/elevator §e" + moveDir.getName().toUpperCase() + "§f!"), true);
                    return ActionResult.SUCCESS;
                }
            }
            return ActionResult.PASS;
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(CommandManager.literal("wmp")
                .requires(src -> src.hasPermissionLevel(2))
                .then(CommandManager.literal("create")
                    .then(CommandManager.argument("name", StringArgumentType.word())
                    .then(CommandManager.argument("pos1", BlockPosArgumentType.blockPos())
                    .then(CommandManager.argument("pos2", BlockPosArgumentType.blockPos())
                        .executes(ctx -> {
                            String name = StringArgumentType.getString(ctx, "name");
                            BlockPos pos1 = BlockPosArgumentType.getBlockPos(ctx, "pos1");
                            BlockPos pos2 = BlockPosArgumentType.getBlockPos(ctx, "pos2");
                            StageStructureManager.registerStructure(name, pos1, pos2);
                            ctx.getSource().sendFeedback(() -> Text.literal("§a[WMP Stagecraft] §fSaved structure §e" + name + "§f!"), false);
                            return 1;
                        })
                    ))))
                .then(CommandManager.literal("move")
                    .then(CommandManager.argument("name", StringArgumentType.word())
                    .then(CommandManager.argument("dx", DoubleArgumentType.doubleArg())
                    .then(CommandManager.argument("dy", DoubleArgumentType.doubleArg())
                    .then(CommandManager.argument("dz", DoubleArgumentType.doubleArg())
                    .then(CommandManager.argument("ticks", IntegerArgumentType.integer(1, 2400))
                        .executes(ctx -> {
                            ServerWorld world = ctx.getSource().getWorld();
                            String name = StringArgumentType.getString(ctx, "name");
                            double dx = DoubleArgumentType.getDouble(ctx, "dx");
                            double dy = DoubleArgumentType.getDouble(ctx, "dy");
                            double dz = DoubleArgumentType.getDouble(ctx, "dz");
                            int ticks = IntegerArgumentType.getInteger(ctx, "ticks");
                            boolean ok = StageStructureManager.animateTranslation(world, name, new Vec3d(dx, dy, dz), ticks);
                            return ok ? 1 : 0;
                        })
                    ))))))
            );
        });
    }
}
