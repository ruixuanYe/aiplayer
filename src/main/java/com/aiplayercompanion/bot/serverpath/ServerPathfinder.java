package com.aiplayercompanion.bot.serverpath;

import net.minecraft.block.BlockState;
import net.minecraft.block.DoorBlock;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;

public final class ServerPathfinder {
    private static final int MAX_VISITED = 4500;
    private static final int MAX_PATH_LENGTH = 96;
    private static final BlockPos[] CARDINAL = new BlockPos[]{
            new BlockPos(1, 0, 0),
            new BlockPos(-1, 0, 0),
            new BlockPos(0, 0, 1),
            new BlockPos(0, 0, -1)
    };

    private ServerPathfinder() {
    }

    public static Optional<List<BlockPos>> findPath(ServerWorld world, BlockPos rawStart, BlockPos rawGoal, int range) {
        Optional<BlockPos> start = CollisionValidator.findStandable(world, rawStart, 8, true);
        Optional<BlockPos> goal = CollisionValidator.findStandable(world, rawGoal, 16, true);
        if (start.isEmpty() || goal.isEmpty()) {
            return Optional.empty();
        }
        return search(world, start.get(), goal.get(), range);
    }

    private static Optional<List<BlockPos>> search(ServerWorld world, BlockPos start, BlockPos goal, int range) {
        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::score));
        Map<BlockPos, BlockPos> cameFrom = new HashMap<>();
        Map<BlockPos, Double> costs = new HashMap<>();
        open.add(new Node(start, 0.0D, heuristic(start, goal)));
        costs.put(start, 0.0D);

        int visited = 0;
        while (!open.isEmpty() && visited++ < MAX_VISITED) {
            Node current = open.poll();
            if (current.pos.getManhattanDistance(goal) <= 1 || current.pos.equals(goal)) {
                return Optional.of(reconstruct(cameFrom, current.pos));
            }
            if (start.getManhattanDistance(current.pos) > range) {
                continue;
            }
            for (BlockPos neighbor : neighbors(world, current.pos)) {
                double nextCost = current.cost + stepCost(world, neighbor);
                Double previous = costs.get(neighbor);
                if (previous != null && previous <= nextCost) {
                    continue;
                }
                costs.put(neighbor, nextCost);
                cameFrom.put(neighbor, current.pos);
                open.add(new Node(neighbor, nextCost, nextCost + heuristic(neighbor, goal)));
            }
        }
        return Optional.empty();
    }

    private static List<BlockPos> neighbors(ServerWorld world, BlockPos pos) {
        List<BlockPos> result = new ArrayList<>(8);
        for (BlockPos dir : CARDINAL) {
            BlockPos flat = pos.add(dir);
            addIfStandable(world, result, flat);
            addIfStandable(world, result, flat.up());
            addIfStandable(world, result, flat.down());
        }
        return result;
    }

    private static void addIfStandable(ServerWorld world, List<BlockPos> result, BlockPos pos) {
        if (!CollisionValidator.canStandAt(world, pos, true)) {
            return;
        }
        if (pos.getY() <= world.getBottomY() || pos.getY() >= world.getTopYInclusive() - 1) {
            return;
        }
        result.add(pos.toImmutable());
    }

    private static double stepCost(ServerWorld world, BlockPos pos) {
        BlockState feet = world.getBlockState(pos);
        BlockState head = world.getBlockState(pos.up());
        double cost = 1.0D;
        if (isClosedDoor(feet) || isClosedDoor(head)) {
            cost += 2.5D;
        }
        if (feet.isIn(BlockTags.CLIMBABLE) || head.isIn(BlockTags.CLIMBABLE)) {
            cost += 0.35D;
        }
        return cost;
    }

    private static boolean isClosedDoor(BlockState state) {
        return state.getBlock() instanceof DoorBlock
                && state.contains(DoorBlock.OPEN)
                && !state.get(DoorBlock.OPEN);
    }

    private static double heuristic(BlockPos from, BlockPos to) {
        return Math.abs(from.getX() - to.getX())
                + Math.abs(from.getY() - to.getY()) * 1.5D
                + Math.abs(from.getZ() - to.getZ());
    }

    private static List<BlockPos> reconstruct(Map<BlockPos, BlockPos> cameFrom, BlockPos end) {
        List<BlockPos> reversed = new ArrayList<>();
        BlockPos cursor = end;
        reversed.add(cursor);
        while (cameFrom.containsKey(cursor) && reversed.size() < MAX_PATH_LENGTH) {
            cursor = cameFrom.get(cursor);
            reversed.add(cursor);
        }
        List<BlockPos> path = new ArrayList<>(reversed.size());
        for (int i = reversed.size() - 1; i >= 0; i--) {
            path.add(reversed.get(i));
        }
        return path;
    }

    private record Node(BlockPos pos, double cost, double score) {
    }
}
