package com.aiplayercompanion.pathing;

import com.aiplayercompanion.config.AIPlayerCleanConfig;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

public final class AIPlayerPathfinder {
    private static final Direction[] HORIZONTAL_DIRECTIONS = new Direction[]{
            Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };

    private AIPlayerPathfinder() {
    }

    public static PathResult findPath(ServerPlayerEntity bot, ServerPlayerEntity owner, AIPlayerCleanConfig config) {
        BlockPos start = bot.getBlockPos().toImmutable();
        Set<BlockPos> goals = collectGoalPositions(owner, bot.getWorld(), config);
        if (goals.isEmpty()) {
            return PathResult.unreachable(0);
        }
        if (goals.contains(start)) {
            return PathResult.found(List.of(PathStep.walk(start)), 0);
        }

        int maxNodes = Math.max(256, config.pathMaxNodes);
        double maxDistanceSq = config.pathMaxDistance * config.pathMaxDistance;
        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::fScore));
        Map<BlockPos, Double> gScore = new HashMap<>();
        Map<BlockPos, CameFrom> cameFrom = new HashMap<>();
        Set<BlockPos> closed = new HashSet<>();

        gScore.put(start, 0.0);
        open.add(new Node(start, heuristic(start, goals)));

        int visited = 0;
        while (!open.isEmpty() && visited < maxNodes) {
            Node currentNode = open.poll();
            BlockPos current = currentNode.pos();
            if (!closed.add(current)) {
                continue;
            }
            visited++;

            if (goals.contains(current)) {
                return PathResult.found(reconstruct(cameFrom, current, start), visited);
            }

            for (Neighbor neighbor : neighbors(bot, current)) {
                BlockPos next = neighbor.step().pos();
                if (closed.contains(next) || next.getSquaredDistance(start) > maxDistanceSq) {
                    continue;
                }
                double tentative = gScore.getOrDefault(current, Double.MAX_VALUE) + neighbor.cost();
                if (tentative >= gScore.getOrDefault(next, Double.MAX_VALUE)) {
                    continue;
                }
                cameFrom.put(next, new CameFrom(current, neighbor.step()));
                gScore.put(next, tentative);
                open.add(new Node(next, tentative + heuristic(next, goals)));
            }
        }

        return PathResult.unreachable(visited);
    }

    private static Set<BlockPos> collectGoalPositions(ServerPlayerEntity owner, World world, AIPlayerCleanConfig config) {
        Set<BlockPos> goals = new HashSet<>();
        BlockPos origin = owner.getBlockPos();
        int minRadius = Math.max(3, (int) Math.floor(config.stopFollowDistance) + 1);
        int maxRadius = Math.max(minRadius + 1, 5);

        for (int radius = minRadius; radius <= maxRadius; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) != radius && Math.abs(dz) != radius) {
                        continue;
                    }
                    for (int dy = 2; dy >= -3; dy--) {
                        BlockPos pos = origin.add(dx, dy, dz).toImmutable();
                        if (isStandable(world, pos, null)) {
                            goals.add(pos);
                        }
                    }
                }
            }
        }
        return goals;
    }

    private static List<Neighbor> neighbors(ServerPlayerEntity bot, BlockPos current) {
        List<Neighbor> result = new ArrayList<>(12);
        World world = bot.getWorld();
        for (Direction direction : HORIZONTAL_DIRECTIONS) {
            BlockPos base = current.offset(direction).toImmutable();
            BlockPos door = closedDoorAt(world, base);
            boolean ignoreDoor = door != null;

            if (isStandable(world, base, ignoreDoor ? door : null)) {
                result.add(new Neighbor(toStep(base, door, MovementType.WALK), door == null ? 1.0 : 2.4));
                continue;
            }

            BlockPos stepUp = base.up().toImmutable();
            if (isStandable(world, stepUp, ignoreDoor ? door : null) && isPassable(world, current.up(), null)) {
                result.add(new Neighbor(toStep(stepUp, door, MovementType.STEP_UP), door == null ? 1.4 : 2.8));
                continue;
            }

            for (int drop = 1; drop <= 2; drop++) {
                BlockPos dropped = base.down(drop).toImmutable();
                if (isStandable(world, dropped, ignoreDoor ? door : null)) {
                    result.add(new Neighbor(toStep(dropped, door, MovementType.DROP), door == null ? 1.25 + drop * 0.25 : 2.6 + drop * 0.25));
                    break;
                }
            }
        }
        return result;
    }

    private static PathStep toStep(BlockPos pos, BlockPos door, MovementType fallback) {
        if (door != null) {
            return PathStep.openDoor(pos, door);
        }
        return switch (fallback) {
            case STEP_UP -> PathStep.stepUp(pos);
            case DROP -> PathStep.drop(pos);
            default -> PathStep.walk(pos);
        };
    }

    private static List<PathStep> reconstruct(Map<BlockPos, CameFrom> cameFrom, BlockPos end, BlockPos start) {
        ArrayList<PathStep> steps = new ArrayList<>();
        BlockPos cursor = end;
        while (!cursor.equals(start)) {
            CameFrom previous = cameFrom.get(cursor);
            if (previous == null) {
                break;
            }
            steps.add(previous.step());
            cursor = previous.from();
        }
        ArrayList<PathStep> ordered = new ArrayList<>(steps.size());
        for (int i = steps.size() - 1; i >= 0; i--) {
            ordered.add(steps.get(i));
        }
        return ordered;
    }

    private static double heuristic(BlockPos pos, Set<BlockPos> goals) {
        double best = Double.MAX_VALUE;
        for (BlockPos goal : goals) {
            double distance = Math.abs(goal.getX() - pos.getX())
                    + Math.abs(goal.getY() - pos.getY()) * 1.5
                    + Math.abs(goal.getZ() - pos.getZ());
            if (distance < best) {
                best = distance;
            }
        }
        return best;
    }

    private static boolean isStandable(World world, BlockPos pos, BlockPos ignoredDoor) {
        BlockState floor = world.getBlockState(pos.down());
        if (!floor.isSideSolidFullSquare(world, pos.down(), Direction.UP) || isHazard(floor)) {
            return false;
        }
        return isPassable(world, pos, ignoredDoor) && isPassable(world, pos.up(), ignoredDoor);
    }

    private static boolean isPassable(World world, BlockPos pos, BlockPos ignoredDoor) {
        if (ignoredDoor != null && (pos.equals(ignoredDoor) || pos.equals(ignoredDoor.up()) || pos.equals(ignoredDoor.down()))) {
            return true;
        }
        BlockState state = world.getBlockState(pos);
        if (isHazard(state) || !state.getFluidState().isEmpty()) {
            return false;
        }
        Box box = new Box(pos);
        return state.getCollisionShape(world, pos).isEmpty() || world.isSpaceEmpty(box);
    }

    private static boolean isHazard(BlockState state) {
        return state.isOf(Blocks.LAVA)
                || state.isOf(Blocks.FIRE)
                || state.isOf(Blocks.SOUL_FIRE)
                || state.isOf(Blocks.CACTUS)
                || state.isOf(Blocks.MAGMA_BLOCK)
                || state.isOf(Blocks.CAMPFIRE)
                || state.isOf(Blocks.SOUL_CAMPFIRE)
                || state.isOf(Blocks.POWDER_SNOW)
                || state.isOf(Blocks.SWEET_BERRY_BUSH);
    }

    private static BlockPos closedDoorAt(World world, BlockPos pos) {
        if (isClosedDoorLike(world.getBlockState(pos))) {
            return pos.toImmutable();
        }
        if (isClosedDoorLike(world.getBlockState(pos.up()))) {
            return pos.up().toImmutable();
        }
        return null;
    }

    private static boolean isClosedDoorLike(BlockState state) {
        Block block = state.getBlock();
        return (block instanceof DoorBlock || block instanceof TrapdoorBlock)
                && state.contains(Properties.OPEN)
                && !state.get(Properties.OPEN);
    }

    private record Node(BlockPos pos, double fScore) {
    }

    private record CameFrom(BlockPos from, PathStep step) {
    }

    private record Neighbor(PathStep step, double cost) {
    }
}
