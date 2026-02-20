package com.osrsbot.claude.action;

import com.google.gson.JsonObject;
import lombok.Data;

@Data
public class BotAction
{
    private ActionType type;
    private JsonObject rawJson;

    // Common params
    private String name;
    private String option;
    private int x;
    private int y;
    private int plane = -1; // -1 = use current player plane
    private int ticks;
    private int quantity;

    // Item-specific
    private String item;
    private String item1;
    private String item2;
    private String npc;
    private String object;

    // Text input
    private String text;
}
