package com.aiplayercompanion.bot.navigation;

import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;

public final class BotPathPlanner {
    private static final int MAX_PATH_DISTANCE = 64;
    private static final int MAX_VISITED_NODES = 1800;

    private BotPathPlanner() {
    }

    public static List<BotPathNode> findPath(ServerWorld world, BlockPos start, BlockPos goal) {
        Optional<BlockPos> safeStart = BotPathingUtil.findWalkableLanding(world, start, 10);
        if (safeStart.isEmpty()) {
            return List.of();
        }
        if (safeStart.get().getManhattanDistance(goal) > MAX_PATH_DISTANCE) {
            return List.of();
        }

        PriorityQueue<SearchNode> open = new PriorityQueue<>(Comparator.comparingDouble(SearchNode::score));
        Map<BlockPos, BlockPos> cameFrom = new HashMap<>();
        Map<BlockPos, Double> cost = new HashMap<>();
        HashSet<BlockPos> closed = new HashSet<>();
        BlockPos startPos = safeStart.get();
        open.add(new SearchNode(startPos, 0.0D, heuristic(startPos, goal)));
        cost.put(startPos, 0.0D);

        int visited = 0;
        while (!open.isEmpty() && visited++ < MAX_VISITED_NODES) {
            SearchNode current = open.poll();
            if (!closed.add(current.pos())) {
                continue;
            }
            if (current.pos().getSquaredDistance(goal) <= 1.75D) {
                return reconstruct(world, cameFrom, current.pos());
            }
            for (BlockPos neighbor : neighbors(world, current.pos())) {
                if (closed.contains(neighbor)) {
                    continue;
                }
                double newCost = cost.get(current.pos()) + moveCost(world, current.pos(), neighbor);
                if (newCost >= cost.getOrDefault(neighbor, Double.MAX_VALUE)) {
                    continue;
                }
                cameFrom.put(neighbor, current.pos());
                cost.put(neighbor, newCost);
                open.add(new SearchNode(neighbor, newCost, newCost + heuristic(neighbor, goal)));
            }
        }
        return List.of();
    }

    private static List<BlockPos> neighbors(ServerWorld world, BlockPos pos) {
        ArrayList<BlockPos> result = new ArrayList<>(12);
        addHorizontal(world, result, pos, 1, 0);
        addHorizontal(world, result, pos, -1, 0);
        addHorizontal(world, result, pos, 0, 1);
        addHorizontal(world, result, pos, 0, -1);
        addHorizontal(world, result, pos, 1, 1);
        addHorizontal(world, result, pos, 1, -1);
        addHorizontal(world, result, pos, -1, 1);
        addHorizontal(world, result, pos, -1, -1);

        if (BotPathingUtil.isClimbable(world.getBlockState(pos)) || BotPathingUtil.isClimbable(world.getBlockState(pos.up()))) {
            if (BotPathingUtil.isWalkablePosition(world, pos.up(), true)) {
                result.add(pos.up());
            }
            if (BotPathingUtil.isWalkablePosition(world, pos.down(), true)) {
                result.add(pos.down());
            }
        }
        return result;
    }

    private static void addHorizontal(ServerWorld world, List<BlockPos> result, BlockPos pos, int dx, int dz) {
        Optional<BlockPos> safe = BotPathingUtil.findWalkableLanding(world, pos.add(dx, 0, dz), 4);
        if (safe.isEmpty()) {
            return;
        }
        int dy = safe.get().getY() - pos.getY();
        if (dy > 1 || dy < -4) {
            return;
        }
        if (dx != 0 && dz != 0) {
            Optional<BlockPos> sideA = BotPathingUtil.findWalkableLanding(world, pos.add(dx, 0, 0), 3);
            Optional<BlockPos> sideB = BotPathingUtil.findWalkableLanding(world, pos.add(0, 0, dz), 3);
            if (sideA.isEmpty() || sideB.isEmpty()) {
                return;
            }
        }
        result.add(safe.get());
    }

    private static List<BotPathNode> reconstruct(ServerWorld world, Map<BlockPos, BlockPos> cameFrom, BlockPos end) {
        ArrayList<BlockPos> raw = new ArrayList<>();
        BlockPos current = end;
        raw.add(current);
        while (cameFrom.containsKey(current)) {
            current = cameFrom.get(current);
            raw.add(current);
        }
        java.util.Collections.reverse(raw);
        if (!raw.isEmpty()) {
            raw.remove(0);
        }
        ArrayList<BotPathNode> result = new ArrayList<>();
        BlockPos previous = null;
        for (BlockPos pos : raw) {
            result.add(new BotPathNode(pos, classify(world, previous, pos)));
            previous = pos;
        }
        return result;
    }

    private static BotPathNode.NodeType classify(ServerWorld world, BlockPos previous, BlockPos pos) {
        BlockState feet = world.getBlockState(pos);
        BlockState head = world.getBlockState(pos.up());
        if (BotPathingUtil.canOpenDoor(feet) || BotPathingUtil.canOpenDoor(head)) {
            return BotPathNode.NodeType.DOOR;
        }
        if (BotPathingUtil.isClimbable(feet) || BotPathingUtil.isClimbable(head)) {
            return BotPathNode.NodeType.CLIMB;
        }
        if (previous == null) {
            return BotPathNode.NodeType.WALK;
        }
        int dy = pos.getY() - previous.getY();
        if (dy > 0) {
            return dy == 1 ? BotPathNode.NodeType.STEP_UP : BotPathNode.NodeType.JUMP;
        }
        if (dy < 0) {
            return BotPathNode.NodeType.STEP_DOWN;
        }
        return BotPathNode.NodeType.WALK;
    }

    private static double heuristic(BlockPos from, BlockPos to) {
        return Math.abs(from.getX() - to.getX()) + Math.abs(from.getY() - to.getY()) * 1.25D + Math.abs(from.getZ() - to.getZ());
    }

    private static double moveCost(ServerWorld world, BlockPos from, BlockPos to) {
        double horizontal = Math.abs(from.getX() - to.getX()) + Math.abs(from.getZ() - to.getZ());
        double vertical = Math.abs(from.getY() - to.getY()) * 1.8D;
        BlockState feet = world.getBlockState(to);
        BlockState head = world.getBlockState(to.up());
        double door = BotPathingUtil.canOpenDoor(feet) || BotPathingUtil.canOpenDoor(head) ? 1.5D : 0.0D;
        double climb = BotPathingUtil.isClimbable(feet) || BotPathingUtil.isClimbable(head) ? 0.6D : 0.0D;
        return horizontal + vertical + door + climb;
    }

    private record SearchNode(BlockPos pos, double cost, double score) {
    }
}
