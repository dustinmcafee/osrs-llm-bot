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
import java.awt.event.KeyEvent;
import java.util.HashMap;
import java.util.Map;

/**
 * Opens a game tab. Tries F-key first (fast), then verifies the tab actually
 * changed. If it didn't (e.g. Tutorial Island requires clicking the widget),
 * falls back to clicking the actual tab button widget.
 */
public class OpenTabAction
{
    // F-key bindings (F1-F7 always bound by default in OSRS)
    private static final Map<String, Integer> FKEY_TABS = new HashMap<>();
    static
    {
        FKEY_TABS.put("combat", KeyEvent.VK_F1);
        FKEY_TABS.put("skills", KeyEvent.VK_F2);
        FKEY_TABS.put("stats", KeyEvent.VK_F2);
        FKEY_TABS.put("quest", KeyEvent.VK_F3);
        FKEY_TABS.put("quests", KeyEvent.VK_F3);
        FKEY_TABS.put("inventory", KeyEvent.VK_F4);
        FKEY_TABS.put("equipment", KeyEvent.VK_F5);
        FKEY_TABS.put("prayer", KeyEvent.VK_F6);
        FKEY_TABS.put("spellbook", KeyEvent.VK_F7);
        FKEY_TABS.put("magic", KeyEvent.VK_F7);
    }

    // Expected varc 171 values for each tab (used to verify F-key worked)
    private static final Map<String, Integer> TAB_VARC = new HashMap<>();
    static
    {
        TAB_VARC.put("combat", 0);
        TAB_VARC.put("skills", 1);
        TAB_VARC.put("stats", 1);
        TAB_VARC.put("quest", 2);
        TAB_VARC.put("quests", 2);
        TAB_VARC.put("inventory", 3);
        TAB_VARC.put("equipment", 4);
        TAB_VARC.put("prayer", 5);
        TAB_VARC.put("spellbook", 6);
        TAB_VARC.put("magic", 6);
        TAB_VARC.put("clan", 7);
        TAB_VARC.put("clan chat", 7);
        TAB_VARC.put("friends", 8);
        TAB_VARC.put("friends list", 8);
        TAB_VARC.put("account", 9);
        TAB_VARC.put("account management", 9);
        TAB_VARC.put("logout", 10);
        TAB_VARC.put("settings", 11);
        TAB_VARC.put("emotes", 12);
        TAB_VARC.put("music", 13);
    }

    // Widget search terms for ALL tabs (used for widget-click fallback)
    private static final Map<String, String> WIDGET_SEARCH = new HashMap<>();
    static
    {
        WIDGET_SEARCH.put("combat", "Combat Options");
        WIDGET_SEARCH.put("skills", "Skills");
        WIDGET_SEARCH.put("stats", "Skills");
        WIDGET_SEARCH.put("quest", "Quest");
        WIDGET_SEARCH.put("quests", "Quest");
        WIDGET_SEARCH.put("inventory", "Inventory");
        WIDGET_SEARCH.put("equipment", "Worn Equipment");
        WIDGET_SEARCH.put("prayer", "Prayer");
        WIDGET_SEARCH.put("spellbook", "Magic");
        WIDGET_SEARCH.put("magic", "Magic");
        WIDGET_SEARCH.put("clan", "Clan Chat");
        WIDGET_SEARCH.put("clan chat", "Clan Chat");
        WIDGET_SEARCH.put("friends", "Friends List");
        WIDGET_SEARCH.put("friends list", "Friends List");
        WIDGET_SEARCH.put("account", "Account Management");
        WIDGET_SEARCH.put("account management", "Account Management");
        WIDGET_SEARCH.put("logout", "Logout");
        WIDGET_SEARCH.put("settings", "Settings");
        WIDGET_SEARCH.put("emotes", "Emotes");
        WIDGET_SEARCH.put("music", "Music Player");
    }

    // Widget groups that contain tab buttons
    private static final int[] TAB_WIDGET_GROUPS = { 548, 161, 164 };

