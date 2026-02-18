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
    private final boolean restrictToF2P;
    private final Set<WorldPoint> runtimeBlocked;
    private final Random random = new Random();
    private Node nearest;

    public Pathfinder(CollisionMap map, Map<WorldPoint, List<WorldPoint>> transports,
                      WorldPoint start, WorldPoint target, boolean avoidWilderness,
                      boolean restrictToF2P)
    {
        this(map, transports, start, target, avoidWilderness, restrictToF2P, Collections.emptySet());
    }

    public Pathfinder(CollisionMap map, Map<WorldPoint, List<WorldPoint>> transports,
                      WorldPoint start, WorldPoint target, boolean avoidWilderness,
                      boolean restrictToF2P, Set<WorldPoint> runtimeBlocked)
    {
        this.map = map;
        this.transports = transports;
        this.target = target;
        this.start = new Node(start, null);
        this.avoidWilderness = avoidWilderness;
        this.restrictToF2P = restrictToF2P;
        this.runtimeBlocked = runtimeBlocked;
        nearest = null;
    }

    public List<WorldPoint> find()
    {
        boundary.add(start);
        int bestDistance = Integer.MAX_VALUE;

        while (!boundary.isEmpty())
        {
            Node node = boundary.remove(0);

            // Match target by x,y only — ignore plane so the BFS terminates
            // as soon as it reaches the right coordinates on ANY plane.
            // Multi-plane paths (via staircase transports) are handled naturally.
            if (node.position.getX() == target.getX()
                && node.position.getY() == target.getY())
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

        if (restrictToF2P && !isF2PArea(neighbor))
        {
            return;
        }

        // Skip tiles discovered at runtime to be blocked (stale collision data)
        if (!runtimeBlocked.isEmpty() && runtimeBlocked.contains(neighbor))
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

    /**
     * Checks if a tile is within the F2P area of the OSRS world.
     * Used to prevent routing through members-only areas on F2P worlds.
     */
    private static boolean isF2PArea(WorldPoint p)
    {
        int x = p.getX();
        int y = p.getY();

        // Non-surface areas (underground, instances) — allow all.
        // These are only reachable via transports that start in F2P areas.
        if (y < 2560 || y > 4096)
        {
            return true;
        }

        // Southern F2P mainland (Rimmington, Port Sarim, Mudskipper Point,
        // Draynor, Lumbridge, Al Kharid, southern Falador)
        // x: 2880-3424, y: 2560-3399
        if (x >= 2880 && x <= 3424 && y >= 2560 && y < 3400)
        {
            return true;
        }

        // Northern F2P + Wilderness (narrower x to exclude Taverly/Burthorpe)
        // Includes Ice Mountain, Edgeville, Barbarian Village, Varrock, GE, Wilderness
        // x: 2944-3424, y: 3400-4096
        if (x >= 2944 && x <= 3424 && y >= 3400 && y <= 4096)
        {
            return true;
        }

        // Karamja F2P area (Musa Point, banana plantation, volcano, fishing docks)
        if (x >= 2820 && x <= 2970 && y >= 3120 && y <= 3250)
        {
            return true;
        }

        // Corsair Cove (F2P area)
        if (x >= 2432 && x <= 2656 && y >= 2816 && y <= 3040)
        {
            return true;
        }

        // Crandor island
        if (x >= 2810 && x <= 2870 && y >= 3220 && y <= 3310)
        {
            return true;
        }

        return false;
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
