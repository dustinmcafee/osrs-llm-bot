package com.osrsbot.claude.brain;

import com.google.gson.*;
import com.osrsbot.claude.action.ActionType;
import com.osrsbot.claude.action.BotAction;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Singleton
public class ResponseParser
{
    private final Gson gson = new Gson();

    public List<BotAction> parse(String response)
    {
        List<BotAction> actions = new ArrayList<>();

        try
        {
            // Strip any markdown code fences that Claude might add despite instructions
            String cleaned = response.trim();
            if (cleaned.startsWith("```"))
            {
                cleaned = cleaned.replaceAll("```[a-z]*\\n?", "").replace("```", "").trim();
            }

            JsonArray array = JsonParser.parseString(cleaned).getAsJsonArray();

            for (JsonElement element : array)
            {
                JsonObject obj = element.getAsJsonObject();
                String actionStr = obj.get("action").getAsString();

                try
                {
                    ActionType type = ActionType.valueOf(actionStr);
                    BotAction action = new BotAction();
                    action.setType(type);
                    action.setRawJson(obj);

                    // Parse common parameters
                    if (obj.has("name")) action.setName(obj.get("name").getAsString());
                    if (obj.has("option")) action.setOption(obj.get("option").getAsString());
                    if (obj.has("x")) action.setX(obj.get("x").getAsInt());
                    if (obj.has("y")) action.setY(obj.get("y").getAsInt());
                    if (obj.has("ticks")) action.setTicks(obj.get("ticks").getAsInt());
                    if (obj.has("quantity")) action.setQuantity(obj.get("quantity").getAsInt());
                    if (obj.has("item")) action.setItem(obj.get("item").getAsString());
                    if (obj.has("item1")) action.setItem1(obj.get("item1").getAsString());
                    if (obj.has("item2")) action.setItem2(obj.get("item2").getAsString());
                    if (obj.has("npc")) action.setNpc(obj.get("npc").getAsString());
                    if (obj.has("object")) action.setObject(obj.get("object").getAsString());

                    actions.add(action);
                }
                catch (IllegalArgumentException e)
                {
                    System.err.println("[ClaudeBot] Unknown action type: " + actionStr);
                }
            }
        }
        catch (Throwable e)
        {
            System.err.println("[ClaudeBot] Failed to parse Claude response: " + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace(System.err);
            // Return a wait action on parse failure
            BotAction wait = new BotAction();
            wait.setType(ActionType.WAIT);
            wait.setTicks(5);
            actions.add(wait);
        }

        return actions;
    }
}
