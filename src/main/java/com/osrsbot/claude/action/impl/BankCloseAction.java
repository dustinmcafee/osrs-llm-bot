package com.osrsbot.claude.action.impl;

import com.osrsbot.claude.action.ActionResult;
import com.osrsbot.claude.action.ActionType;
import com.osrsbot.claude.human.HumanSimulator;
import net.runelite.api.Client;

import java.awt.event.KeyEvent;

public class BankCloseAction
{
    public static ActionResult execute(Client client, HumanSimulator human)
    {
        // Most players close bank with Escape key
        human.pressKey(KeyEvent.VK_ESCAPE);
        return ActionResult.success(ActionType.BANK_CLOSE);
    }
}
