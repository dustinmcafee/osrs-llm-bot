package com.osrsbot.claude.action.impl;

import com.osrsbot.claude.action.ActionResult;
import com.osrsbot.claude.action.ActionType;
import com.osrsbot.claude.action.BotAction;
import com.osrsbot.claude.human.HumanSimulator;

import java.awt.event.KeyEvent;

/**
 * Types text into the currently focused text field.
 * Used for: bank PIN entry, GE search, quantity prompts, dialogue text input.
 * Optionally presses Enter afterward if the "option" field is set to "enter".
 */
public class TypeTextAction
{
    public static ActionResult execute(HumanSimulator human, BotAction action)
    {
        String text = action.getText();
        if (text == null || text.isEmpty())
        {
            // Fall back to name field if text is not set
            text = action.getName();
        }
        if (text == null || text.isEmpty())
        {
            return ActionResult.failure(ActionType.TYPE_TEXT, "No text specified");
        }

        human.typeText(text);
        human.shortPause();

        // Press Enter if requested
        String option = action.getOption();
        if (option != null && option.equalsIgnoreCase("enter"))
        {
            human.pressKey(KeyEvent.VK_ENTER);
            human.shortPause();
        }

        return ActionResult.success(ActionType.TYPE_TEXT);
    }
}
