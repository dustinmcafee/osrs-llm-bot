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
 * Sets the combat attack style by clicking the appropriate button in the combat options tab.
 * Accepts style names like "Accurate", "Aggressive", "Controlled", "Defensive",
 * or weapon-specific names like "Chop", "Slash", "Lunge", "Block", "Longrange".
 */
public class SetAttackStyleAction
{
    private static final int COMBAT_OPTIONS_GROUP = 593;

    public static ActionResult execute(Client client, HumanSimulator human, ClientThread clientThread, BotAction action)
    {
        String styleName = action.getOption();
        if (styleName == null || styleName.isEmpty())
        {
            styleName = action.getName();
        }
        if (styleName == null || styleName.isEmpty())
        {
            return ActionResult.failure(ActionType.SET_ATTACK_STYLE, "No style name specified");
        }

        // Open combat tab first (F-key with widget-click fallback)
        OpenTabAction.ensureTab(client, human, clientThread, "combat");

        // Phase 1: Find the combat style widget by searching for matching text
        final String targetStyle = styleName;
        Point point;
        try
        {
            point = ClientThreadRunner.runOnClientThread(clientThread, () ->
                findStyleWidget(client, targetStyle));
        }
        catch (Throwable t)
        {
            System.err.println("[ClaudeBot] SetAttackStyle lookup failed: " + t.getMessage());
            return ActionResult.failure(ActionType.SET_ATTACK_STYLE, "Lookup failed: " + t.getMessage());
        }

        if (point == null)
        {
            return ActionResult.failure(ActionType.SET_ATTACK_STYLE, "Attack style not found: " + styleName);
        }

        // Phase 2: Click on background thread
        human.moveAndClick(point.x, point.y);
        return ActionResult.success(ActionType.SET_ATTACK_STYLE);
    }

    /**
     * Searches the combat options widget group for a child whose text or actions
     * match the requested combat style.
     */
    private static Point findStyleWidget(Client client, String styleName)
    {
        String lower = styleName.toLowerCase().trim();

        // Combat style buttons are typically children 4, 8, 12, 16 in group 593
        // But we search all children to be robust across weapon types
        for (int childIdx = 0; childIdx < 30; childIdx++)
        {
            Widget child = client.getWidget(COMBAT_OPTIONS_GROUP, childIdx);
            if (child == null || child.isHidden()) continue;

            // Check widget text (style name labels)
            String text = child.getText();
            if (text != null && text.toLowerCase().contains(lower))
            {
                Rectangle bounds = child.getBounds();
                if (bounds != null && bounds.width > 0)
                {
                    return new Point((int) bounds.getCenterX(), (int) bounds.getCenterY());
                }
            }

            // Check actions (like "Accurate", "Aggressive" etc.)
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

            // Also check nested children (style names may be in sub-widgets)
            // When found in a nested child, click the PARENT widget (the clickable button area)
            Widget[] nestedChildren = child.getChildren();
            if (nestedChildren != null)
            {
                for (Widget nested : nestedChildren)
                {
                    if (nested == null || nested.isHidden()) continue;
                    String nestedText = nested.getText();
                    if (nestedText != null && nestedText.toLowerCase().contains(lower))
                    {
                        // Use parent bounds — nested text labels are typically inside the button
                        // but the parent is the clickable area
                        Rectangle parentBounds = child.getBounds();
                        Rectangle nestedBounds = nested.getBounds();
                        Rectangle clickBounds = (nestedBounds != null && nestedBounds.width > 0) ? nestedBounds : parentBounds;
                        if (clickBounds != null && clickBounds.width > 0)
                        {
                            return new Point((int) clickBounds.getCenterX(), (int) clickBounds.getCenterY());
                        }
                    }
                }
            }
        }
        return null;
    }
}