    /**
     * Reusable helper for other actions that need a tab open before proceeding.
     * Tries F-key, verifies via varc 171, falls back to widget click.
     * Returns true if the tab was successfully opened (or was already open).
     */
    public static boolean ensureTab(Client client, HumanSimulator human, ClientThread clientThread, String tabKey)
    {
        String key = tabKey.toLowerCase().trim();
        Integer expectedVarc = TAB_VARC.get(key);
        if (expectedVarc == null) return false;

        // Check if already on the right tab
        try
        {
            int currentTab = ClientThreadRunner.runOnClientThread(clientThread,
                () -> client.getVarcIntValue(171));
            if (currentTab == expectedVarc) return true;
        }
        catch (Throwable t)
        {
            // Can't check, proceed with trying to open
        }

        // Try F-key
        Integer keyCode = FKEY_TABS.get(key);
        if (keyCode != null)
        {
            human.pressKey(keyCode);
            human.shortPause();

            try
            {
                int currentTab = ClientThreadRunner.runOnClientThread(clientThread,
                    () -> client.getVarcIntValue(171));
                if (currentTab == expectedVarc) return true;
            }
            catch (Throwable t)
            {
                // Fall through
            }
        }

        // Widget-click fallback
        String searchName = WIDGET_SEARCH.get(key);
        if (searchName == null) return false;

        try
        {
            final String target = searchName;
            Point tabPoint = ClientThreadRunner.runOnClientThread(clientThread, () ->
                findTabWidget(client, target));
            if (tabPoint != null)
            {
                human.moveAndClick(tabPoint.x, tabPoint.y);
                human.shortPause();
                return true;
            }
        }
        catch (Throwable t)
        {
            // Failed
        }
        return false;
    }

    public static ActionResult execute(Client client, HumanSimulator human, ClientThread clientThread, BotAction action)
    {
        String tabName = action.getName();
        if (tabName == null || tabName.isEmpty())
        {
            return ActionResult.failure(ActionType.OPEN_TAB, "No tab name specified");
        }

        String key = tabName.toLowerCase().trim();

        // Validate tab name
        if (!WIDGET_SEARCH.containsKey(key))
        {
            return ActionResult.failure(ActionType.OPEN_TAB,
                "Unknown tab: '" + tabName + "'. Use: combat, skills, quest, inventory, equipment, prayer, spellbook, clan, friends, account, logout, settings");
        }

        // Step 1: Try F-key if available
        Integer keyCode = FKEY_TABS.get(key);
        if (keyCode != null)
        {
            human.pressKey(keyCode);
            human.shortPause();

            // Verify the tab actually changed
            Integer expectedVarc = TAB_VARC.get(key);
            if (expectedVarc != null)
            {
                try
                {
                    int currentTab = ClientThreadRunner.runOnClientThread(clientThread,
                        () -> client.getVarcIntValue(171));
                    if (currentTab == expectedVarc)
                    {
                        return ActionResult.success(ActionType.OPEN_TAB);
                    }
                    // F-key didn't work — fall through to widget click
                    System.out.println("[ClaudeBot] F-key didn't change tab (expected varc " +
                        expectedVarc + ", got " + currentTab + "), falling back to widget click");
                }
                catch (Throwable t)
                {
                    // Can't verify, fall through to widget click
                }
            }
        }

        // Step 2: Widget-click fallback — works everywhere including Tutorial Island
        String searchName = WIDGET_SEARCH.get(key);
        Point tabPoint;
        try
        {
            final String target = searchName;
            tabPoint = ClientThreadRunner.runOnClientThread(clientThread, () ->
                findTabWidget(client, target));
        }
        catch (Throwable t)
        {
            return ActionResult.failure(ActionType.OPEN_TAB, "Tab widget lookup failed: " + t.getMessage());
        }

        if (tabPoint == null)
        {
            return ActionResult.failure(ActionType.OPEN_TAB, "Tab button not found: " + searchName);
        }

        human.moveAndClick(tabPoint.x, tabPoint.y);
        human.shortPause();
        return ActionResult.success(ActionType.OPEN_TAB);
    }

    /**
     * Searches tab widget groups for a button matching the tab name.
     * Looks in the game panel strip (group 548 for fixed, 161/164 for resizable).
     */
    private static Point findTabWidget(Client client, String tabName)
    {
        String lower = tabName.toLowerCase();

        for (int groupId : TAB_WIDGET_GROUPS)
        {
            for (int childIdx = 0; childIdx < 60; childIdx++)
            {
                Widget child = client.getWidget(groupId, childIdx);
                if (child == null || child.isHidden()) continue;

                // Check actions (tab buttons have actions like "Prayer", "Inventory", etc.)
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

                // Check widget tooltip/name
                String name = child.getName();
                if (name != null && name.toLowerCase().contains(lower))
                {
                    Rectangle bounds = child.getBounds();
                    if (bounds != null && bounds.width > 0)
                    {
                        return new Point((int) bounds.getCenterX(), (int) bounds.getCenterY());
                    }
                }
            }
        }
        return null;
    }
}
