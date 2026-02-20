package com.osrsbot.claude.brain;

import com.google.gson.*;
import com.osrsbot.claude.action.ActionType;
import com.osrsbot.claude.action.BotAction;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Singleton
public class ResponseParser
{
    private final Gson gson = new Gson();

    // Last extracted goal from Claude's response
    private volatile String lastGoal = null;

    // Parse errors from the most recent parse, fed back to the LLM
    private final List<String> parseErrors = new ArrayList<>();

    /**
     * Maps common LLM-invented action names to real ActionTypes + default options.
     * Small local models often use natural-language names instead of our exact enum values.
     */
    private static final Map<String, ActionAlias> ACTION_ALIASES = new HashMap<>();

    static
    {
        // Object interactions
        alias("CHOP_DOWN", ActionType.INTERACT_OBJECT, "Chop down");
        alias("CHOP", ActionType.INTERACT_OBJECT, "Chop down");
        alias("CUT", ActionType.INTERACT_OBJECT, "Chop down");
        alias("CUT_DOWN", ActionType.INTERACT_OBJECT, "Chop down");
        alias("MINE", ActionType.INTERACT_OBJECT, "Mine");
        alias("MINING", ActionType.INTERACT_OBJECT, "Mine");
        alias("FISH", ActionType.INTERACT_OBJECT, null);
        alias("FISHING", ActionType.INTERACT_OBJECT, null);
        alias("COOK", ActionType.INTERACT_OBJECT, "Cook");
        alias("SMELT", ActionType.INTERACT_OBJECT, "Smelt");
        alias("SMITH", ActionType.INTERACT_OBJECT, "Smith");
        alias("OPEN", ActionType.INTERACT_OBJECT, "Open");
        alias("CLOSE", ActionType.INTERACT_OBJECT, "Close");
        alias("CLIMB_UP", ActionType.INTERACT_OBJECT, "Climb-up");
        alias("CLIMB_DOWN", ActionType.INTERACT_OBJECT, "Climb-down");
        alias("CLIMB", ActionType.INTERACT_OBJECT, "Climb");
        alias("ENTER", ActionType.INTERACT_OBJECT, "Enter");
        alias("SEARCH", ActionType.INTERACT_OBJECT, "Search");
        alias("PRAY_AT", ActionType.INTERACT_OBJECT, "Pray-at");
        alias("BANK", ActionType.INTERACT_OBJECT, "Bank");

        // NPC interactions
        alias("ATTACK", ActionType.INTERACT_NPC, "Attack");
        alias("FIGHT", ActionType.INTERACT_NPC, "Attack");
        alias("TALK", ActionType.INTERACT_NPC, "Talk-to");
        alias("TALK_TO", ActionType.INTERACT_NPC, "Talk-to");
        alias("TRADE", ActionType.INTERACT_NPC, "Trade");
        alias("PICKPOCKET", ActionType.INTERACT_NPC, "Pickpocket");

        // Shorthand aliases
        alias("EAT", ActionType.EAT_FOOD, null);
        alias("DROP", ActionType.DROP_ITEM, null);
        alias("PICKUP", ActionType.PICKUP_ITEM, null);
        alias("PICK_UP", ActionType.PICKUP_ITEM, null);
        alias("TAKE", ActionType.PICKUP_ITEM, null);
        alias("EQUIP", ActionType.EQUIP_ITEM, null);
        alias("WEAR", ActionType.EQUIP_ITEM, null);
        alias("WIELD", ActionType.EQUIP_ITEM, null);
        alias("UNEQUIP", ActionType.UNEQUIP_ITEM, null);
        alias("REMOVE", ActionType.UNEQUIP_ITEM, null);
        alias("DEPOSIT", ActionType.BANK_DEPOSIT, null);
        alias("DEPOSIT_ITEM", ActionType.BANK_DEPOSIT, null);
        alias("WITHDRAW", ActionType.BANK_WITHDRAW, null);
        alias("WITHDRAW_ITEM", ActionType.BANK_WITHDRAW, null);
        alias("DEPOSIT_ALL", ActionType.BANK_DEPOSIT_ALL, null);
        alias("OPEN_BANK", ActionType.INTERACT_OBJECT, "Bank", "Bank booth");
        alias("USE_BANK", ActionType.INTERACT_OBJECT, "Bank", "Bank booth");
        alias("CLOSE_BANK", ActionType.BANK_CLOSE, null);
        alias("RUN", ActionType.TOGGLE_RUN, null);
        alias("WALK", ActionType.WALK_TO, null);
        alias("MOVE", ActionType.WALK_TO, null);
        alias("MOVE_TO", ActionType.WALK_TO, null);
        alias("GO_TO", ActionType.PATH_TO, null);
        alias("NAVIGATE", ActionType.PATH_TO, null);
        alias("TRAVEL", ActionType.PATH_TO, null);
        alias("WAIT_ANIM", ActionType.WAIT_ANIMATION, null);
        alias("WAIT_FOR_ANIMATION", ActionType.WAIT_ANIMATION, null);
    }

