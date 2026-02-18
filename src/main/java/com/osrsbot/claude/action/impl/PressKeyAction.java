package com.osrsbot.claude.action.impl;

import com.osrsbot.claude.action.ActionResult;
import com.osrsbot.claude.action.ActionType;
import com.osrsbot.claude.action.BotAction;
import com.osrsbot.claude.human.HumanSimulator;

import java.awt.event.KeyEvent;
import java.util.HashMap;
import java.util.Map;

/**
 * Presses a keyboard key by name. Supports common keys like ESCAPE, ENTER, SPACE,
 * arrow keys, F-keys, and number/letter keys.
 */
public class PressKeyAction
{
    private static final Map<String, Integer> KEY_MAP = new HashMap<>();
    static
    {
        KEY_MAP.put("escape", KeyEvent.VK_ESCAPE);
        KEY_MAP.put("esc", KeyEvent.VK_ESCAPE);
        KEY_MAP.put("enter", KeyEvent.VK_ENTER);
        KEY_MAP.put("return", KeyEvent.VK_ENTER);
        KEY_MAP.put("space", KeyEvent.VK_SPACE);
        KEY_MAP.put("tab", KeyEvent.VK_TAB);
        KEY_MAP.put("backspace", KeyEvent.VK_BACK_SPACE);
        KEY_MAP.put("delete", KeyEvent.VK_DELETE);
        KEY_MAP.put("up", KeyEvent.VK_UP);
        KEY_MAP.put("down", KeyEvent.VK_DOWN);
        KEY_MAP.put("left", KeyEvent.VK_LEFT);
        KEY_MAP.put("right", KeyEvent.VK_RIGHT);
        KEY_MAP.put("shift", KeyEvent.VK_SHIFT);
        KEY_MAP.put("ctrl", KeyEvent.VK_CONTROL);
        KEY_MAP.put("alt", KeyEvent.VK_ALT);
        // F-keys
        for (int i = 1; i <= 12; i++)
        {
            KEY_MAP.put("f" + i, KeyEvent.VK_F1 + (i - 1));
        }
        // Number keys
        for (int i = 0; i <= 9; i++)
        {
            KEY_MAP.put(String.valueOf(i), KeyEvent.VK_0 + i);
        }
        // Letter keys
        for (char c = 'a'; c <= 'z'; c++)
        {
            KEY_MAP.put(String.valueOf(c), KeyEvent.VK_A + (c - 'a'));
        }
    }

    public static ActionResult execute(HumanSimulator human, BotAction action)
    {
        String keyName = action.getName();
        if (keyName == null || keyName.isEmpty())
        {
            return ActionResult.failure(ActionType.PRESS_KEY, "No key name specified");
        }

        String key = keyName.toLowerCase().trim();
        Integer keyCode = KEY_MAP.get(key);
        if (keyCode == null)
        {
            return ActionResult.failure(ActionType.PRESS_KEY,
                "Unknown key: '" + keyName + "'. Use: escape, enter, space, tab, up, down, left, right, f1-f12, 0-9, a-z");
        }

        human.pressKey(keyCode);
        human.shortPause();
        return ActionResult.success(ActionType.PRESS_KEY);
    }
}
