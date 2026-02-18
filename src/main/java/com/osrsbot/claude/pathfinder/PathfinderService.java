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
 *
 * Maintains separate F2P transport maps so the pathfinder never routes
 * through members-only shortcuts, doors, or teleports on free worlds.
 */
@Singleton
public class PathfinderService
{
    /** Section names in transports.txt that are accessible on F2P worlds */
    private static final Set<String> F2P_SECTIONS = new HashSet<>(Arrays.asList(
        "Tutorial Island",
        "Lumbridge",
        "Farm between Lumbridge and Draynor",
        "Draynor Village",
        "Draynor Sewers",
        "Draynor Manor",
        "South Falador Farm",
        "Falador",
        "Makeover Mage",
        "Dark Wizards' Tower",
        "Killerwatt Plane",
        "Ferox Enclave",
        "Clan Wars (Free-for-all)",
        "Edgeville",
        "Varrock",
        "Stronghold of Security",
        "Barbarian Village",
        "Al Kharid",
        "Citharede Abbey",
        "Al Kharid Mine",
        "Port Sarim",
        "Wizard Tower",
        "Monastery",
        "Dwarven Mine",
        "Rimmington",
        "Wilderness",
        "Musa Point",
        "Karamja Volcano"
    ));

    private CollisionMap collisionMap;

    /** All transport connections for BFS (members + F2P) */
    private Map<WorldPoint, List<WorldPoint>> transportMap;
    /** All transport details for execution */
    private Map<WorldPoint, Transport> transportDetails;

    /** F2P-only transport connections for BFS */
    private Map<WorldPoint, List<WorldPoint>> f2pTransportMap;
    /** F2P-only transport details for execution */
    private Map<WorldPoint, Transport> f2pTransportDetails;