    private static void alias(String name, ActionType type, String defaultOption)
    {
        ACTION_ALIASES.put(name, new ActionAlias(type, defaultOption, null));
    }

    private static void alias(String name, ActionType type, String defaultOption, String defaultName)
    {
        ACTION_ALIASES.put(name, new ActionAlias(type, defaultOption, defaultName));
    }

    /**
     * Returns the goal extracted from the most recent parse, or null if none.
     */
    public String getLastGoal()
    {
        return lastGoal;
    }

    /**
     * Returns parse errors from the most recent parse, then clears them.
     * These should be fed back to the LLM as action results so it learns from mistakes.
     */
    public List<String> getAndClearParseErrors()
    {
        List<String> errors = new ArrayList<>(parseErrors);
        parseErrors.clear();
        return errors;
    }

    public List<BotAction> parse(String response)
    {
        List<BotAction> actions = new ArrayList<>();
        lastGoal = null;
        parseErrors.clear();

        try
        {
            String cleaned = extractJsonArray(response);
            JsonArray array = JsonParser.parseString(cleaned).getAsJsonArray();

            // Extract goal from first action object if present
            if (array.size() > 0 && array.get(0).isJsonObject())
            {
                JsonObject first = array.get(0).getAsJsonObject();
                if (first.has("goal"))
                {
                    lastGoal = first.get("goal").getAsString();
                }
            }

            for (JsonElement element : array)
            {
                if (!element.isJsonObject()) continue;

                JsonObject obj = element.getAsJsonObject();
                if (!obj.has("action")) continue;

                // Resolve action type: integer ID, exact string match, or alias
                ActionType type = null;
                ActionAlias resolvedAlias = null;
                JsonElement actionEl = obj.get("action");

                // Try integer ID first (e.g. "action": 3)
                if (actionEl.isJsonPrimitive() && actionEl.getAsJsonPrimitive().isNumber())
                {
                    type = ActionType.fromId(actionEl.getAsInt());
                    if (type == null)
                    {
                        String err = "PARSE_ERROR: Unknown action ID " + actionEl.getAsInt()
                            + " in " + obj + ". Use a valid action type name like INTERACT_OBJECT, INTERACT_NPC, etc.";
                        System.err.println("[ClaudeBot] " + err);
                        parseErrors.add(err);
                        continue;
                    }
                }
                else
                {
                    String actionStr = actionEl.getAsString()
                        .trim().toUpperCase().replaceAll("[^A-Z_0-9]", "");

                    // Try parsing string as integer (e.g. "action": "3")
                    try
                    {
                        int actionId = Integer.parseInt(actionStr);
                        type = ActionType.fromId(actionId);
                        if (type != null)
                        {
                            System.out.println("[ClaudeBot] Resolved action ID " + actionId + " -> " + type.name());
                        }
                    }
                    catch (NumberFormatException ignored) {}

                    if (type == null)
                    {
                        // Skip "goal" pseudo-actions that small models produce
                        if (actionStr.equals("GOAL"))
                        {
                            if (obj.has("value"))
                            {
                                lastGoal = obj.get("value").getAsString();
                            }
                            else if (obj.has("name"))
                            {
                                lastGoal = obj.get("name").getAsString();
                            }
                            else if (obj.has("text"))
                            {
                                lastGoal = obj.get("text").getAsString();
                            }
                            continue;
                        }

                        // Try exact enum match
                        try
                        {
                            type = ActionType.valueOf(actionStr);
                        }
                        catch (IllegalArgumentException e)
                        {
                            // Try alias map
                            resolvedAlias = ACTION_ALIASES.get(actionStr);
                            if (resolvedAlias != null)
                            {
                                type = resolvedAlias.type;
                                System.out.println("[ClaudeBot] Mapped alias " + actionStr + " -> " + type.name());
                            }
                            else
                            {
                                String err = "PARSE_ERROR: Unknown action \"" + actionEl.getAsString()
                                    + "\" in " + obj + ". Valid actions: INTERACT_OBJECT, INTERACT_NPC, PATH_TO, EAT_FOOD, PICKUP_ITEM, etc. "
                                    + "Use \"name\" for the target name and \"option\" for the verb (e.g. Mine, Attack, Pick). "
                                    + "Do NOT put the verb in the \"action\" field.";
                                System.err.println("[ClaudeBot] " + err);
                                parseErrors.add(err);
                                continue;
                            }
                        }
                    }
                }

                BotAction action = new BotAction();
                action.setType(type);
                action.setRawJson(obj);

                // Parse common parameters with robust type handling
                if (obj.has("name")) action.setName(safeString(obj, "name"));
                if (obj.has("option")) action.setOption(safeString(obj, "option"));
                if (obj.has("x")) action.setX(safeInt(obj, "x"));
                if (obj.has("y")) action.setY(safeInt(obj, "y"));
                if (obj.has("plane")) action.setPlane(safeInt(obj, "plane"));
                else if (obj.has("z")) action.setPlane(safeInt(obj, "z"));
                if (obj.has("ticks")) action.setTicks(safeInt(obj, "ticks"));
                if (obj.has("quantity")) action.setQuantity(safeQuantity(obj));
                if (obj.has("item")) action.setItem(safeString(obj, "item"));
                if (obj.has("item1")) action.setItem1(safeString(obj, "item1"));
                if (obj.has("item2")) action.setItem2(safeString(obj, "item2"));
                if (obj.has("npc")) action.setNpc(safeString(obj, "npc"));
                if (obj.has("object")) action.setObject(safeString(obj, "object"));
                if (obj.has("text")) action.setText(safeString(obj, "text"));
                if (obj.has("fleeing")) action.setFleeing(safeBoolean(obj, "fleeing"));

                // Apply defaults from alias if the model didn't specify them
                if (resolvedAlias != null)
                {
                    if (action.getOption() == null && resolvedAlias.defaultOption != null)
                    {
                        action.setOption(resolvedAlias.defaultOption);
                    }
                    if (action.getName() == null && resolvedAlias.defaultName != null)
                    {
                        action.setName(resolvedAlias.defaultName);
                    }
                }

                // Auto-correct action type when fields don't match the parsed type.
                // LLMs frequently confuse integer IDs (e.g. 14 for 19, 4 for 38).
                ActionType corrected = inferCorrectType(type, action, obj);
                if (corrected != type)
                {
                    String warning = "WARNING: You used action " + type.getId() + "(" + type.name()
                        + ") but the fields indicate " + corrected.getId() + "(" + corrected.name()
                        + "). Auto-corrected. Use " + corrected.getId() + " for " + corrected.name() + " next time.";
                    System.out.println("[ClaudeBot] " + warning);
                    action.setParseWarning(warning);
                    action.setType(corrected);
                    type = corrected;
                }

                // Auto-recover "object" field → "name" for INTERACT_OBJECT
                if (action.getName() == null && action.getObject() != null
                    && (type == ActionType.INTERACT_OBJECT))
                {
                    action.setName(action.getObject());
                    String fix = "Auto-fixed: used \"object\" field as \"name\" for INTERACT_OBJECT. Use \"name\" next time.";
                    System.out.println("[ClaudeBot] " + fix);
                    if (action.getParseWarning() == null) action.setParseWarning(fix);
                }

                // Validate required fields for key action types
                String validationError = validateRequiredFields(type, action);
                if (validationError != null)
                {
                    System.err.println("[ClaudeBot] " + validationError);
                    parseErrors.add(validationError);
                    continue;
                }

                // Extract goal from any action (not just the first)
                if (obj.has("goal") && lastGoal == null)
                {
                    lastGoal = obj.get("goal").getAsString();
                }

                actions.add(action);
            }
        }
        catch (Throwable e)
        {
            System.err.println("[ClaudeBot] Failed to parse response: " + e.getMessage());
            System.err.println("[ClaudeBot] Raw response: " + response);
            parseErrors.add("PARSE_ERROR: Could not parse your JSON response: " + e.getMessage()
                + ". Your response MUST contain a valid JSON array like [{\"action\":\"INTERACT_OBJECT\",\"name\":\"Potato\",\"option\":\"Pick\"}]");
            // Return a wait action on parse failure
            BotAction wait = new BotAction();
            wait.setType(ActionType.WAIT);
            wait.setTicks(5);
            actions.add(wait);
        }

        return actions;
    }

