package com.osrsbot.claude.util;

import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.*;

@Slf4j
@Singleton
public class TileUtils
{
    @Inject
    private Client client;

    public Point worldToScreen(Client client, WorldPoint worldPoint)
    {
        LocalPoint local = LocalPoint.fromWorld(client, worldPoint);
        if (local == null) return null;

        net.runelite.api.Point canvasPoint = Perspective.localToCanvas(
            client, local, client.getPlane());
        if (canvasPoint == null) return null;

        return new Point(canvasPoint.getX(), canvasPoint.getY());
    }

    public int distanceTo(Client client, WorldPoint target)
    {
        if (client.getLocalPlayer() == null) return Integer.MAX_VALUE;
        return client.getLocalPlayer().getWorldLocation().distanceTo(target);
    }
}
