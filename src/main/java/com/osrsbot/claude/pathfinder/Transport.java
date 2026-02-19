package com.osrsbot.claude.pathfinder;

import net.runelite.api.coords.WorldPoint;

/**
 * Represents a transport connection between two tiles — a door, staircase, ladder,
 * portal, or other interactive object that lets the player traverse an otherwise
 * impassable boundary.
 *
 * Parsed from transports.txt (format: startX startY startZ endX endY endZ action target objectId ["requirements"])
 */
public class Transport
{
    public final WorldPoint start;
    public final WorldPoint end;
    public final String action;       // e.g. "Open", "Climb-up", "Enter"
    public final String target;       // e.g. "Door", "Staircase", "Ladder"
    public final int objectId;        // game object ID
    public final int agilityReq;      // minimum agility level required (0 = none)
    public final boolean hasQuestReq; // requires quest/diary completion
    public final boolean isTollGate;  // requires payment (e.g. 10gp Al Kharid gate)
    public final String requirement;  // raw requirement string (null if none)

    public Transport(WorldPoint start, WorldPoint end, String action, String target, int objectId)
    {
        this(start, end, action, target, objectId, 0, false, false, null);
    }

    public Transport(WorldPoint start, WorldPoint end, String action, String target, int objectId,
                     int agilityReq, boolean hasQuestReq, boolean isTollGate, String requirement)
    {
        this.start = start;
        this.end = end;
        this.action = action;
        this.target = target;
        this.objectId = objectId;
        this.agilityReq = agilityReq;
        this.hasQuestReq = hasQuestReq;
        this.isTollGate = isTollGate;
        this.requirement = requirement;
    }

    /**
     * Check if this transport can be used given the player's world type, agility level,
     * and toll gate preference.
     *
     * On F2P worlds, ALL agility shortcuts are blocked regardless of level
     * (agility is a members skill — shortcuts are members-only interactions).
     * On members worlds, agility shortcuts are allowed if the player meets the level.
     */
    public boolean canUse(int playerAgilityLevel, boolean allowTolls, boolean membersWorld)
    {
        if (hasQuestReq) return false;
        if (isTollGate && !allowTolls) return false;
        if (agilityReq > 0 && !membersWorld) return false;
        if (agilityReq > 0 && playerAgilityLevel < agilityReq) return false;
        return true;
    }

    @Override
    public String toString()
    {
        return action + " " + target + "(id:" + objectId + ") " + start + " -> " + end;
    }
}