    /**
     * Extracts reasoning text from Claude's response (everything before the JSON array).
     * Returns null if there's no reasoning text.
     */
    public String extractReasoning(String response)
    {
        if (response == null) return null;
        String trimmed = response.trim();

        // Strip code fences first
        String cleaned = trimmed;
        if (cleaned.contains("```"))
        {
            cleaned = cleaned.replaceAll("```[a-zA-Z]*\\s*", "").replace("```", "").trim();
        }

        int arrayStart = cleaned.indexOf('[');
        if (arrayStart <= 0) return null;

        String reasoning = cleaned.substring(0, arrayStart).trim();
        return reasoning.isEmpty() ? null : reasoning;
    }

    /**
     * Extracts a JSON array from Claude's response, handling common formatting issues:
     * - Markdown code fences (```json ... ```)
     * - Text before/after the JSON array ("Here are the actions: [...]")
     * - Whitespace and newlines
     */
    private String extractJsonArray(String response)
    {
        String cleaned = response.trim();

        // Strip markdown code fences
        if (cleaned.contains("```"))
        {
            cleaned = cleaned.replaceAll("```[a-zA-Z]*\\s*", "").replace("```", "").trim();
        }

        // Find the JSON array boundaries
        int start = cleaned.indexOf('[');
        int end = cleaned.lastIndexOf(']');
        if (start >= 0 && end > start)
        {
            cleaned = cleaned.substring(start, end + 1);
        }

        // Sanitize common LLM JSON malformations
        cleaned = sanitizeJson(cleaned);

        return cleaned;
    }

