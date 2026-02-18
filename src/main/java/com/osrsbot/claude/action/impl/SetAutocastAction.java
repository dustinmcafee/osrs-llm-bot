package com.osrsbot.claude.action.impl;

import com.osrsbot.claude.action.ActionResult;
import com.osrsbot.claude.action.ActionType;
import com.osrsbot.claude.action.BotAction;
import com.osrsbot.claude.human.HumanSimulator;
import com.osrsbot.claude.util.ClientThreadRunner;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;

import java.awt.Point;
import java.awt.Rectangle;

/**
 * Sets the autocast spell by:
 * 1. Opening the combat tab
 * 2. Clicking the autocast button to open spell selection (widget group 593)
 * 3. Selecting the requested spell from the autocast interface (widget group 201)
 *
 * If option is "defensive", clicks the defensive autocast button instead.
 */
public class SetAutocastAction
{
    private static final int COMBAT_OPTIONS_GROUP = 593;
    private static final int AUTOCAST_GROUP = 201;

    public static ActionResult execute(Client client, HumanSimulator human, ClientThread clientThread, BotAction action)
    {
        String spellName = action.getName();
        if (spellName == null || spellName.isEmpty())
        {
            return ActionResult.failure(ActionType.SET_AUTOCAST, "No spell name specified");
        }

        boolean defensive = "defensive".equalsIgnoreCase(action.getOption());

        // Open combat tab (F-key with widget-click fallback)
        OpenTabAction.ensureTab(client, human, clientThread, "combat");

        // Phase 1a: Find and click the autocast button
        Point autocastBtn;
        try
        {
            autocastBtn = ClientThreadRunner.runOnClientThread(clientThread, () ->
                findAutocastButton(client, defensive));
        }
        catch (Throwable t)
        {
            return ActionResult.failure(ActionType.SET_AUTOCAST, "Autocast button lookup failed: " + t.getMessage());
        }

        if (autocastBtn == null)
        {
            return ActionResult.failure(ActionType.SET_AUTOCAST, "Autocast button not found in combat interface");
        }

        // Click autocast button to open spell selection
        human.moveAndClick(autocastBtn.x, autocastBtn.y);
        human.shortPause();
        human.shortPause(); // extra wait for interface to open

        // Phase 1b: Find the spell in the autocast interface
        final String targetSpell = spellName;
        Point spellPoint;
        try
        {
            spellPoint = ClientThreadRunner.runOnClientThread(clientThread, () ->
                findSpellInAutocast(client, targetSpell));
        }
        catch (Throwable t)
        {
            return ActionResult.failure(ActionType.SET_AUTOCAST, "Spell lookup failed: " + t.getMessage());
        }

        if (spellPoint == null)
        {
            return ActionResult.failure(ActionType.SET_AUTOCAST, "Spell not found in autocast list: " + spellName);
        }

        // Click the spell
        human.moveAndClick(spellPoint.x, spellPoint.y);
        return ActionResult.success(ActionType.SET_AUTOCAST);
    }

    /**
     * Finds the autocast or defensive-autocast button in the combat options interface.
     * Searches for widgets with actions containing "autocast" or "Choose spell".
     */
    private static Point findAutocastButton(Client client, boolean defensive)
    {
        String searchTerm = defensive ? "defensive" : "autocast";

        for (int childIdx = 0; childIdx < 40; childIdx++)
        {
            Widget child = client.getWidget(COMBAT_OPTIONS_GROUP, childIdx);
            if (child == null || child.isHidden()) continue;

            String[] actions = child.getActions();
            if (actions != null)
            {
                for (String act : actions)
                {
                    if (act != null && act.toLowerCase().contains(searchTerm))
                    {
                        Rectangle bounds = child.getBounds();
                        if (bounds != null && bounds.width > 0)
                        {
                            return new Point((int) bounds.getCenterX(), (int) bounds.getCenterY());
                        }
                    }
                }
            }
        }

        // Fallback: search for "Choose spell" if "autocast" wasn't found
        for (int childIdx = 0; childIdx < 40; childIdx++)
        {
            Widget child = client.getWidget(COMBAT_OPTIONS_GROUP, childIdx);
            if (child == null || child.isHidden()) continue;

            String[] actions = child.getActions();
            if (actions != null)
            {
                for (String act : actions)
                {
                    if (act != null && act.toLowerCase().contains("choose spell"))
                    {
                        Rectangle bounds = child.getBounds();
                        if (bounds != null && bounds.width > 0)
                        {
                            return new Point((int) bounds.getCenterX(), (int) bounds.getCenterY());
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Searches the autocast spell selection interface (group 201) for the requested spell.
     */
    private static Point findSpellInAutocast(Client client, String spellName)
    {
        String lower = spellName.toLowerCase().trim();

        for (int childIdx = 0; childIdx < 60; childIdx++)
        {
            Widget child = client.getWidget(AUTOCAST_GROUP, childIdx);
            if (child == null || child.isHidden()) continue;

            // Check actions (spell names appear in actions like "Fire Strike")
            String[] actions = child.getActions();
            if (actions != null)
            {
                for (String act : actions)
                {
                    if (act != null && act.toLowerCase().contains(lower))
                    {
                        Rectangle bounds = child.getBounds();
                        if (bounds != null && bounds.width > 0)
                        {
                            return new Point((int) bounds.getCenterX(), (int) bounds.getCenterY());
                        }
                    }
                }
            }

            // Check widget name
            String name = child.getName();
            if (name != null && name.toLowerCase().contains(lower))
            {
                Rectangle bounds = child.getBounds();
                if (bounds != null && bounds.width > 0)
                {
                    return new Point((int) bounds.getCenterX(), (int) bounds.getCenterY());
                }
            }

            // Check nested children
            Widget[] nested = child.getChildren();
            if (nested != null)
            {
                for (Widget n : nested)
                {
                    if (n == null || n.isHidden()) continue;
                    String[] nActions = n.getActions();
                    if (nActions != null)
                    {
                        for (String act : nActions)
                        {
                            if (act != null && act.toLowerCase().contains(lower))
                            {
                                Rectangle bounds = n.getBounds();
                                if (bounds != null && bounds.width > 0)
                                {
                                    return new Point((int) bounds.getCenterX(), (int) bounds.getCenterY());
                                }
                            }
                        }
                    }
                }
            }
        }
        return null;
    }
}
