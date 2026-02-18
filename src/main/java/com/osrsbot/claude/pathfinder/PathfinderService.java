package com.osrsbot.claude.pathfinder;

import net.runelite.api.coords.WorldPoint;

import javax.inject.Singleton;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Singleton service that provides pathfinding across the OSRS world.
 * Loads pre-built collision data and transport connections on startup,
 * computes BFS paths on demand, and caches results.
 */
@Singleton
public class PathfinderService
{
    private CollisionMap collisionMap;

    /** Transport connections for the BFS (start → list of reachable endpoints) */
    private Map<WorldPoint, List<WorldPoint>> transportMap;

    /** Transport details for path execution (start → Transport with action/target/objectId) */
    private Map<WorldPoint, Transport> transportDetails;

    /** Cached path from last computation */
    private List<WorldPoint> cachedPath;
    private WorldPoint cachedTarget;
    private volatile boolean loaded = false;

    /**
     * Loads collision data and transport connections. Call once on plugin startup.
     * This is CPU-intensive (~200ms) so call from a background thread or accept the startup cost.
     */
    public void load()
    {
        loadCollisionMap();
        loadTransports();
        loaded = true;
        System.out.println("[ClaudeBot] PathfinderService loaded: collision map + "
            + transportMap.size() + " transport sources");
    }

    private void loadCollisionMap()
    {
        Map<SplitFlagMap.Position, byte[]> compressedRegions = new HashMap<>();

        try (ZipInputStream in = new ZipInputStream(
            PathfinderService.class.getResourceAsStream("/collision-map.zip")))
        {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null)
            {
                String[] parts = entry.getName().split("_");
                compressedRegions.put(
                    new SplitFlagMap.Position(
                        Integer.parseInt(parts[0]),
                        Integer.parseInt(parts[1])),
                    SplitFlagMap.readAllBytes(in));
            }
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }

        collisionMap = new CollisionMap(64, compressedRegions);
    }

    private void loadTransports()
    {
        transportMap = new HashMap<>();
        transportDetails = new HashMap<>();

        try
        {
            String text = new String(
                SplitFlagMap.readAllBytes(
                    PathfinderService.class.getResourceAsStream("/transports.txt")),
                StandardCharsets.UTF_8);

            Scanner scanner = new Scanner(text);
            while (scanner.hasNextLine())
            {
                String line = scanner.nextLine().trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                parseTransportLine(line);
            }
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Parses a transport line:
     * startX startY startZ endX endY endZ action target objectId ["requirements"]
     *
     * Action is one word (Open, Climb-up, Enter, etc.)
     * Target can be multi-word (Portal Home, Underwall tunnel, etc.)
     * ObjectId is the last integer token before optional quoted requirements
     */
    private void parseTransportLine(String line)
    {
        try
        {
            // Strip quoted requirements at the end
            int quoteIdx = line.indexOf('"');
            if (quoteIdx >= 0)
            {
                line = line.substring(0, quoteIdx).trim();
            }

            String[] tokens = line.split("\\s+");
            if (tokens.length < 9) return; // Need at least: 6 coords + action + target + objectId

            int startX = Integer.parseInt(tokens[0]);
            int startY = Integer.parseInt(tokens[1]);
            int startZ = Integer.parseInt(tokens[2]);
            int endX = Integer.parseInt(tokens[3]);
            int endY = Integer.parseInt(tokens[4]);
            int endZ = Integer.parseInt(tokens[5]);

            WorldPoint start = new WorldPoint(startX, startY, startZ);
            WorldPoint end = new WorldPoint(endX, endY, endZ);

            // Last token is objectId, second-to-last back to index 7 is the target name
            String action = tokens[6];
            int objectId = Integer.parseInt(tokens[tokens.length - 1]);

            // Target is everything between action and objectId
            StringBuilder targetBuilder = new StringBuilder();
            for (int i = 7; i < tokens.length - 1; i++)
            {
                if (targetBuilder.length() > 0) targetBuilder.append(" ");
                targetBuilder.append(tokens[i]);
            }
            String target = targetBuilder.toString();

            // Add to BFS transport map
            transportMap.computeIfAbsent(start, k -> new ArrayList<>()).add(end);

            // Store transport details for path execution
            Transport transport = new Transport(start, end, action, target, objectId);
            // Key by start point — for multi-destination transports, keep the first one
            transportDetails.putIfAbsent(start, transport);
        }
        catch (NumberFormatException e)
        {
            // Skip malformed lines
        }
    }

    /**
     * Computes a path from start to target. Returns cached path if target matches.
     * Returns null if no path can be found.
     */
    public List<WorldPoint> getPath(WorldPoint from, WorldPoint to)
    {
        if (!loaded) return null;

        // Return cached path if targeting the same destination and player is still on it
        if (cachedPath != null && cachedTarget != null && cachedTarget.equals(to))
        {
            // Check if player is still reasonably near the path
            if (isNearPath(from, cachedPath, 10))
            {
                return cachedPath;
            }
        }

        // Compute new path
        Pathfinder pathfinder = new Pathfinder(collisionMap, transportMap, from, to, true);
        cachedPath = pathfinder.find();
        cachedTarget = to;

        if (cachedPath != null)
        {
            System.out.println("[ClaudeBot] Path computed: " + cachedPath.size() + " tiles from "
                + from + " to " + to);
        }
        else
        {
            System.out.println("[ClaudeBot] No path found from " + from + " to " + to);
        }

        return cachedPath;
    }

    /**
     * Returns the Transport at the given tile, or null if none.
     * Used during path execution to know what to interact with.
     */
    public Transport getTransportAt(WorldPoint tile)
    {
        return transportDetails.get(tile);
    }

    /**
     * Returns the Transport that connects 'from' to 'to', or null if none.
     */
    public Transport getTransportBetween(WorldPoint from, WorldPoint to)
    {
        Transport t = transportDetails.get(from);
        if (t != null && t.end.equals(to))
        {
            return t;
        }
        return null;
    }

    /**
     * Force recompute on next getPath() call.
     */
    public void invalidate()
    {
        cachedPath = null;
        cachedTarget = null;
    }

    public boolean isLoaded()
    {
        return loaded;
    }

    /**
     * Check if a position is within maxDist tiles of any point on the path.
     */
    private boolean isNearPath(WorldPoint pos, List<WorldPoint> path, int maxDist)
    {
        for (WorldPoint p : path)
        {
            if (pos.distanceTo(p) <= maxDist)
            {
                return true;
            }
        }
        return false;
    }
}
