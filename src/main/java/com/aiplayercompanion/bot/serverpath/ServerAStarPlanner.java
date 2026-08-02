package com.aiplayercompanion.bot.serverpath;

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

public final class ServerAStarPlanner {
    private static final int MAX_DISTANCE = 72;
    private static final int MAX_VISITED = 2400;

    public PathPlan plan(ServerWorld world, BlockPos start, BlockPos goal) {
        Optional<BlockPos> safeStart = CollisionValidator.findStandable(world, start, 6, true);
        if (safeStart.isEmpty()) {
            return new PathPlan(goal, List.of());
        }
        if (safeStart.get().getManhattanDistance(goal) > MAX_DISTANCE) {
            return new PathPlan(goal, List.of());
        }

        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::score));
        Map<BlockPos, BlockPos> cameFrom = new HashMap<>();
        Map<BlockPos, Double> costs = new HashMap<>();
        HashSet<BlockPos> closed = new HashSet<>();
        BlockPos startPos = safeStart.get();
        open.add(new Node(startPos, 0.0D, heuristic(startPos, goal)));
        costs.put(startPos, 0.0D);

        int visited = 0;
        while (!open.isEmpty() && visited++ < MAX_VISITED) {
            Node current = open.poll();
            if (!closed.add(current.pos())) {
                continue;
            }
            if (current.pos().getSquaredDistance(goal) <= 2.0D) {
                return new PathPlan(goal, reconstruct(world, cameFrom, current.pos()));
            }
            for (BlockPos neighbor : neighbors(world, current.pos())) {
                if (closed.contains(neighbor)) {
                    continue;
                }
                double nextCost = costs.get(current.pos()) + movementCost(world, current.pos(), neighbor);
                if (nextCost >= costs.getOrDefault(neighbor, Double.MAX_VALUE)) {
                    continue;
                }
                cameFrom.put(neighbor, current.pos());
                costs.put(neighbor, nextCost);
                open.add(new Node(neighbor, nextCost, nextCost + heuristic(neighbor, goal)));
            }
        }
        return new PathPlan(goal, List.of());
    }

    private List<BlockPos> neighbors(ServerWorld world, BlockPos pos) {
        ArrayList<BlockPos> result = new ArrayList<>(12);
        addNeighbor(world, result, pos, 1, 0);
        addNeighbor(world, result, pos, -1, 0);
        addNeighbor(world, result, pos, 0, 1);
        addNeighbor(world, result, pos, 0, -1);
        addNeighbor(world, result, pos, 1, 1);
        addNeighbor(world, result, pos, 1, -1);
        addNeighbor(world, result, pos, -1, 1);
        addNeighbor(world, result, pos, -1, -1);

        if (CollisionValidator.isClimbable(world.getBlockState(pos)) || CollisionValidator.isClimbable(world.getBlockState(pos.up()))) {
            if (CollisionValidator.canStandAt(world, pos.up(), true)) {
                result.add(pos.up());
            }
            if (CollisionValidator.canStandAt(world, pos.down(), true)) {
                result.add(pos.down());
            }
        }
        return result;
    }

    private void addNeighbor(ServerWorld world, List<BlockPos> result, BlockPos pos, int dx, int dz) {
        Optional<BlockPos> safe = CollisionValidator.findStandable(world, pos.add(dx, 0, dz), 4, true);
        if (safe.isEmpty()) {
            return;
        }
        int dy = safe.get().getY() - pos.getY();
        if (dy > 1 || dy < -4) {
            return;
        }
        if (dx != 0 && dz != 0) {
            Optional<BlockPos> sideA = CollisionValidator.findStandable(world, pos.add(dx, 0, 0), 3, true);
            Optional<BlockPos> sideB = CollisionValidator.findStandable(world, pos.add(0, 0, dz), 3, true);
            if (sideA.isEmpty() || sideB.isEmpty()) {
                return;
            }
        }
        result.add(safe.get());
    }

    private List<PathStep> reconstruct(ServerWorld world, Map<BlockPos, BlockPos> cameFrom, BlockPos end) {
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
        ArrayList<PathStep> steps = new ArrayList<>();
        BlockPos previous = null;
        for (BlockPos pos : raw) {
            steps.add(new PathStep(pos, classify(world, previous, pos)));
            previous = pos;
        }
        return steps;
    }

    private PathStepType classify(ServerWorld world, BlockPos previous, BlockPos pos) {
        BlockState feet = world.getBlockState(pos);
        BlockState head = world.getBlockState(pos.up());
        if (CollisionValidator.canOpenDoor(feet) || CollisionValidator.canOpenDoor(head)) {
            return PathStepType.OPEN_DOOR;
        }
        if (CollisionValidator.isClimbable(feet) || CollisionValidator.isClimbable(head)) {
            if (previous != null && pos.getY() < previous.getY()) {
                return PathStepType.CLIMB_DOWN;
            }
            return PathStepType.CLIMB_UP;
        }
        if (previous == null) {
            return PathStepType.WALK;
        }
        int dy = pos.getY() - previous.getY();
        if (dy > 0) {
            return PathStepType.STEP_UP;
        }
        if (dy < 0) {
            return PathStepType.STEP_DOWN;
        }
        return PathStepType.WALK;
    }

    private double movementCost(ServerWorld world, BlockPos from, BlockPos to) {
        double horizontal = Math.abs(from.getX() - to.getX()) + Math.abs(from.getZ() - to.getZ());
        double vertical = Math.abs(from.getY() - to.getY()) * 2.0D;
        BlockState feet = world.getBlockState(to);
        BlockState head = world.getBlockState(to.up());
        double door = CollisionValidator.canOpenDoor(feet) || CollisionValidator.canOpenDoor(head) ? 2.0D : 0.0D;
        double climb = CollisionValidator.isClimbable(feet) || CollisionValidator.isClimbable(head) ? 0.75D : 0.0D;
        return horizontal + vertical + door + climb;
    }

    private double heuristic(BlockPos from, BlockPos to) {
        return Math.abs(from.getX() - to.getX()) + Math.abs(from.getY() - to.getY()) * 1.5D + Math.abs(from.getZ() - to.getZ());
    }

    private record Node(BlockPos pos, double cost, double score) {
    }
}