    /**
     * Fixes common JSON malformations produced by local LLMs:
     * - Stray trailing quotes on numbers: "y":3305" → "y":3305
     * - Trailing commas before } or ]: [1,2,] → [1,2]
     * - Single quotes used as JSON delimiters: {'a':'b'} → {"a":"b"}
     * - Missing commas between objects: }{ → },{
     */
    private String sanitizeJson(String json)
    {
        // Fix stray trailing quote after bare numbers: "y":3305" → "y":3305
        // Requires ":<spaces> before digits (the closing quote of the JSON key + colon).
        // This prevents matching inside string values like "text":"coords: 3305"
        // because the colon there is NOT preceded by a double-quote.
        // ":<digits>" (valid string value) also won't match because the opening quote
        // between : and digits means -?\d+ can't start there.
        json = json.replaceAll("(\":\\s*)(-?\\d+)\"(\\s*[,}\\]])", "$1$2$3");

        // Fix stray trailing quote after true/false/null: "flag":true" → "flag":true
        json = json.replaceAll("(:\\s*(?:true|false|null))\"(\\s*[,}\\]])", "$1$2");

        // Fix trailing commas before ] or }
        json = json.replaceAll(",\\s*([}\\]])", "$1");

        // Fix missing comma between objects: }{  → },{
        json = json.replaceAll("}\\s*\\{", "},{");

        // Fix single-quoted JSON: {'action':'WAIT'} → {"action":"WAIT"}
        // Only applied if there are no double quotes at all (pure single-quote JSON from Python-style LLMs)
        if (!json.contains("\"") && json.contains("'"))
        {
            json = json.replace('\'', '"');
        }

        return json;
    }

