package com.osrsbot.claude.human;

import com.osrsbot.claude.util.ClientThreadRunner;
import net.runelite.api.Client;
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
     * finds the specified option, moves to it, and clicks.
     *
     * @param client       RuneLite client (for reading menu state)
     * @param clientThread RuneLite's client thread (for thread-safe API access)
     * @param option       The menu option text (e.g., "Chop down", "Bank", "Attack")
     * @param target       Optional target name to match (e.g., "Oak tree"). Pass null to match any target.
     * @return true if the option was found and clicked, false otherwise
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

        // Small pause after menu opens (human reaction time)
        human.getTimingEngine().sleep(human.getTimingEngine().nextClickDelay());

        // Find the target entry and get its screen position (on client thread)
        Point entryPos;
        try
        {
            entryPos = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                MenuEntry[] entries = client.getMenuEntries();
                int entryIndex = findEntry(entries, option, target);

                if (entryIndex == -1)
                {
                    return null;
                }

                return getEntryScreenPosition(client, entries.length, entryIndex);
            });
        }
        catch (Throwable t)
        {
            System.err.println("[ClaudeBot] Menu entry lookup failed: " + t.getMessage());
            dismissMenu(client);
            return false;
        }

        if (entryPos == null)
        {
            System.err.println("[ClaudeBot] Menu option '" + option + "' not found for target '" + target + "'");
            dismissMenu(client);
            return false;
        }

        // Move to entry and click (on background thread)
        human.moveMouse(entryPos.x, entryPos.y);
        human.click();

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
     * Calculates the screen position (center of row) for a menu entry.
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
