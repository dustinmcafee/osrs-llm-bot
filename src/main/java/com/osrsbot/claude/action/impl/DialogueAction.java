package com.osrsbot.claude.action.impl;

import com.osrsbot.claude.action.ActionResult;
import com.osrsbot.claude.action.ActionType;
import com.osrsbot.claude.action.BotAction;
import com.osrsbot.claude.human.HumanSimulator;
import com.osrsbot.claude.util.ClientThreadRunner;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.callback.ClientThread;

public class DialogueAction
{
    public static ActionResult execute(Client client, HumanSimulator human, ClientThread clientThread, BotAction action)
    {
        int optionIndex = action.getX();
        if (optionIndex <= 0) optionIndex = 1;
        final int idx = optionIndex;

        // Phase 1: Widget lookup on client thread
        Object[] lookupData;
        try
        {
            lookupData = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                Widget options = client.getWidget(WidgetInfo.DIALOG_OPTION_OPTIONS);
                if (options == null || options.isHidden()) return new Object[]{ "NO_OPTIONS" };

                Widget[] children = options.getChildren();
                if (children == null || children.length == 0) return new Object[]{ "NO_CHILDREN" };
                if (idx > children.length) return new Object[]{ "OUT_OF_RANGE" };

                Widget target = children[idx - 1];
                java.awt.Rectangle bounds = target.getBounds();
                if (bounds == null) return new Object[]{ "NO_BOUNDS" };

                return new Object[]{ "OK", new java.awt.Point((int) bounds.getCenterX(), (int) bounds.getCenterY()) };
            });
        }
        catch (Throwable t)
        {
            System.err.println("[ClaudeBot] Dialogue lookup failed: " + t.getMessage());
            return ActionResult.failure(ActionType.SELECT_DIALOGUE, "Lookup failed: " + t.getMessage());
        }

        String status = (String) lookupData[0];
        if (!"OK".equals(status))
        {
            return ActionResult.failure(ActionType.SELECT_DIALOGUE, "Dialogue: " + status);
        }

        // Phase 2: Click on background thread
        java.awt.Point point = (java.awt.Point) lookupData[1];
        human.moveAndClick(point.x, point.y);
        return ActionResult.success(ActionType.SELECT_DIALOGUE);
    }

    public static ActionResult executeContinue(Client client, HumanSimulator human, ClientThread clientThread)
    {
        // Phase 1: Widget lookup on client thread
        java.awt.Point point;
        try
        {
            point = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                // NPC dialogue continue button
                Widget continueWidget = client.getWidget(231, 5);
                if (continueWidget == null || continueWidget.isHidden())
                {
                    // Player dialogue continue button
                    continueWidget = client.getWidget(217, 5);
                }
                if (continueWidget == null || continueWidget.isHidden()) return null;

                java.awt.Rectangle bounds = continueWidget.getBounds();
                if (bounds == null) return null;
                return new java.awt.Point((int) bounds.getCenterX(), (int) bounds.getCenterY());
            });
        }
        catch (Throwable t)
        {
            System.err.println("[ClaudeBot] Continue dialogue lookup failed: " + t.getMessage());
            // Fallback: press space
            human.pressKey(java.awt.event.KeyEvent.VK_SPACE);
            return ActionResult.success(ActionType.CONTINUE_DIALOGUE);
        }

        // Phase 2: Click or press space on background thread
        if (point != null)
        {
            human.moveAndClick(point.x, point.y);
        }
        else
        {
            human.pressKey(java.awt.event.KeyEvent.VK_SPACE);
        }
        return ActionResult.success(ActionType.CONTINUE_DIALOGUE);
    }
}
