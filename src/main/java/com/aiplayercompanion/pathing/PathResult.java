package com.aiplayercompanion.pathing;

import java.util.List;

public record PathResult(Status status, List<PathStep> steps, int visitedNodes) {
    public enum Status {
        FOUND,
        UNREACHABLE
    }

    public static PathResult found(List<PathStep> steps, int visitedNodes) {
        return new PathResult(Status.FOUND, List.copyOf(steps), visitedNodes);
    }

    public static PathResult unreachable(int visitedNodes) {
        return new PathResult(Status.UNREACHABLE, List.of(), visitedNodes);
    }

    public boolean found() {
        return status == Status.FOUND && !steps.isEmpty();
    }
}
