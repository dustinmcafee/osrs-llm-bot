package com.osrsbot.claude.action.impl;

import com.osrsbot.claude.action.ActionResult;
import com.osrsbot.claude.action.ActionType;
import com.osrsbot.claude.action.BotAction;
import com.osrsbot.claude.human.HumanSimulator;
import com.osrsbot.claude.util.ClientThreadRunner;
import com.osrsbot.claude.util.ItemUtils;
import com.osrsbot.claude.util.NpcUtils;
import com.osrsbot.claude.util.ObjectUtils;
import net.runelite.api.*;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;

import java.awt.Point;
import java.awt.Rectangle;

/**
 * Casts a spell from the spellbook, optionally targeting an NPC.
 *
 * Phase 1 (client thread): Check if magic tab is open, find the spell widget
 * by searching spellbook widget children for matching spell name.
 * Phase 2 (background thread): Click the spell via humanized mouse movement.
 * If targeting an NPC, find and click the NPC afterward.
 */
public class CastSpellAction
{
    private static final int SPELLBOOK_GROUP_ID = 218;

    public static ActionResult execute(Client client, HumanSimulator human, NpcUtils npcUtils,
                                       ItemUtils itemUtils, ObjectUtils objectUtils,
                                       ClientThread clientThread, BotAction action)
    {
        String spellName = action.getName();
        if (spellName == null || spellName.isEmpty())
        {
            return ActionResult.failure(ActionType.CAST_SPELL, "No spell name provided");
        }

        // Phase 1: Check if magic tab is open on client thread
        Boolean tabOpen;
        try
        {
            tabOpen = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                Widget spellbook = client.getWidget(SPELLBOOK_GROUP_ID, 0);
                return spellbook != null && !spellbook.isHidden();
            });
        }
        catch (Throwable t)
        {
            return ActionResult.failure(ActionType.CAST_SPELL, "Spellbook check failed: " + t.getMessage());
        }

        // If magic tab isn't open, open it (F-key with widget-click fallback)
        if (!tabOpen)
        {
            OpenTabAction.ensureTab(client, human, clientThread, "spellbook");
        }

        // Find the spell widget on client thread
        Object[] spellData;
        try
        {
            spellData = ClientThreadRunner.runOnClientThread(clientThread, () ->
                findSpellWidget(client, spellName));
        }
        catch (Throwable t)
        {
            return ActionResult.failure(ActionType.CAST_SPELL, "Spell lookup failed: " + t.getMessage());
        }

        if (spellData == null)
        {
            return ActionResult.failure(ActionType.CAST_SPELL, "Spell not found: " + spellName);
        }

        Point spellPoint = (Point) spellData[0];
        if (spellPoint == null)
        {
            return ActionResult.failure(ActionType.CAST_SPELL, "Spell widget has no screen bounds: " + spellName);
        }

        // Phase 2: Click the spell widget with humanized mouse
        human.moveAndClick(spellPoint.x, spellPoint.y);
        human.shortPause();

        // If targeting an NPC, find and click it
        String targetNpc = action.getNpc();
        if (targetNpc != null && !targetNpc.isEmpty())
        {
            Object[] npcData;
            try
            {
                npcData = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                    NPC npc = npcUtils.findNearest(client, targetNpc);
                    if (npc == null) return null;

                    Point screenPoint = null;
                    if (npc.getCanvasTilePoly() != null)
                    {
                        Rectangle bounds = npc.getCanvasTilePoly().getBounds();
                        if (bounds != null)
                        {
                            screenPoint = new Point((int) bounds.getCenterX(), (int) bounds.getCenterY());
                        }
                    }
                    return new Object[]{ screenPoint };
                });
            }
            catch (Throwable t)
            {
                return ActionResult.failure(ActionType.CAST_SPELL, "NPC target lookup failed: " + t.getMessage());
            }

            if (npcData == null)
            {
                return ActionResult.failure(ActionType.CAST_SPELL, "Target NPC not found: " + targetNpc);
            }

            Point npcPoint = (Point) npcData[0];
            if (npcPoint != null)
            {
                human.moveAndClick(npcPoint.x, npcPoint.y);
                human.shortPause();
            }
            return ActionResult.success(ActionType.CAST_SPELL);
        }

        // If targeting an inventory item (High Alch, Enchant, Superheat, etc.)
        String targetItem = action.getItem();
        if (targetItem != null && !targetItem.isEmpty())
        {
            // Open inventory tab so the item is visible
            OpenTabAction.ensureTab(client, human, clientThread, "inventory");

            Point itemPoint;
            try
            {
                itemPoint = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                    Widget item = itemUtils.findInInventory(client, targetItem);
                    if (item == null) return null;
                    Rectangle bounds = item.getBounds();
                    if (bounds == null) return null;
                    return new Point((int) bounds.getCenterX(), (int) bounds.getCenterY());
                });
            }
            catch (Throwable t)
            {
                return ActionResult.failure(ActionType.CAST_SPELL, "Item target lookup failed: " + t.getMessage());
            }

            if (itemPoint == null)
            {
                return ActionResult.failure(ActionType.CAST_SPELL, "Target item not found: " + targetItem);
            }

            human.moveAndClick(itemPoint.x, itemPoint.y);
            human.shortPause();
            return ActionResult.success(ActionType.CAST_SPELL);
        }

        // If targeting a game object (rare: e.g. casting on specific objects)
        String targetObject = action.getObject();
        if (targetObject != null && !targetObject.isEmpty())
        {
            Point objPoint;
            try
            {
                objPoint = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                    TileObject obj = objectUtils.findNearest(client, targetObject);
                    if (obj == null) return null;
                    if (obj.getClickbox() == null) return null;
                    Rectangle bounds = obj.getClickbox().getBounds();
                    if (bounds == null) return null;
                    return new Point((int) bounds.getCenterX(), (int) bounds.getCenterY());
                });
            }
            catch (Throwable t)
            {
                return ActionResult.failure(ActionType.CAST_SPELL, "Object target lookup failed: " + t.getMessage());
            }

            if (objPoint == null)
            {
                return ActionResult.failure(ActionType.CAST_SPELL, "Target object not found: " + targetObject);
            }

            human.moveAndClick(objPoint.x, objPoint.y);
            human.shortPause();
            return ActionResult.success(ActionType.CAST_SPELL);
        }

        return ActionResult.success(ActionType.CAST_SPELL);
    }

    /**
     * Searches the spellbook widget group for a spell matching the given name.
     * Returns Object[]{ Point screenCenter } or null if not found.
     */
    private static Object[] findSpellWidget(Client client, String spellName)
    {
        // The spellbook has multiple children, each representing a spell
        // We search all children of the spellbook group
        for (int childIdx = 0; childIdx < 200; childIdx++)
        {
            Widget child = client.getWidget(SPELLBOOK_GROUP_ID, childIdx);
            if (child == null) continue;
            if (child.isHidden()) continue;

            // Check the widget's actions for "Cast <spell name>"
            String[] actions = child.getActions();
            if (actions != null)
            {
                for (String act : actions)
                {
                    if (act != null && act.toLowerCase().contains(spellName.toLowerCase()))
                    {
                        Rectangle bounds = child.getBounds();
                        if (bounds != null)
                        {
                            return new Object[]{
                                new Point((int) bounds.getCenterX(), (int) bounds.getCenterY())
                            };
                        }
                    }
                }
            }

            // Also check widget name/text
            String widgetName = child.getName();
            if (widgetName != null && widgetName.toLowerCase().contains(spellName.toLowerCase()))
            {
                Rectangle bounds = child.getBounds();
                if (bounds != null)
                {
                    return new Object[]{
                        new Point((int) bounds.getCenterX(), (int) bounds.getCenterY())
                    };
                }
            }
        }
        return null;
    }
}
