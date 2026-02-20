package com.osrsbot.claude.pathfinder;

import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;

import java.util.*;

/**
 * A* pathfinder using pre-built collision map and transport connections.
 * Finds shortest walkable path between any two world coordinates.
 *
 * Anti-detection: priority tiebreaker adds random noise so nodes with
 * equal f-scores are explored in random order, producing different
 * but equally-optimal paths through open areas on each invocation.
 *
 * Ported from github.com/Runemoro/shortest-path with A* upgrade.
 */
public class Pathfinder
{
    private static final WorldArea WILDERNESS_ABOVE = new WorldArea(2944, 3523, 448, 448, 0);
    private static final WorldArea WILDERNESS_BELOW = new WorldArea(2944, 9918, 320, 442, 0);

    /** Scale factor for priority values. Must exceed max tiebreaker to preserve optimality. */
    private static final int PRIORITY_SCALE = 4;

    private final CollisionMap map;
    private final Node start;
    private final WorldPoint target;
    private final PriorityQueue<Node> boundary = new PriorityQueue<>();
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
        this.avoidWilderness = avoidWilderness;
        this.restrictToF2P = restrictToF2P;
        this.runtimeBlocked = runtimeBlocked;
        this.start = new Node(start, null, 0,
            chebyshev(start, target) * PRIORITY_SCALE + random.nextInt(PRIORITY_SCALE));
        nearest = null;
    }

    public List<WorldPoint> find()
    {
        boundary.add(start);
        int bestDistance = Integer.MAX_VALUE;

        while (!boundary.isEmpty())
        {
            Node node = boundary.poll();

            if (node.position.getX() == target.getX()
                && node.position.getY() == target.getY()
                && node.position.getPlane() == target.getPlane())
            {
                return node.path();
            }

            // Track nearest node — strongly prefer same-plane nodes so we never
            // route to the right (x,y) on the wrong floor when falling back
            int distance = nearestDistance(node.position, target);
            if (nearest == null || distance < bestDistance)
            {
                nearest = node;
                bestDistance = distance;
            }

            addNeighbors(node);
        }

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

        if (map.n(x, y, z)) addNeighbor(node, new WorldPoint(x, y + 1, z));
        if (map.s(x, y, z)) addNeighbor(node, new WorldPoint(x, y - 1, z));
        if (map.e(x, y, z)) addNeighbor(node, new WorldPoint(x + 1, y, z));
        if (map.w(x, y, z)) addNeighbor(node, new WorldPoint(x - 1, y, z));
        if (map.ne(x, y, z)) addNeighbor(node, new WorldPoint(x + 1, y + 1, z));
        if (map.nw(x, y, z)) addNeighbor(node, new WorldPoint(x - 1, y + 1, z));
        if (map.se(x, y, z)) addNeighbor(node, new WorldPoint(x + 1, y - 1, z));
        if (map.sw(x, y, z)) addNeighbor(node, new WorldPoint(x - 1, y - 1, z));

        for (WorldPoint transport : transports.getOrDefault(node.position, Collections.emptyList()))
        {
            addNeighbor(node, transport);
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

        if (!runtimeBlocked.isEmpty() && runtimeBlocked.contains(neighbor))
        {
            return;
        }

        if (!visited.add(neighbor))
        {
            return;
        }

        int newCost = node.cost + 1;
        int h = chebyshev(neighbor, target);
        // Scale f-score so tiebreaker never changes node ordering:
        // f-score gap of 1 -> priority gap of PRIORITY_SCALE (4), tiebreaker is 0..3
        int priority = (newCost + h) * PRIORITY_SCALE + random.nextInt(PRIORITY_SCALE);
        boundary.add(new Node(neighbor, node, newCost, priority));
    }

    /**
     * Chebyshev distance: exact optimal distance for 8-directional unit-cost movement.
     * Admissible and consistent, so A* with closed set is correct.
     * Note: 2D only (ignores plane) — this is intentional for the A* heuristic
     * since plane changes via transports have unpredictable cost.
     */
    private static int chebyshev(WorldPoint a, WorldPoint b)
    {
        return Math.max(
            Math.abs(a.getX() - b.getX()),
            Math.abs(a.getY() - b.getY()));
    }

    /**
     * Distance metric for "nearest node" fallback tracking.
     * Adds a large penalty for different planes so we never prefer a wrong-floor
     * node over a same-floor one. This prevents the pathfinder from returning a
     * path to e.g. (3212,3215,2) when targeting (3212,3215,0) — same x,y but
     * different floor in a multi-story building.
     */
    private static int nearestDistance(WorldPoint a, WorldPoint b)
    {
        int planePenalty = (a.getPlane() != b.getPlane()) ? 10000 : 0;
        return Math.max(
            Math.abs(a.getX() - b.getX()),
            Math.abs(a.getY() - b.getY())) + planePenalty;
    }

    private static boolean isInWilderness(WorldPoint p)
    {
        return WILDERNESS_ABOVE.distanceTo(p) == 0 || WILDERNESS_BELOW.distanceTo(p) == 0;
    }

    private static boolean isF2PArea(WorldPoint p)
    {
        int x = p.getX();
        int y = p.getY();

        if (y < 2560 || y > 4096)
        {
            return true;
        }

        if (x >= 2880 && x <= 3424 && y >= 2560 && y < 3400)
        {
            return true;
        }

        if (x >= 2944 && x <= 3424 && y >= 3400 && y <= 4096)
        {
            return true;
        }

        if (x >= 2820 && x <= 2970 && y >= 3120 && y <= 3250)
        {
            return true;
        }

        if (x >= 2432 && x <= 2656 && y >= 2816 && y <= 3040)
        {
            return true;
        }

        if (x >= 2810 && x <= 2870 && y >= 3220 && y <= 3310)
        {
            return true;
        }

        return false;
    }

    private static class Node implements Comparable<Node>
    {
        public final WorldPoint position;
        public final Node previous;
        public final int cost;
        public final int priority;

        public Node(WorldPoint position, Node previous, int cost, int priority)
        {
            this.position = position;
            this.previous = previous;
            this.cost = cost;
            this.priority = priority;
        }

        @Override
        public int compareTo(Node other)
        {
            return Integer.compare(this.priority, other.priority);
        }

        public List<WorldPoint> path()
        {
            List<WorldPoint> path = new ArrayList<>();
            Node node = this;
            while (node != null)
            {
                path.add(node.position);
                node = node.previous;
            }
            Collections.reverse(path);
            return path;
        }
    }
}
