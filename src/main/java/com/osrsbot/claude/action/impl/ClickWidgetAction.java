package com.osrsbot.claude.action.impl;

import com.osrsbot.claude.action.ActionResult;
import com.osrsbot.claude.action.ActionType;
import com.osrsbot.claude.action.BotAction;
import com.osrsbot.claude.human.HumanSimulator;

/**
 * Clicks at raw screen coordinates (x, y) on the game canvas.
 * This is the universal fallback for any interface element Claude can see
 * in the screenshot but doesn't have a specific action type for.
 *
 * All interaction goes through HumanSimulator so the client sees
 * natural Bezier mouse movement and realistic click timing.
 */
public class ClickWidgetAction
{
    public static ActionResult execute(HumanSimulator human, BotAction action)
    {
        int x = action.getX();
        int y = action.getY();

        if (x <= 0 || y <= 0)
        {
            return ActionResult.failure(ActionType.CLICK_WIDGET, "Invalid coordinates: (" + x + "," + y + ")");
        }

        String option = action.getOption();
        if ("right".equalsIgnoreCase(option))
        {
            human.moveAndRightClick(x, y);
        }
        else
        {
            human.moveAndClick(x, y);
        }

        human.shortPause();
        return ActionResult.success(ActionType.CLICK_WIDGET);
    }
}
