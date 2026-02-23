package com.osrsbot.claude.action;

/**
 * Stores the last game object clicked by InteractObjectAction.
 * WaitAnimationAction reads this to detect rock/tree depletion
 * by checking if the object still exists at the stored tile.
 */
public class LastInteractedObject
{
    private static volatile int objectId = -1;
    private static volatile int worldX = -1;
    private static volatile int worldY = -1;
    private static volatile int plane = -1;

    public static void set(int id, int x, int y, int p)
    {
        objectId = id;
        worldX = x;
        worldY = y;
        plane = p;
    }

    public static void clear()
    {
        objectId = -1;
    }

    public static int getObjectId() { return objectId; }
    public static int getWorldX() { return worldX; }
    public static int getWorldY() { return worldY; }
    public static int getPlane() { return plane; }
    public static boolean isSet() { return objectId > 0; }
}
