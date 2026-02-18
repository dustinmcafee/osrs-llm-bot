package com.osrsbot.claude.pathfinder;

import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;

import java.util.*;

/**
 * BFS pathfinder using pre-built collision map and transport connections.
 * Finds shortest walkable path between any two world coordinates.
 *
 * Anti-detection: neighbor order is shuffled each node expansion, producing
 * different but equally-optimal paths through open areas on each invocation.
 *
 * Ported from github.com/Runemoro/shortest-path with modifications.
 */
public class Pathfinder
{
    private static final WorldArea WILDERNESS_ABOVE = new WorldArea(2944, 3523, 448, 448, 0);
    private static final WorldArea WILDERNESS_BELOW = new WorldArea(2944, 9918, 320, 442, 0);

    private final CollisionMap map;
    private final Node start;
    private final WorldPoint target;
    private final List<Node> boundary = new LinkedList<>();
    private final Set<WorldPoint> visited = new HashSet<>();
    private final Map<WorldPoint, List<WorldPoint>> transports;
    private final boolean avoidWilderness;
    private final Random random = new Random();
    private Node nearest;

    public Pathfinder(CollisionMap map, Map<WorldPoint, List<WorldPoint>> transports,
                      WorldPoint start, WorldPoint target, boolean avoidWilderness)
    {
        this.map = map;
        this.transports = transports;
        this.target = target;
        this.start = new Node(start, null);
        this.avoidWilderness = avoidWilderness;
        nearest = null;
    }

    public List<WorldPoint> find()
    {
        boundary.add(start);
        int bestDistance = Integer.MAX_VALUE;

        while (!boundary.isEmpty())
        {
            Node node = boundary.remove(0);

            if (node.position.equals(target))
            {
                return node.path();
            }

            int distance = Math.max(
                Math.abs(node.position.getX() - target.getX()),
                Math.abs(node.position.getY() - target.getY()));
            if (nearest == null || distance < bestDistance)
            {
                nearest = node;
                bestDistance = distance;
            }

            addNeighbors(node);
        }

        // No exact path found — return path to nearest reachable tile
        if (nearest != null)
        {
            return nearest.path();
        }

        return null;
    }

    private void addNeighbors(Node node)
    {
        int x = node.position.getX();
        int y = node.position.getY();
        int z = node.position.getPlane();

        // Collect all valid neighbors, then shuffle for path randomization
        List<WorldPoint> neighbors = new ArrayList<>(12);

        if (map.n(x, y, z)) neighbors.add(new WorldPoint(x, y + 1, z));
        if (map.s(x, y, z)) neighbors.add(new WorldPoint(x, y - 1, z));
        if (map.e(x, y, z)) neighbors.add(new WorldPoint(x + 1, y, z));
        if (map.w(x, y, z)) neighbors.add(new WorldPoint(x - 1, y, z));
        if (map.ne(x, y, z)) neighbors.add(new WorldPoint(x + 1, y + 1, z));
        if (map.nw(x, y, z)) neighbors.add(new WorldPoint(x - 1, y + 1, z));
        if (map.se(x, y, z)) neighbors.add(new WorldPoint(x + 1, y - 1, z));
        if (map.sw(x, y, z)) neighbors.add(new WorldPoint(x - 1, y - 1, z));

        // Add transport connections (doors, stairs, ladders, etc.)
        for (WorldPoint transport : transports.getOrDefault(node.position, Collections.emptyList()))
        {
            neighbors.add(transport);
        }

        // Shuffle to produce different optimal paths each time (anti-detection)
        Collections.shuffle(neighbors, random);

        for (WorldPoint neighbor : neighbors)
        {
            addNeighbor(node, neighbor);
        }
    }

    private void addNeighbor(Node node, WorldPoint neighbor)
    {
        if (avoidWilderness && isInWilderness(neighbor))
        {
            return;
        }

        if (!visited.add(neighbor))
        {
            return;
        }

        boundary.add(new Node(neighbor, node));
    }

    private static boolean isInWilderness(WorldPoint p)
    {
        return WILDERNESS_ABOVE.distanceTo(p) == 0 || WILDERNESS_BELOW.distanceTo(p) == 0;
    }

    private static class Node
    {
        public final WorldPoint position;
        public final Node previous;

        public Node(WorldPoint position, Node previous)
        {
            this.position = position;
            this.previous = previous;
        }

        public List<WorldPoint> path()
        {
            List<WorldPoint> path = new LinkedList<>();
            Node node = this;
            while (node != null)
            {
                path.add(0, node.position);
                node = node.previous;
            }
            return new ArrayList<>(path);
        }
    }
}
