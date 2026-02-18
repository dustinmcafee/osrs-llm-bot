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
    public final String action;    // e.g. "Open", "Climb-up", "Enter"
    public final String target;    // e.g. "Door", "Staircase", "Ladder"
    public final int objectId;     // game object ID

    public Transport(WorldPoint start, WorldPoint end, String action, String target, int objectId)
    {
        this.start = start;
        this.end = end;
        this.action = action;
        this.target = target;
        this.objectId = objectId;
    }

    @Override
    public String toString()
    {
        return action + " " + target + "(id:" + objectId + ") " + start + " -> " + end;
    }
}
