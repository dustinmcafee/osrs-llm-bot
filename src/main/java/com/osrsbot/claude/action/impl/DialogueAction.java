package com.osrsbot.claude.action.impl;

import com.osrsbot.claude.action.ActionResult;
import com.osrsbot.claude.action.ActionType;
import com.osrsbot.claude.action.BotAction;
import com.osrsbot.claude.human.HumanSimulator;
import com.osrsbot.claude.util.ClientThreadRunner;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.callback.ClientThread;

public class DialogueAction
{
    public static ActionResult execute(Client client, HumanSimulator human, ClientThread clientThread, BotAction action)
    {
        String opt = action.getOption();

        // Phase 1: Widget lookup on client thread — support both numeric index and text match
        Object[] lookupData;
        try
        {
            lookupData = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                Widget options = client.getWidget(WidgetInfo.DIALOG_OPTION_OPTIONS);
                if (options == null || options.isHidden()) return new Object[]{ "NO_OPTIONS" };

                Widget[] children = options.getChildren();
                if (children == null || children.length == 0) return new Object[]{ "NO_CHILDREN" };

                // Try numeric index first
                if (opt != null && !opt.isEmpty())
                {
                    try
                    {
                        int idx = Integer.parseInt(opt.trim());
                        if (idx >= 1 && idx < children.length)
                        {
                            Widget target = children[idx];
                            java.awt.Rectangle bounds = target.getBounds();
                            if (bounds != null)
                            {
                                return new Object[]{ "OK", new java.awt.Point((int) bounds.getCenterX(), (int) bounds.getCenterY()) };
                            }
                        }
                    }
                    catch (NumberFormatException ignored) {}

                    // Text match: search option text (case-insensitive, partial match)
                    String lowerOpt = opt.toLowerCase().trim();
                    for (int i = 1; i < children.length; i++)
                    {
                        Widget child = children[i];
                        if (child == null || child.isHidden()) continue;
                        String text = child.getText();
                        if (text != null)
                        {
                            String stripped = text.replaceAll("<[^>]+>", "").trim().toLowerCase();
                            if (stripped.contains(lowerOpt) || lowerOpt.contains(stripped))
                            {
                                java.awt.Rectangle bounds = child.getBounds();
                                if (bounds != null)
                                {
                                    return new Object[]{ "OK", new java.awt.Point((int) bounds.getCenterX(), (int) bounds.getCenterY()) };
                                }
                            }
                        }
                    }
                }

                // Default: click first option
                if (children.length > 1)
                {
                    java.awt.Rectangle bounds = children[1].getBounds();
                    if (bounds != null)
                    {
                        return new Object[]{ "OK", new java.awt.Point((int) bounds.getCenterX(), (int) bounds.getCenterY()) };
                    }
                }

                return new Object[]{ "NO_MATCH" };
            });
        }
        catch (Throwable t)
        {
            System.err.println("[ClaudeBot] Dialogue lookup failed: " + t.getMessage());
            return ActionResult.failure(ActionType.SELECT_DIALOGUE, "Lookup failed: " + t.getMessage());
        }

        String status = (String) lookupData[0];
        if (!"OK".equals(status))
        {
            return ActionResult.failure(ActionType.SELECT_DIALOGUE, "Dialogue: " + status + " (option='" + opt + "')");
        }

        // Phase 2: Click on background thread
        java.awt.Point point = (java.awt.Point) lookupData[1];
        human.moveAndClick(point.x, point.y);
        return ActionResult.success(ActionType.SELECT_DIALOGUE);
    }

    // Continue button packed component IDs from RuneLite's InterfaceID.
    // Each dialog type has a specific CONTINUE widget.
    private static final int[] CONTINUE_WIDGETS = {
        InterfaceID.ChatLeft.CONTINUE,        // NPC dialogue (group 231, child 5)
        InterfaceID.ChatRight.CONTINUE,       // Player dialogue (group 217, child 5)
        InterfaceID.ChatBoth.CONTINUE,        // Both heads dialogue (group 60, child 6)
        InterfaceID.Messagebox.CONTINUE,      // Plain text message (group 229, child 4)
    };

    public static ActionResult executeContinue(Client client, HumanSimulator human, ClientThread clientThread)
    {
        // Phase 1: Search ALL dialog types for a continue button
        java.awt.Point point;
        try
        {
            point = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                // Try each known continue widget
                for (int packedId : CONTINUE_WIDGETS)
                {
                    Widget w = client.getWidget(packedId);
                    if (w != null && !w.isHidden())
                    {
                        java.awt.Rectangle bounds = w.getBounds();
                        if (bounds != null && bounds.width > 0)
                        {
                            return new java.awt.Point((int) bounds.getCenterX(), (int) bounds.getCenterY());
                        }
                    }
                }
                // Fallback: search for any widget with "Click here to continue" text
                int[][] searchGroups = {
                    {231, 10}, {217, 10}, {60, 10}, {229, 10}, {193, 10}, {11, 10},
                };
                for (int[] group : searchGroups)
                {
                    for (int childIdx = 0; childIdx < group[1]; childIdx++)
                    {
                        Widget child = client.getWidget(group[0], childIdx);
                        if (child == null || child.isHidden()) continue;
                        String text = child.getText();
                        if (text != null && text.toLowerCase().contains("click here to continue"))
                        {
                            java.awt.Rectangle bounds = child.getBounds();
                            if (bounds != null && bounds.width > 0)
                            {
                                return new java.awt.Point((int) bounds.getCenterX(), (int) bounds.getCenterY());
                            }
                        }
                    }
                }
                return null;
            });
        }
        catch (Throwable t)
        {
            System.err.println("[ClaudeBot] Continue dialogue lookup failed: " + t.getMessage());
            human.pressKey(java.awt.event.KeyEvent.VK_SPACE);
            return ActionResult.success(ActionType.CONTINUE_DIALOGUE);
        }

        // Phase 2: Click or press space on background thread
        if (point != null)
        {
            human.moveAndClick(point.x, point.y);
        }
        else
        {
            // Space bar fallback — works for most dialogs
            human.pressKey(java.awt.event.KeyEvent.VK_SPACE);
        }
        return ActionResult.success(ActionType.CONTINUE_DIALOGUE);
    }
}
