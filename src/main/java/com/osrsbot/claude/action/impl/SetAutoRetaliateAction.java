package com.osrsbot.claude.action.impl;

import com.osrsbot.claude.action.ActionResult;
import com.osrsbot.claude.action.ActionType;
import com.osrsbot.claude.action.BotAction;
import com.osrsbot.claude.human.HumanSimulator;
import com.osrsbot.claude.util.ClientThreadRunner;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;

import java.awt.Point;
import java.awt.Rectangle;

/**
 * Toggles auto-retaliate on or off by clicking the button in the combat tab.
 *
 * VarP 172: 0 = auto-retaliate ON, 1 = auto-retaliate OFF.
 * The LLM sends option "on" or "off" to set a specific state, or no option to toggle.
 */
public class SetAutoRetaliateAction
{
    private static final int VARP_AUTO_RETALIATE = 172;

    public static ActionResult execute(Client client, HumanSimulator human, ClientThread clientThread, BotAction action)
    {
        String option = action.getOption();
        if (option == null || option.isEmpty())
        {
            option = action.getName();
        }

        // Determine desired state: true = ON, false = OFF, null = toggle
        Boolean desiredOn = null;
        if (option != null)
        {
            String lower = option.toLowerCase().trim();
            if ("on".equals(lower) || "enable".equals(lower) || "true".equals(lower) || "1".equals(lower))
            {
                desiredOn = true;
            }
            else if ("off".equals(lower) || "disable".equals(lower) || "false".equals(lower) || "0".equals(lower))
            {
                desiredOn = false;
            }
        }

        // Check current state
        boolean currentlyOn;
        try
        {
            currentlyOn = ClientThreadRunner.runOnClientThread(clientThread,
                () -> client.getVarpValue(VARP_AUTO_RETALIATE) == 0);
        }
        catch (Throwable t)
        {
            return ActionResult.failure(ActionType.SET_AUTO_RETALIATE, "Failed to read auto-retaliate state: " + t.getMessage());
        }

        // If already in desired state, no-op
        if (desiredOn != null && desiredOn == currentlyOn)
        {
            String state = currentlyOn ? "ON" : "OFF";
            return ActionResult.success(ActionType.SET_AUTO_RETALIATE, "Auto-retaliate already " + state);
        }

        // Open combat tab
        OpenTabAction.ensureTab(client, human, clientThread, "combat");

        // Find the retaliate button widget
        Point point;
        try
        {
            point = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                Widget retaliateBtn = client.getWidget(InterfaceID.CombatInterface.RETALIATE);
                if (retaliateBtn == null || retaliateBtn.isHidden()) return null;
                Rectangle bounds = retaliateBtn.getBounds();
                if (bounds == null || bounds.width == 0) return null;
                return new Point((int) bounds.getCenterX(), (int) bounds.getCenterY());
            });
        }
        catch (Throwable t)
        {
            return ActionResult.failure(ActionType.SET_AUTO_RETALIATE, "Widget lookup failed: " + t.getMessage());
        }

        if (point == null)
        {
            return ActionResult.failure(ActionType.SET_AUTO_RETALIATE, "Auto-retaliate button not visible (combat tab may not be open)");
        }

        // Click the button
        human.moveAndClick(point.x, point.y);

        // Wait a tick for state to update
        human.getTimingEngine().sleep(human.getTimingEngine().nextTickDelay());

        // Verify state changed
        try
        {
            boolean nowOn = ClientThreadRunner.runOnClientThread(clientThread,
                () -> client.getVarpValue(VARP_AUTO_RETALIATE) == 0);
            String state = nowOn ? "ON" : "OFF";
            if (desiredOn != null && desiredOn != nowOn)
            {
                return ActionResult.failure(ActionType.SET_AUTO_RETALIATE,
                    "Auto-retaliate is " + state + " but wanted " + (desiredOn ? "ON" : "OFF"));
            }
            return ActionResult.success(ActionType.SET_AUTO_RETALIATE, "Auto-retaliate " + state);
        }
        catch (Throwable t)
        {
            // Click happened, just can't verify
            return ActionResult.success(ActionType.SET_AUTO_RETALIATE);
        }
    }
}
