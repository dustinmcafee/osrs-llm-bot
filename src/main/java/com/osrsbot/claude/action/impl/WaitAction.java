package com.osrsbot.claude.action.impl;

import com.osrsbot.claude.action.ActionResult;
import com.osrsbot.claude.action.ActionType;
import com.osrsbot.claude.action.BotAction;

public class WaitAction
{
    public static ActionResult execute(BotAction action)
    {
        int ticks = action.getTicks();
        if (ticks <= 0) ticks = 1;

        try
        {
            Thread.sleep(ticks * 600L);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }

        return ActionResult.success(ActionType.WAIT);
    }
}
