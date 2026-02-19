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
    /** All transport details for execution (multiple transports per tile) */
    private Map<WorldPoint, List<Transport>> transportDetails;

    /** F2P-only transport connections for BFS */
    private Map<WorldPoint, List<WorldPoint>> f2pTransportMap;
    /** F2P-only transport details for execution (multiple transports per tile) */
    private Map<WorldPoint, List<Transport>> f2pTransportDetails;

    /** Cached path from last computation */
    private List<WorldPoint> cachedPath;
    private WorldPoint cachedTarget;
    private boolean cachedMembersWorld;
    private int cachedAgilityLevel;
    private volatile boolean loaded = false;
    private volatile boolean allowTolls = false;

    /**
     * Tiles discovered at runtime to be blocked (stale collision data).
     * When the player gets stuck, these tiles are added so the BFS routes around them.
     * Cleared when the target changes (new destination = fresh slate).
     */
    private final Set<WorldPoint> runtimeBlockedTiles = new HashSet<>();

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

            // Detect toll gates (e.g. "Pay-toll(10gp)") and grapple shortcuts (members-only, require equipment)
            String actionLower = action.toLowerCase();
            boolean tollGate = actionLower.contains("toll") || actionLower.contains("pay-");
            if (actionLower.equals("grapple"))
            {
                hasQuestReq = true; // Grapple shortcuts require mithril grapple + crossbow
            }

            // Store transport details
            Transport transport = new Transport(start, end, action, target, objectId,
                agilityReq, hasQuestReq, tollGate, requirement);

            // Add to full transport maps (members world uses these)
            transportMap.computeIfAbsent(start, k -> new ArrayList<>()).add(end);
            transportDetails.computeIfAbsent(start, k -> new ArrayList<>()).add(transport);

            // Add to F2P transport maps only if in an F2P section
            if (isF2PSection)
            {
                f2pTransportMap.computeIfAbsent(start, k -> new ArrayList<>()).add(end);
                f2pTransportDetails.computeIfAbsent(start, k -> new ArrayList<>()).add(transport);
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
     * Check if the requirement includes a quest, diary, item gate, varbit check,
     * or other non-skill gate that we cannot verify at runtime.
     * Returns true if the transport should be blocked (requirement can't be confirmed).
     * Returns false for harmless notes, warnings, instructions, and pure skill checks.
     */
    private boolean hasQuestRequirement(String req)
    {
        String lower = req.toLowerCase();

        // --- Harmless patterns: never block these ---
        if (lower.startsWith("has a warning") || lower.startsWith("has warning")
            || lower.startsWith("warning if")) return false;
        if (lower.startsWith("chat option")) return false;
        if (lower.startsWith("select '")) return false;
        if (lower.startsWith("needs to be opened")) return false;
        if (lower.startsWith("fails first")) return false;
        if (lower.startsWith("need to walk") || lower.startsWith("need to click")) return false;
        if (lower.startsWith("if has ") || lower.startsWith("if no ")) return false;
        // Pure run energy check is not a quest gate
        if (lower.matches("\\d+\\s+run energy")) return false;
        // "Agility?" is an uncertain note, not a real gate
        if (lower.equals("agility?")) return false;
        // Pure agility + note combo like "31 Agility, need to click stone in right direction"
        if (lower.matches("\\d+\\s+agility,?\\s+need to.*")) return false;

        // --- Blocking patterns ---
        // Diary requirements
        if (lower.contains("diary")) return true;
        // Explicit quest keyword
        if (lower.contains("quest")) return true;
        // Varbit/state checks we can't verify
        if (lower.contains("varb(") || lower.contains("varbit")) return true;
        // Permission or guild access
        if (lower.contains("permission")) return true;
        if (lower.contains("guild requirements")) return true;

        // Named quest requirements (exact quest names found in transports.txt)
        if (lower.contains("ernest the chicken")) return true;
        if (lower.contains("fishing contest")) return true;
        if (lower.contains("darkness of hallowvale")) return true;

        // Item/key requirements (player must have a specific item)
        if (lower.contains("dusty key")) return true;
        if (lower.contains("crystal-mine key")) return true;
        if (lower.contains("shantay pass")) return true;
        if (lower.contains("skavid map")) return true;
        if (lower.contains("glarial's amulet")) return true;
        if (lower.matches(".*key,?\\s*id\\s*\\d+.*")) return true;

        // If it's just a pure agility number like "42 Agility", that's handled
        // by parseAgilityReq() separately — don't flag it here
        if (lower.matches("\\d+\\s+agility")) return false;

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
        Map<WorldPoint, List<Transport>> baseDetails = membersWorld ? transportDetails : f2pTransportDetails;

        // Filter out transports the player can't actually use (agility, quest reqs)
        Map<WorldPoint, List<WorldPoint>> filteredTransports = filterTransports(
            baseTransports, baseDetails, agilityLevel, membersWorld);

        // Clear runtime blocks when destination changes (fresh start for new path)
        if (cachedTarget == null || !cachedTarget.equals(to))
        {
            runtimeBlockedTiles.clear();
        }

        // Compute new path (restrict to F2P areas when on a free world)
        boolean restrictToF2P = !membersWorld;
        Pathfinder pathfinder = new Pathfinder(collisionMap, filteredTransports,
            from, to, true, restrictToF2P, runtimeBlockedTiles);
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
        Map<WorldPoint, List<Transport>> baseDetails,
        int agilityLevel, boolean membersWorld)
    {
        Map<WorldPoint, List<WorldPoint>> filtered = new HashMap<>();

        for (Map.Entry<WorldPoint, List<WorldPoint>> entry : baseTransports.entrySet())
        {
            WorldPoint start = entry.getKey();
            List<Transport> transports = baseDetails.get(start);

            if (transports == null || transports.isEmpty())
            {
                // No details — include all destinations
                filtered.put(start, entry.getValue());
                continue;
            }

            // Filter individual destinations based on which transports are usable
            List<WorldPoint> usableDestinations = new ArrayList<>();
            for (Transport t : transports)
            {
                if (t.canUse(agilityLevel, allowTolls, membersWorld))
                {
                    usableDestinations.add(t.end);
                }
            }

            if (!usableDestinations.isEmpty())
            {
                filtered.put(start, usableDestinations);
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
        List<Transport> transports = (membersWorld ? transportDetails : f2pTransportDetails).get(tile);
        return (transports != null && !transports.isEmpty()) ? transports.get(0) : null;
    }

    /**
     * Returns the Transport that connects 'from' to 'to', or null if none.
     */
    public Transport getTransportBetween(WorldPoint from, WorldPoint to, boolean membersWorld)
    {
        List<Transport> transports = (membersWorld ? transportDetails : f2pTransportDetails).get(from);
        if (transports != null)
        {
            for (Transport t : transports)
            {
                if (t.end.equals(to))
                {
                    return t;
                }
            }
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

    /**
     * Mark a tile and its neighbors as runtime-blocked (stale collision data).
     * The BFS will avoid these tiles on the next path computation.
     * Called when the player gets stuck at a position the collision map says is walkable.
     */
    public void blockArea(WorldPoint center, int radius)
    {
        int cx = center.getX();
        int cy = center.getY();
        int cz = center.getPlane();
        for (int dx = -radius; dx <= radius; dx++)
        {
            for (int dy = -radius; dy <= radius; dy++)
            {
                runtimeBlockedTiles.add(new WorldPoint(cx + dx, cy + dy, cz));
            }
        }
        System.out.println("[ClaudeBot] Pathfinder: blocked " + ((2 * radius + 1) * (2 * radius + 1))
            + " tiles around " + center + " (total blocked: " + runtimeBlockedTiles.size() + ")");
    }

    public boolean isLoaded()
    {
        return loaded;
    }

    public void setAllowTolls(boolean allowTolls)
    {
        if (this.allowTolls != allowTolls)
        {
            this.allowTolls = allowTolls;
            invalidate(); // Recompute paths with new setting
        }
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