    /**
     * Safely extract an integer from a JSON field, handling:
     * - Normal integers: "x":3285
     * - Strings containing integers: "x":"3285"
     * - Strings with stray characters: "x":"3285""
     * Returns 0 if the value can't be parsed.
     */
    private static int safeInt(JsonObject obj, String key)
    {
        try
        {
            return obj.get(key).getAsInt();
        }
        catch (Exception e)
        {
            // Fallback: extract digits (with optional leading minus) from the raw string
            try
            {
                String raw = obj.get(key).toString().replaceAll("[\"\\s]", "");
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("-?\\d+").matcher(raw);
                if (m.find())
                {
                    return Integer.parseInt(m.group());
                }
            }
            catch (Exception ignored) {}
            System.err.println("[ClaudeBot] Could not parse int for '" + key + "': " + obj.get(key));
            return 0;
        }
    }

    /**
     * Parses quantity field with special handling for string values like "All", "all".
     */
    private static int safeQuantity(JsonObject obj)
    {
        try
        {
            String raw = obj.get("quantity").getAsString().trim().toLowerCase();
            if ("all".equals(raw)) return -1;
        }
        catch (Exception ignored) {}
        return safeInt(obj, "quantity");
    }

    /**
     * Safely extract a string from a JSON field, handling non-string types.
     */
    private static String safeString(JsonObject obj, String key)
    {
        try
        {
            JsonElement el = obj.get(key);
            if (el.isJsonPrimitive())
            {
                return el.getAsString();
            }
            // For non-primitives, return the raw JSON string
            return el.toString();
        }
        catch (Exception e)
        {
            return obj.get(key).toString();
        }
    }

    /**
     * Safely extract a boolean from a JSON field, handling string values like "true"/"1"/"yes".
     */
    private static boolean safeBoolean(JsonObject obj, String key)
    {
        try
        {
            return obj.get(key).getAsBoolean();
        }
        catch (Exception e)
        {
            try
            {
                String raw = obj.get(key).getAsString().trim().toLowerCase();
                return "true".equals(raw) || "1".equals(raw) || "yes".equals(raw);
            }
            catch (Exception ignored) {}
            return false;
        }
    }

    /**
     * Detects when the LLM used the wrong integer ID but the fields clearly indicate
     * a different action type. Returns the corrected type, or the original if no
     * correction is needed.
     */
    private static ActionType inferCorrectType(ActionType parsed, BotAction action, JsonObject obj)
    {
        boolean hasQuantity = obj.has("quantity");
        boolean hasItem = obj.has("item") || obj.has("name");
        boolean hasXY = obj.has("x") && obj.has("y");
        boolean hasOption = obj.has("option");
        boolean hasNpc = obj.has("npc");
        boolean hasObject = obj.has("object");

        // Fields suggest BANK_WITHDRAW (19): has item/name + quantity, no x/y
        // Common confusion: SELECT_DIALOGUE (14), CONTINUE_DIALOGUE (15)
        if (hasItem && hasQuantity && !hasXY && !hasOption
            && (parsed == ActionType.SELECT_DIALOGUE || parsed == ActionType.CONTINUE_DIALOGUE
                || parsed == ActionType.WAIT))
        {
            return ActionType.BANK_WITHDRAW;
        }

        // BANK_CLOSE(20) with item+quantity → BANK_DEPOSIT(18).
        // LLMs confuse BANK_CLOSE(20) with BANK_DEPOSIT(18) since they're nearby IDs.
        // If you wanted BANK_WITHDRAW(19), you'd use 19 directly.
        if (parsed == ActionType.BANK_CLOSE && hasItem && hasQuantity && !hasXY)
        {
            return ActionType.BANK_DEPOSIT;
        }

        // Fields suggest BANK_WITHDRAW (19): same pattern with other wrong IDs
        // Only correct types that would NEVER have item+quantity together
        if (hasItem && hasQuantity && !hasXY
            && parsed != ActionType.BANK_WITHDRAW && parsed != ActionType.BANK_DEPOSIT
            && parsed != ActionType.BANK_CLOSE
            && parsed != ActionType.SHOP_BUY && parsed != ActionType.SHOP_SELL
            && parsed != ActionType.GE_BUY && parsed != ActionType.GE_SELL
            && parsed != ActionType.DROP_ITEM && parsed != ActionType.EAT_FOOD
            && parsed != ActionType.EQUIP_ITEM && parsed != ActionType.UNEQUIP_ITEM
            && parsed != ActionType.INTERACT_NPC && parsed != ActionType.INTERACT_OBJECT
            && parsed != ActionType.USE_ITEM && parsed != ActionType.USE_ITEM_ON_ITEM
            && parsed != ActionType.USE_ITEM_ON_NPC && parsed != ActionType.USE_ITEM_ON_OBJECT
            && parsed != ActionType.CAST_SPELL && parsed != ActionType.MAKE_ITEM
            && parsed != ActionType.PICKUP_ITEM)
        {
            return ActionType.BANK_WITHDRAW;
        }

        // Fields suggest PATH_TO (38): has x/y coordinates, no name/option
        // Common confusion: USE_ITEM (4), WALK_TO (1) with long distances
        if (hasXY && !hasItem && !hasOption && !hasNpc && !hasObject
            && parsed == ActionType.USE_ITEM)
        {
            return ActionType.PATH_TO;
        }

        return parsed;
    }

