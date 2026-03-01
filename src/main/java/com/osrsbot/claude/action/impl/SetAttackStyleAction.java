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
 * Sets the combat attack style by clicking the appropriate button in the combat options tab.
 * Uses RuneLite's ComponentID constants — no hardcoded widget child indices.
 *
 * The LLM sends a numeric index (0-3) to select the style slot.
 */
public class SetAttackStyleAction
{
    // Packed component IDs for each combat style button, from RuneLite's InterfaceID.
    private static final int[] STYLE_BUTTONS = {
        InterfaceID.CombatInterface._0,
        InterfaceID.CombatInterface._1,
        InterfaceID.CombatInterface._2,
        InterfaceID.CombatInterface._3
    };

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

        // Open combat tab first
        OpenTabAction.ensureTab(client, human, clientThread, "combat");

        final String targetStyle = styleName;
        Point point;
        String debugInfo;
        try
        {
            Object[] result = ClientThreadRunner.runOnClientThread(clientThread, () ->
                findStyleButton(client, targetStyle));
            point = (Point) result[0];
            debugInfo = (String) result[1];
        }
        catch (Throwable t)
        {
            System.err.println("[ClaudeBot] SetAttackStyle lookup failed: " + t.getMessage());
            return ActionResult.failure(ActionType.SET_ATTACK_STYLE, "Lookup failed: " + t.getMessage());
        }

        if (point == null)
        {
            System.out.println("[ClaudeBot] SetAttackStyle FAILED for '" + styleName + "'. Debug: " + debugInfo);
            return ActionResult.failure(ActionType.SET_ATTACK_STYLE,
                "Attack style not found: " + styleName + ". " + debugInfo);
        }

        human.moveAndClick(point.x, point.y);
        return ActionResult.success(ActionType.SET_ATTACK_STYLE);
    }

    private static Object[] findStyleButton(Client client, String styleName)
    {
        String lower = styleName.toLowerCase().trim();

        // Primary path: numeric index 0-3
        try
        {
            int index = Integer.parseInt(lower);
            if (index >= 0 && index < STYLE_BUTTONS.length)
            {
                Widget button = client.getWidget(STYLE_BUTTONS[index]);
                if (button != null && !button.isHidden())
                {
                    Point p = getClickableCenter(button);
                    if (p != null)
                    {
                        System.out.println("[ClaudeBot] SetAttackStyle: index " + index + " -> OK");
                        return new Object[]{p, ""};
                    }
                }
                return new Object[]{null, "Button " + index + " not visible (combat tab may not be open)"};
            }
            return new Object[]{null, "Index " + index + " out of range. Use 0-3."};
        }
        catch (NumberFormatException ignored) { }

        // Fallback: generic name -> index
        int index = genericStyleToIndex(lower);
        if (index >= 0)
        {
            Widget button = client.getWidget(STYLE_BUTTONS[index]);
            if (button != null && !button.isHidden())
            {
                Point p = getClickableCenter(button);
                if (p != null)
                {
                    System.out.println("[ClaudeBot] SetAttackStyle: '" + styleName + "' -> index " + index + " -> OK");
                    return new Object[]{p, ""};
                }
            }
            return new Object[]{null, "Button for '" + styleName + "' not visible"};
        }

        return new Object[]{null, "Unknown style '" + styleName + "'. Use index 0-3, or: accurate, aggressive, controlled, defensive, rapid, longrange"};
    }

    private static int genericStyleToIndex(String lower)
    {
        switch (lower)
        {
            case "accurate": return 0;
            case "aggressive": return 1;
            case "controlled": return 2;
            case "defensive": return 3;
            case "rapid": return 1;
            case "longrange":
            case "long range": return 2;
            default: return -1;
        }
    }

    private static Point getClickableCenter(Widget widget)
    {
        Rectangle bounds = widget.getBounds();
        if (bounds == null || bounds.width == 0) return null;
        return new Point((int) bounds.getCenterX(), (int) bounds.getCenterY());
    }
}