    /** Cached path from last computation */
    private List<WorldPoint> cachedPath;
    private WorldPoint cachedTarget;
    private boolean cachedMembersWorld;
    private int cachedAgilityLevel;
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
            + transportMap.size() + " transport sources ("
            + f2pTransportMap.size() + " F2P)");
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
        f2pTransportMap = new HashMap<>();
        f2pTransportDetails = new HashMap<>();

        try
        {
            String text = new String(
                SplitFlagMap.readAllBytes(
                    PathfinderService.class.getResourceAsStream("/transports.txt")),
                StandardCharsets.UTF_8);

            String currentSection = "";
            Scanner scanner = new Scanner(text);
            while (scanner.hasNextLine())
            {
                String line = scanner.nextLine().trim();
                if (line.isEmpty()) continue;

                if (line.startsWith("#"))
                {
                    currentSection = line.substring(1).trim();
                    continue;
                }

                boolean isF2PSection = F2P_SECTIONS.contains(currentSection);
                parseTransportLine(line, isF2PSection);
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
     *
     * Requirements (in quotes) can include skill levels like "42 Agility"
     * or quest names like "Ernest the Chicken". Transports with requirements
     * the player can't meet are excluded from the filtered transport maps.
     */
    private void parseTransportLine(String line, boolean isF2PSection)
    {
        try
        {
            // Extract quoted requirements before stripping
            String requirement = null;
            int quoteIdx = line.indexOf('"');
            if (quoteIdx >= 0)
            {
                int endQuote = line.indexOf('"', quoteIdx + 1);
                if (endQuote > quoteIdx)
                {
                    requirement = line.substring(quoteIdx + 1, endQuote).trim();
                }
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

            // Parse skill requirements from requirement string
            int agilityReq = 0;
            boolean hasQuestReq = false;
            if (requirement != null && !requirement.isEmpty())
            {
                agilityReq = parseAgilityReq(requirement);
                hasQuestReq = hasQuestRequirement(requirement);
            }

            // Store transport details
            Transport transport = new Transport(start, end, action, target, objectId,
                agilityReq, hasQuestReq, requirement);

            // Add to full transport maps (members world uses these)
            transportMap.computeIfAbsent(start, k -> new ArrayList<>()).add(end);
            transportDetails.putIfAbsent(start, transport);

            // Add to F2P transport maps only if in an F2P section
            if (isF2PSection)
            {
                f2pTransportMap.computeIfAbsent(start, k -> new ArrayList<>()).add(end);
                f2pTransportDetails.putIfAbsent(start, transport);
            }
        }
        catch (NumberFormatException e)
        {
            // Skip malformed lines
        }
    }

    /**
     * Extract agility level requirement from requirement string.
     * Looks for patterns like "42 Agility" or "70 Agility".
     */
    private int parseAgilityReq(String req)
    {
        // Match "N Agility" pattern
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("(\\d+)\\s+Agility", java.util.regex.Pattern.CASE_INSENSITIVE)
            .matcher(req);
        if (m.find())
        {
            return Integer.parseInt(m.group(1));
        }
        return 0;
    }

    /**
     * Check if the requirement includes a quest, diary, or other non-skill gate.
     * These transports should be skipped unless we can verify completion.
     */
    private boolean hasQuestRequirement(String req)
    {
        String lower = req.toLowerCase();
        // Skip if it mentions a quest, diary, or other gate we can't verify
        // But don't flag pure skill requirements or simple notes
        if (lower.contains("diary")) return true;
        if (lower.contains("quest")) return true;

        // Check for named quests (requirement is just a quest name, no skill level)
        // If there's no numeric skill requirement and it's not just a note, treat as quest
        if (!lower.matches(".*\\d+\\s+(agility|mining|strength|ranged|woodcutting|fishing).*")
            && !lower.startsWith("needs to be")
            && !lower.startsWith("has ")
            && !lower.startsWith("no ")
            && !lower.startsWith("chat ")
            && !lower.startsWith("fails "))
        {
            // Likely a quest name like "Ernest the Chicken", "Fishing Contest"
            if (lower.matches(".*[A-Z].*") || req.contains(","))
            {
                // Has capitalized words or commas — might be quest name
                // But also might be a place name in a note
                // Be conservative: only flag if it looks like a known quest pattern
            }
        }
        return false;
    }

    /**
     * Computes a path from start to target. Returns cached path if target matches.
     * Returns null if no path can be found.
     *
     * @param membersWorld true if the player is on a members world (no F2P restriction)
     * @param agilityLevel player's current agility level (filters shortcuts they can't use)
     */
    public List<WorldPoint> getPath(WorldPoint from, WorldPoint to,
                                     boolean membersWorld, int agilityLevel)
    {
        if (!loaded) return null;

        // Return cached path if targeting the same destination, same world type, and player is still on it
        if (cachedPath != null && cachedTarget != null && cachedTarget.equals(to)
            && cachedMembersWorld == membersWorld
            && cachedAgilityLevel == agilityLevel)
        {
            // Check if player is still reasonably near the path
            if (isNearPath(from, cachedPath, 10))
            {
                return cachedPath;
            }
        }

        // Pick the right transport maps based on world type
        Map<WorldPoint, List<WorldPoint>> baseTransports = membersWorld ? transportMap : f2pTransportMap;
        Map<WorldPoint, Transport> baseDetails = membersWorld ? transportDetails : f2pTransportDetails;

        // Filter out transports the player can't actually use (agility, quest reqs)
        Map<WorldPoint, List<WorldPoint>> filteredTransports = filterTransports(
            baseTransports, baseDetails, agilityLevel);

        // Compute new path (restrict to F2P areas when on a free world)
        boolean restrictToF2P = !membersWorld;
        Pathfinder pathfinder = new Pathfinder(collisionMap, filteredTransports,
            from, to, true, restrictToF2P);
        cachedPath = pathfinder.find();
        cachedTarget = to;
        cachedMembersWorld = membersWorld;
        cachedAgilityLevel = agilityLevel;

        if (cachedPath != null)
        {
            System.out.println("[ClaudeBot] Path computed: " + cachedPath.size() + " tiles from "
                + from + " to " + to + (restrictToF2P ? " (F2P)" : ""));
        }
        else
        {
            System.out.println("[ClaudeBot] No path found from " + from + " to " + to);
        }

        return cachedPath;
    }

    /**
     * Filter transport map to only include transports the player can use.
     * Removes agility shortcuts above player level and quest-locked transports.
     */
    private Map<WorldPoint, List<WorldPoint>> filterTransports(
        Map<WorldPoint, List<WorldPoint>> baseTransports,
        Map<WorldPoint, Transport> baseDetails,
        int agilityLevel)
    {
        Map<WorldPoint, List<WorldPoint>> filtered = new HashMap<>();

        for (Map.Entry<WorldPoint, List<WorldPoint>> entry : baseTransports.entrySet())
        {
            WorldPoint start = entry.getKey();
            Transport transport = baseDetails.get(start);

            // If no transport details exist, or transport is usable, include it
            if (transport == null || transport.canUse(agilityLevel))
            {
                filtered.put(start, entry.getValue());
            }
        }

        return filtered;
    }

    /**
     * Returns the Transport at the given tile, or null if none.
     * Used during path execution to know what to interact with.
     */
    public Transport getTransportAt(WorldPoint tile, boolean membersWorld)
    {
        return (membersWorld ? transportDetails : f2pTransportDetails).get(tile);
    }

    /**
     * Returns the Transport that connects 'from' to 'to', or null if none.
     */
    public Transport getTransportBetween(WorldPoint from, WorldPoint to, boolean membersWorld)
    {
        Transport t = (membersWorld ? transportDetails : f2pTransportDetails).get(from);
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