    /**
     * Validates that an action has the required fields.
     * Returns an error message if validation fails, null if OK.
     */
    private static String validateRequiredFields(ActionType type, BotAction action)
    {
        switch (type)
        {
            case INTERACT_OBJECT:
                if (action.getName() == null || action.getName().isEmpty())
                {
                    return "PARSE_ERROR: INTERACT_OBJECT requires \"name\" (the object name from [NEARBY_OBJECTS]) "
                        + "and \"option\" (the verb like Mine, Pick, Bank, Chop down). "
                        + "Example: {\"action\":\"INTERACT_OBJECT\",\"name\":\"Potato\",\"option\":\"Pick\"}";
                }
                // Missing option is allowed — action handler will query available options and return them
                break;
            case INTERACT_NPC:
                if (action.getName() == null || action.getName().isEmpty())
                {
                    return "PARSE_ERROR: INTERACT_NPC requires \"name\" (the NPC name from [NEARBY_NPCS]) "
                        + "and \"option\" (the verb like Attack, Talk-to, Bank). "
                        + "Example: {\"action\":\"INTERACT_NPC\",\"name\":\"Goblin\",\"option\":\"Attack\"}";
                }
                // Missing option is allowed — action handler will query available options and return them
                break;
            case EAT_FOOD:
                if (action.getName() == null || action.getName().isEmpty())
                {
                    return "PARSE_ERROR: EAT_FOOD requires \"name\" (the food item name from your inventory). "
                        + "Example: {\"action\":\"EAT_FOOD\",\"name\":\"Lobster\"}";
                }
                break;
            case PICKUP_ITEM:
                if (action.getName() == null || action.getName().isEmpty())
                {
                    return "PARSE_ERROR: PICKUP_ITEM requires \"name\" (item name from [NEARBY_GROUND_ITEMS]). "
                        + "For objects like Potato/Cabbage that show in [NEARBY_OBJECTS], use INTERACT_OBJECT with option \"Pick\" instead.";
                }
                break;
            case PATH_TO:
                if (action.getX() == 0 && action.getY() == 0)
                {
                    return "PARSE_ERROR: PATH_TO requires \"x\" and \"y\" coordinates. "
                        + "Example: {\"action\":\"PATH_TO\",\"x\":3200,\"y\":3200}";
                }
                break;
            case BANK_WITHDRAW:
            case BANK_DEPOSIT:
                if (action.getName() == null || action.getName().isEmpty())
                {
                    return "PARSE_ERROR: " + type.name() + " requires \"name\" and \"quantity\". "
                        + "Example: {\"action\":\"" + type.name() + "\",\"name\":\"Lobster\",\"quantity\":5}";
                }
                break;
            default:
                break;
        }
        return null;
    }

    private static class ActionAlias
    {
        final ActionType type;
        final String defaultOption;
        final String defaultName;

        ActionAlias(ActionType type, String defaultOption, String defaultName)
        {
            this.type = type;
            this.defaultOption = defaultOption;
            this.defaultName = defaultName;
        }
    }
}
