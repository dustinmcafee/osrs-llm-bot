package com.osrsbot.claude.action.impl;

import com.osrsbot.claude.action.ActionResult;
import com.osrsbot.claude.action.ActionType;
import com.osrsbot.claude.human.HumanSimulator;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.client.callback.ClientThread;

import java.awt.event.KeyEvent;

public class BankCloseAction
{
    private static final int VERIFY_POLL_MS = 100;
    private static final int VERIFY_TIMEOUT_MS = 1200;

    public static ActionResult execute(Client client, HumanSimulator human, ClientThread clientThread)
    {
        // Press Escape to close the bank
        human.pressKey(KeyEvent.VK_ESCAPE);

        // Wait for bank to actually close before returning
        long deadline = System.currentTimeMillis() + VERIFY_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline)
        {
            human.getTimingEngine().sleep(VERIFY_POLL_MS);
            try
            {
                boolean closed = com.osrsbot.claude.util.ClientThreadRunner.runOnClientThread(
                    clientThread, () -> client.getItemContainer(InventoryID.BANK) == null);
                if (closed) break;
            }
            catch (Throwable t)
            {
                // Ignore and retry
            }
        }

        return ActionResult.success(ActionType.BANK_CLOSE);
    }
}
