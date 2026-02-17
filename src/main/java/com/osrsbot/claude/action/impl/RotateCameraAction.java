package com.osrsbot.claude.action.impl;

import com.osrsbot.claude.action.ActionResult;
import com.osrsbot.claude.action.ActionType;
import com.osrsbot.claude.action.BotAction;
import com.osrsbot.claude.human.HumanSimulator;
import com.osrsbot.claude.util.ClientThreadRunner;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;

import java.awt.event.KeyEvent;

/**
 * Rotates the camera by holding arrow keys — exactly how a human would do it.
 *
 * Supports two modes:
 * 1. Directional hold: option="left"/"right"/"up"/"down", ticks=duration
 *    Holds the arrow key for (ticks * 600ms) via HumanSimulator.holdKey()
 *
 * 2. Cardinal snap: option="north"/"south"/"east"/"west"
 *    Reads current camera yaw, calculates the shortest rotation direction,
 *    and holds the appropriate arrow key until facing the target direction.
 *
 * All key input goes through the EventQueue via MouseController so the
 * game client sees the same event stream as real keyboard hardware.
 */
public class RotateCameraAction
{
    // OSRS camera yaw values (0-2048 JAU range, where 2048 = 360 degrees):
    // 0 = north, 512 = west, 1024 = south, 1536 = east
    private static final int YAW_NORTH = 0;
    private static final int YAW_SOUTH = 1024;
    private static final int YAW_EAST = 1536;
    private static final int YAW_WEST = 512;

    // Approximate yaw change per 600ms of key hold
    private static final int YAW_PER_TICK = 256;

    public static ActionResult execute(Client client, HumanSimulator human,
                                       ClientThread clientThread, BotAction action)
    {
        String option = action.getOption();
        if (option == null || option.isEmpty())
        {
            return ActionResult.failure(ActionType.ROTATE_CAMERA, "No direction specified");
        }

        option = option.toLowerCase();

        // Mode 1: Direct arrow key hold
        switch (option)
        {
            case "left":
                return holdArrowKey(human, KeyEvent.VK_LEFT, action.getTicks());
            case "right":
                return holdArrowKey(human, KeyEvent.VK_RIGHT, action.getTicks());
            case "up":
                return holdArrowKey(human, KeyEvent.VK_UP, action.getTicks());
            case "down":
                return holdArrowKey(human, KeyEvent.VK_DOWN, action.getTicks());
        }

        // Mode 2: Snap to cardinal direction
        int targetYaw;
        switch (option)
        {
            case "north":
                targetYaw = YAW_NORTH;
                break;
            case "south":
                targetYaw = YAW_SOUTH;
                break;
            case "east":
                targetYaw = YAW_EAST;
                break;
            case "west":
                targetYaw = YAW_WEST;
                break;
            default:
                return ActionResult.failure(ActionType.ROTATE_CAMERA,
                    "Unknown direction: " + option + ". Use left/right/up/down/north/south/east/west");
        }

        // Get current camera yaw on client thread
        int currentYaw;
        try
        {
            currentYaw = ClientThreadRunner.runOnClientThread(clientThread, () -> client.getMapAngle());
        }
        catch (Throwable t)
        {
            return ActionResult.failure(ActionType.ROTATE_CAMERA, "Failed to read camera angle: " + t.getMessage());
        }

        // Calculate shortest rotation direction
        int diff = targetYaw - currentYaw;
        // Normalize to [-1024, 1024]
        while (diff > 1024) diff -= 2048;
        while (diff < -1024) diff += 2048;

        if (Math.abs(diff) < 32)
        {
            // Already facing the right direction (within ~6 degrees)
            return ActionResult.success(ActionType.ROTATE_CAMERA);
        }

        // Calculate hold duration based on angular distance
        int holdMs = (int) (Math.abs(diff) / (double) YAW_PER_TICK * 600);
        holdMs = Math.max(200, Math.min(holdMs, 3000)); // clamp to reasonable range

        int keyCode = diff > 0 ? KeyEvent.VK_LEFT : KeyEvent.VK_RIGHT;
        human.holdKey(keyCode, holdMs);
        human.shortPause();

        return ActionResult.success(ActionType.ROTATE_CAMERA);
    }

    private static ActionResult holdArrowKey(HumanSimulator human, int keyCode, int ticks)
    {
        if (ticks <= 0) ticks = 1;
        int durationMs = ticks * 600;
        durationMs = Math.max(200, Math.min(durationMs, 5000)); // safety clamp

        human.holdKey(keyCode, durationMs);
        human.shortPause();

        return ActionResult.success(ActionType.ROTATE_CAMERA);
    }
}
