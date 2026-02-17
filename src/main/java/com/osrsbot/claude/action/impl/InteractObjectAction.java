package com.osrsbot.claude.action.impl;

import com.osrsbot.claude.action.ActionResult;
import com.osrsbot.claude.action.ActionType;
import com.osrsbot.claude.action.BotAction;
import com.osrsbot.claude.human.HumanSimulator;
import com.osrsbot.claude.util.ClientThreadRunner;
import com.osrsbot.claude.util.ObjectUtils;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.client.callback.ClientThread;

@Slf4j
public class InteractObjectAction
{
    public static ActionResult execute(Client client, HumanSimulator human, ObjectUtils objectUtils, ClientThread clientThread, BotAction action)
    {
        // Phase 1: Lookup on client thread (blocks background thread until complete)
        // Object API calls (getObjectDefinition, getLocalLocation, getClickbox, etc.) require the client thread.
        Object[] lookupData;
        try
        {
            lookupData = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                TileObject obj = objectUtils.findNearest(client, action.getName());
                if (obj == null) return null;

                int actionIndex = objectUtils.getActionIndex(client, obj, action.getOption());
                MenuAction menuAction = objectUtils.getMenuAction(actionIndex);
                int sceneX = obj.getLocalLocation().getSceneX();
                int sceneY = obj.getLocalLocation().getSceneY();
                int objId = obj.getId();

                java.awt.Point screenPoint = null;
                if (obj.getClickbox() != null)
                {
                    java.awt.Rectangle bounds = obj.getClickbox().getBounds();
                    screenPoint = new java.awt.Point((int) bounds.getCenterX(), (int) bounds.getCenterY());
                }

                return new Object[]{ sceneX, sceneY, objId, actionIndex, menuAction, screenPoint };
            });
        }
        catch (Throwable t)
        {
            System.err.println("[ClaudeBot] Object lookup failed: " + t.getMessage());
            return ActionResult.failure(ActionType.INTERACT_OBJECT, "Lookup failed: " + t.getMessage());
        }

        if (lookupData == null)
        {
            return ActionResult.failure(ActionType.INTERACT_OBJECT, "Object not found: " + action.getName());
        }

        int sceneX = (int) lookupData[0];
        int sceneY = (int) lookupData[1];
        int objId = (int) lookupData[2];
        int actionIndex = (int) lookupData[3];
        MenuAction menuAction = (MenuAction) lookupData[4];
        java.awt.Point screenPoint = (java.awt.Point) lookupData[5];

        if (actionIndex < 0)
        {
            return ActionResult.failure(ActionType.INTERACT_OBJECT, "Option not found on object: " + action.getOption());
        }

        // Phase 2: Mouse movement on background thread (with sleeps for humanization)
        if (screenPoint != null)
        {
            human.moveMouse(screenPoint.x, screenPoint.y);
            human.shortPause();
        }

        // Phase 3: Menu action on client thread (fire-and-forget)
        String objName = action.getName();
        String option = action.getOption();

        System.out.println("[ClaudeBot] Object menuAction: sceneXY=(" + sceneX + "," + sceneY +
            ") id=" + objId + " action=" + menuAction + " option=" + option + " name=" + objName);

        clientThread.invokeLater(() -> {
            try
            {
                client.menuAction(
                    sceneX,         // param0
                    sceneY,         // param1
                    menuAction,     // type
                    objId,          // identifier (object ID)
                    -1,             // itemId
                    option,         // option text
                    objName         // target text
                );
            }
            catch (Throwable t)
            {
                System.err.println("[ClaudeBot] Object menuAction FAILED on client thread: " +
                    t.getClass().getName() + ": " + t.getMessage());
                t.printStackTrace(System.err);
            }
        });

        human.shortPause();
        return ActionResult.success(ActionType.INTERACT_OBJECT);
    }
}
