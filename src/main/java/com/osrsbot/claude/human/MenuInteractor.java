package com.osrsbot.claude.human;

import com.osrsbot.claude.util.ClientThreadRunner;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.client.callback.ClientThread;

import java.awt.*;

public class MenuInteractor
{
    private static final int MENU_HEADER_HEIGHT = 22;
    private static final int MENU_ENTRY_HEIGHT = 15;
    private static final int MENU_OPEN_TIMEOUT_MS = 1500;
    private static final int MENU_POLL_INTERVAL_MS = 50;

    private final HumanSimulator human;

    public MenuInteractor(HumanSimulator human)
    {
        this.human = human;
    }

    /**
     * Right-clicks at the current mouse position, waits for the menu to open,
     * finds the specified option, moves to it visually, and invokes the action
     * via client.menuAction() for reliability (no pixel-position dependency).
     *
     * @param client       RuneLite client (for reading menu state)
     * @param clientThread RuneLite's client thread (for thread-safe API access)
     * @param option       The menu option text (e.g., "Chop down", "Bank", "Attack")
     * @param target       Optional target name to match (e.g., "Oak tree"). Pass null to match any target.
     * @return true if the option was found and invoked, false otherwise
     */
    public boolean rightClickSelect(Client client, ClientThread clientThread, String option, String target)
    {
        // Right-click to open menu
        human.rightClick();

        // Wait for menu to open (polls client.isMenuOpen() on client thread)
        if (!waitForMenuOpen(client, clientThread))
        {
            System.err.println("[ClaudeBot] Menu did not open within timeout");
            return false;
        }

        // Pause after menu opens — human reaction time to "read" the menu
        human.getTimingEngine().sleep(human.getTimingEngine().nextTickDelay());

        // Find the target entry, capture its action data and approximate screen position
        Object[] entryData;
        try
        {
            entryData = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                MenuEntry[] entries = client.getMenuEntries();

                // Log all menu entries for debugging
                System.out.println("[ClaudeBot] Menu has " + entries.length + " entries, looking for '" + option + "' / '" + target + "':");
                for (int i = entries.length - 1; i >= 0; i--)
                {
                    MenuEntry e = entries[i];
                    String eOpt = e.getOption();
                    String eTgt = stripColorTags(e.getTarget());
                    System.out.println("[ClaudeBot]   [" + i + "] " + eOpt + " / " + eTgt);
                }

                int entryIndex = findEntry(entries, option, target);
                if (entryIndex == -1)
                {
                    return null;
                }

                MenuEntry matched = entries[entryIndex];
                Point pos = getEntryScreenPosition(client, entries.length, entryIndex);

                System.out.println("[ClaudeBot] Menu: found '" + option + "' at index " + entryIndex
                    + " type=" + matched.getType()
                    + " id=" + matched.getIdentifier()
                    + " p0=" + matched.getParam0()
                    + " p1=" + matched.getParam1()
                    + " screenPos=(" + pos.x + "," + pos.y + ")");

                return new Object[]{
                    pos,
                    matched.getParam0(),
                    matched.getParam1(),
                    matched.getType(),
                    matched.getIdentifier(),
                    matched.getOption(),
                    matched.getTarget()
                };
            });
        }
        catch (Throwable t)
        {
            System.err.println("[ClaudeBot] Menu entry lookup failed: " + t.getMessage());
            dismissMenu(client);
            return false;
        }

        if (entryData == null)
        {
            System.err.println("[ClaudeBot] Menu option '" + option + "' not found for target '" + target + "'");
            dismissMenu(client);
            return false;
        }

        Point entryPos = (Point) entryData[0];
        int param0 = (int) entryData[1];
        int param1 = (int) entryData[2];
        MenuAction menuAction = (MenuAction) entryData[3];
        int identifier = (int) entryData[4];
        String matchedOption = (String) entryData[5];
        String matchedTarget = (String) entryData[6];

        // Move mouse visually toward the entry (for human appearance)
        human.moveMouse(entryPos.x, entryPos.y);
        human.getTimingEngine().sleep(human.getTimingEngine().nextClickDelay());

        // Fire the action via client API — reliable, no pixel-position dependency
        System.out.println("[ClaudeBot] Menu: invoking " + matchedOption + " via menuAction"
            + " (type=" + menuAction + " id=" + identifier + " p0=" + param0 + " p1=" + param1 + ")");

        clientThread.invokeLater(() -> {
            try
            {
                client.menuAction(
                    param0,         // param0
                    param1,         // param1
                    menuAction,     // type
                    identifier,     // identifier
                    -1,             // itemId
                    matchedOption,  // option
                    matchedTarget   // target
                );
            }
            catch (Throwable t)
            {
                System.err.println("[ClaudeBot] Menu menuAction FAILED on client thread: "
                    + t.getClass().getName() + ": " + t.getMessage());
            }
        });

        return true;
    }

    /**
     * Polls client.isMenuOpen() on the client thread until the menu appears or timeout.
     */
    private boolean waitForMenuOpen(Client client, ClientThread clientThread)
    {
        long deadline = System.currentTimeMillis() + MENU_OPEN_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline)
        {
            try
            {
                boolean isOpen = ClientThreadRunner.runOnClientThread(clientThread, () -> client.isMenuOpen());
                if (isOpen)
                {
                    return true;
                }
            }
            catch (Throwable t)
            {
                // Ignore and retry
            }
            human.getTimingEngine().sleep(MENU_POLL_INTERVAL_MS);
        }
        return false;
    }

    /**
     * Finds a menu entry by option text and optional target name.
     * Returns the array index of the matching entry, or -1 if not found.
     *
     * RuneLite menu entries are ordered bottom-to-top:
     *   entries[0] = Cancel (bottom of visual menu)
     *   entries[n-1] = top entry
     */
    private int findEntry(MenuEntry[] entries, String option, String target)
    {
        for (int i = entries.length - 1; i >= 0; i--)
        {
            MenuEntry entry = entries[i];
            String entryOption = entry.getOption();
            String entryTarget = stripColorTags(entry.getTarget());

            if (entryOption != null && entryOption.equalsIgnoreCase(option))
            {
                if (target == null || target.isEmpty())
                {
                    return i;
                }
                if (entryTarget != null && entryTarget.equalsIgnoreCase(target))
                {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * Calculates the approximate screen position (center of row) for a menu entry.
     * Used for visual mouse movement only — not relied upon for the actual action.
     */
    private Point getEntryScreenPosition(Client client, int totalEntries, int entryIndex)
    {
        int menuX = client.getMenuX();
        int menuY = client.getMenuY();
        int menuWidth = client.getMenuWidth();

        int visualRow = totalEntries - 1 - entryIndex;
        int entryY = menuY + MENU_HEADER_HEIGHT + (visualRow * MENU_ENTRY_HEIGHT) + (MENU_ENTRY_HEIGHT / 2);
        int entryX = menuX + (menuWidth / 2);

        return new Point(entryX, entryY);
    }

    /**
     * Dismisses an open menu by pressing Escape.
     */
    private void dismissMenu(Client client)
    {
        human.pressKey(java.awt.event.KeyEvent.VK_ESCAPE);
        human.getTimingEngine().sleep(100);
    }

    /**
     * Strips RuneLite color tags like <col=ffff00> from a string.
     */
    private String stripColorTags(String text)
    {
        if (text == null) return null;
        return text.replaceAll("<[^>]+>", "").trim();
    }
}
