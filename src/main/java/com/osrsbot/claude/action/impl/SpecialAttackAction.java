package com.osrsbot.claude.action.impl;

import com.osrsbot.claude.action.ActionResult;
import com.osrsbot.claude.action.ActionType;
import com.osrsbot.claude.human.HumanSimulator;
import com.osrsbot.claude.util.ClientThreadRunner;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;

public class SpecialAttackAction
{
    public static ActionResult execute(Client client, HumanSimulator human, ClientThread clientThread)
    {
        // Try minimap spec orb button first (always visible regardless of tab)
        java.awt.Point point;
        try
        {
            point = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                Widget specButton = client.getWidget(InterfaceID.Orbs.SPECBUTTON);
                if (specButton != null && !specButton.isHidden())
                {
                    java.awt.Rectangle bounds = specButton.getBounds();
                    if (bounds != null)
                    {
                        return new java.awt.Point((int) bounds.getCenterX(), (int) bounds.getCenterY());
                    }
                }
                return null;
            });
        }
        catch (Throwable t)
        {
            point = null;
        }

        // Fallback: open combat tab and click the special attack bar
        if (point == null)
        {
            OpenTabAction.ensureTab(client, human, clientThread, "combat");
            try
            {
                point = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                    Widget specBar = client.getWidget(InterfaceID.CombatInterface.SPECIAL_ATTACK);
                    if (specBar == null || specBar.isHidden()) return null;
                    java.awt.Rectangle bounds = specBar.getBounds();
                    if (bounds == null) return null;
                    return new java.awt.Point((int) bounds.getCenterX(), (int) bounds.getCenterY());
                });
            }
            catch (Throwable t)
            {
                return ActionResult.failure(ActionType.SPECIAL_ATTACK, "Spec bar lookup failed: " + t.getMessage());
            }
        }

        if (point == null)
        {
            return ActionResult.failure(ActionType.SPECIAL_ATTACK, "Special attack bar not visible");
        }

        human.moveAndClick(point.x, point.y);
        return ActionResult.success(ActionType.SPECIAL_ATTACK);
    }
}
