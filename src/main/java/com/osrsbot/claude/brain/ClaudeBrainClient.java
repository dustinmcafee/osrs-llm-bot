package com.osrsbot.claude.brain;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.*;
import com.google.gson.*;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Slf4j
@Singleton
public class ClaudeBrainClient
{
    @Inject
    private com.osrsbot.claude.ClaudeBotConfig config;

    private AnthropicClient anthropicClient;
    private ExecutorService executor;
    private String model;
    private int maxTokens;
    private String systemPrompt;
    private boolean logApiCalls;

    /** If non-empty, use OpenAI-compatible HTTP endpoint instead of Anthropic SDK */
    private String apiBaseUrl;
    private String openAiApiKey;

    public void initialize(String apiKey, String model, int maxTokens, String apiBaseUrl)
    {
        this.model = model;
        this.maxTokens = maxTokens;
        this.apiBaseUrl = (apiBaseUrl != null) ? apiBaseUrl.trim() : "";

        if (this.apiBaseUrl.isEmpty())
        {
            // Anthropic SDK mode
            this.anthropicClient = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .build();
        }
        else
        {
            // OpenAI-compatible mode — no SDK needed
            this.anthropicClient = null;
            this.openAiApiKey = (apiKey != null) ? apiKey.trim() : "";
            // Strip trailing slash for clean URL building
            if (this.apiBaseUrl.endsWith("/"))
            {
                this.apiBaseUrl = this.apiBaseUrl.substring(0, this.apiBaseUrl.length() - 1);
            }
        }

        // Recreate executor in case shutdown() was called previously (plugin toggle)
        if (executor == null || executor.isShutdown())
        {
            executor = Executors.newSingleThreadExecutor();
        }
    }

    /**
     * Backwards-compatible overload for Anthropic-only usage.
     */
    public void initialize(String apiKey, String model, int maxTokens)
    {
        initialize(apiKey, model, maxTokens, "");
    }

    public void setSystemPrompt(String systemPrompt)
    {
        this.systemPrompt = systemPrompt;
    }

    public void setLogApiCalls(boolean logApiCalls)
    {
        this.logApiCalls = logApiCalls;
    }

    public CompletableFuture<String> queryAsync(List<ConversationManager.Exchange> conversationHistory, String currentState)
    {
        return CompletableFuture.supplyAsync(() -> query(conversationHistory, currentState), executor);
    }

    private static final String FORMAT_CORRECTION =
        "[FORMAT_ERROR] Your response could not be parsed.\n\n"
        + "RULES:\n"
        + "1. Respond with a JSON array of action objects.\n"
        + "2. Each object MUST have an \"action\" field with one of the EXACT names below.\n"
        + "3. Number values must be bare integers (no quotes): \"x\":3208 NOT \"x\":\"3208\"\n"
        + "4. String values must be quoted: \"name\":\"Oak tree\"\n"
        + "5. Set a \"goal\" on the FIRST action to describe your plan.\n\n"
        + "ALL VALID ACTIONS AND THEIR PARAMETERS:\n\n"
        + "Movement:\n"
        + "  {\"action\":\"PATH_TO\",\"x\":3208,\"y\":3220} — walk to coordinates (handles doors/stairs)\n"
        + "  {\"action\":\"WALK_TO\",\"x\":3208,\"y\":3220} — click-to-walk (short distances only)\n"
        + "  {\"action\":\"MINIMAP_WALK\",\"x\":3208,\"y\":3220} — click minimap\n\n"
        + "Objects & NPCs:\n"
        + "  {\"action\":\"INTERACT_OBJECT\",\"name\":\"Oak tree\",\"option\":\"Chop down\"}\n"
        + "  {\"action\":\"INTERACT_NPC\",\"name\":\"Banker\",\"option\":\"Bank\"}\n\n"
        + "Items:\n"
        + "  {\"action\":\"USE_ITEM\",\"name\":\"Bones\",\"option\":\"Bury\"}\n"
        + "  {\"action\":\"DROP_ITEM\",\"name\":\"Bones\"}\n"
        + "  {\"action\":\"PICKUP_ITEM\",\"name\":\"Bones\"}\n"
        + "  {\"action\":\"EQUIP_ITEM\",\"name\":\"Bronze sword\"}\n"
        + "  {\"action\":\"UNEQUIP_ITEM\",\"name\":\"Bronze sword\"}\n"
        + "  {\"action\":\"EAT_FOOD\",\"name\":\"Shrimps\"}\n"
        + "  {\"action\":\"USE_ITEM_ON_ITEM\",\"item1\":\"Knife\",\"item2\":\"Logs\"}\n"
        + "  {\"action\":\"USE_ITEM_ON_NPC\",\"item\":\"Coins\",\"npc\":\"Shopkeeper\"}\n"
        + "  {\"action\":\"USE_ITEM_ON_OBJECT\",\"item\":\"Ore\",\"object\":\"Furnace\"}\n\n"
        + "Banking:\n"
        + "  {\"action\":\"BANK_DEPOSIT\",\"name\":\"Oak logs\",\"quantity\":-1} — -1 for all\n"
        + "  {\"action\":\"BANK_WITHDRAW\",\"name\":\"Tinderbox\",\"quantity\":1}\n"
        + "  {\"action\":\"BANK_DEPOSIT_ALL\"}\n"
        + "  {\"action\":\"BANK_CLOSE\"}\n\n"
        + "Combat & Settings:\n"
        + "  {\"action\":\"TOGGLE_RUN\"}\n"
        + "  {\"action\":\"TOGGLE_PRAYER\",\"name\":\"Protect from Melee\"}\n"
        + "  {\"action\":\"SPECIAL_ATTACK\"}\n"
        + "  {\"action\":\"SET_ATTACK_STYLE\",\"name\":\"Accurate\"}\n"
        + "  {\"action\":\"SET_AUTOCAST\",\"name\":\"Fire Strike\"}\n\n"
        + "Dialogue:\n"
        + "  {\"action\":\"SELECT_DIALOGUE\",\"option\":\"Yes\"}\n"
        + "  {\"action\":\"CONTINUE_DIALOGUE\"}\n\n"
        + "UI & Input:\n"
        + "  {\"action\":\"CLICK_WIDGET\",\"name\":\"widget description\"}\n"
        + "  {\"action\":\"OPEN_TAB\",\"name\":\"inventory\"}\n"
        + "  {\"action\":\"TYPE_TEXT\",\"text\":\"hello\"}\n"
        + "  {\"action\":\"PRESS_KEY\",\"name\":\"space\"}\n"
        + "  {\"action\":\"ROTATE_CAMERA\",\"x\":0} — 0=north, 512=west, 1024=south, 1536=east\n\n"
        + "Magic:\n"
        + "  {\"action\":\"CAST_SPELL\",\"name\":\"High Level Alchemy\",\"item\":\"Gold bar\"}\n\n"
        + "Shops & GE:\n"
        + "  {\"action\":\"SHOP_BUY\",\"name\":\"Bronze pickaxe\",\"quantity\":1}\n"
        + "  {\"action\":\"SHOP_SELL\",\"name\":\"Raw shrimps\",\"quantity\":1}\n"
        + "  {\"action\":\"GE_BUY\",\"name\":\"Iron ore\",\"quantity\":100}\n"
        + "  {\"action\":\"GE_SELL\",\"name\":\"Iron bar\",\"quantity\":100}\n\n"
        + "Crafting:\n"
        + "  {\"action\":\"MAKE_ITEM\",\"name\":\"Iron dagger\"}\n\n"
        + "Other:\n"
        + "  {\"action\":\"WAIT\",\"ticks\":5}\n"
        + "  {\"action\":\"WORLD_HOP\",\"x\":301} — x is the world number\n\n"
        + "EXAMPLE FULL RESPONSE:\n"
        + "[{\"action\":\"INTERACT_OBJECT\",\"name\":\"Oak tree\",\"option\":\"Chop down\","
        + "\"goal\":\"Chop oaks and bank logs\"}]\n\n"
        + "Try again now. Output ONLY the JSON array.";

    private String query(List<ConversationManager.Exchange> conversationHistory, String currentState)
    {
        if (logApiCalls)
        {
            logRequest(conversationHistory, currentState);
        }

        try
        {
            String responseText = doQuery(conversationHistory, currentState);

            if (logApiCalls)
            {
                logResponse(responseText);
            }

            // Validate response has correct format (JSON objects with "action" fields)
            if (needsFormatCorrection(responseText))
            {
                System.out.println("[ClaudeBot] Response not in expected format, retrying with correction");

                // Build retry history: original conversation + the bad exchange
                java.util.List<ConversationManager.Exchange> retryHistory =
                    new java.util.ArrayList<>(conversationHistory);
                retryHistory.add(new ConversationManager.Exchange(currentState, responseText));

                responseText = doQuery(retryHistory, FORMAT_CORRECTION);

                if (logApiCalls)
                {
                    logResponse(responseText);
                }
            }

            return responseText;
        }
        catch (Throwable e)
        {
            System.err.println("[ClaudeBot] API error: " + e.getClass().getName() + ": " + e.getMessage());
            if (logApiCalls)
            {
                System.err.println("╔══════════════════════════════════════════════════════════════\n"
                    + "║  API ERROR: " + e.getClass().getName() + "\n"
                    + "║  " + e.getMessage() + "\n"
                    + "╚══════════════════════════════════════════════════════════════");
            }
            return "[{\"action\":\"WAIT\",\"ticks\":5}]";
        }
    }

    /**
     * Checks if the response needs format correction by validating:
     * 1. Response contains "action" (basic structural check)
     * 2. Response contains a JSON array with at least one object
     * 3. The JSON is parseable (no fatal structural errors that sanitizeJson won't fix)
     */
    private boolean needsFormatCorrection(String responseText)
    {
        if (responseText == null || responseText.isEmpty()) return true;

        // Must contain "action" somewhere
        if (!responseText.contains("\"action\"")) return true;

        // Must contain JSON array markers
        if (!responseText.contains("[") || !responseText.contains("]")) return true;

        // Must contain at least one JSON object
        if (!responseText.contains("{") || !responseText.contains("}")) return true;

        return false;
    }

    /**
     * Sends the actual API request to either Anthropic or OpenAI-compatible endpoint.
     */
    private String doQuery(List<ConversationManager.Exchange> conversationHistory, String currentState)
        throws Exception
    {
        if (apiBaseUrl != null && !apiBaseUrl.isEmpty())
        {
            return queryOpenAI(conversationHistory, currentState);
        }
        else
        {
            return queryAnthropic(conversationHistory, currentState);
        }
    }

    /**
     * Query using the Anthropic Java SDK (original path).
     */
    private String queryAnthropic(List<ConversationManager.Exchange> conversationHistory, String currentState)
    {
        java.util.List<MessageParam> messages = new java.util.ArrayList<>();

        for (ConversationManager.Exchange exchange : conversationHistory)
        {
            messages.add(MessageParam.builder()
                .role(MessageParam.Role.USER)
                .content(exchange.getUserMessage())
                .build());
            messages.add(MessageParam.builder()
                .role(MessageParam.Role.ASSISTANT)
                .content(exchange.getAssistantMessage())
                .build());
        }

        messages.add(MessageParam.builder()
            .role(MessageParam.Role.USER)
            .content(currentState)
            .build());

        MessageCreateParams params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(maxTokens)
            .system(systemPrompt)
            .messages(messages)
            .build();

        Message response = anthropicClient.messages().create(params);

        return response.content().stream()
            .filter(ContentBlock::isText)
            .map(block -> block.asText().text())
            .collect(Collectors.joining());
    }

    /**
     * Query using OpenAI-compatible HTTP endpoint (LM Studio, Ollama, vLLM, etc.)
     */
    private String queryOpenAI(List<ConversationManager.Exchange> conversationHistory, String currentState)
        throws IOException
    {
        // Build messages array
        JsonArray messages = new JsonArray();

        // System message
        JsonObject systemMsg = new JsonObject();
        systemMsg.addProperty("role", "system");
        systemMsg.addProperty("content", systemPrompt);
        messages.add(systemMsg);

        // Conversation history
        for (ConversationManager.Exchange exchange : conversationHistory)
        {
            JsonObject userMsg = new JsonObject();
            userMsg.addProperty("role", "user");
            userMsg.addProperty("content", exchange.getUserMessage());
            messages.add(userMsg);

            JsonObject assistantMsg = new JsonObject();
            assistantMsg.addProperty("role", "assistant");
            assistantMsg.addProperty("content", exchange.getAssistantMessage());
            messages.add(assistantMsg);
        }

        // Current state
        JsonObject currentMsg = new JsonObject();
        currentMsg.addProperty("role", "user");
        currentMsg.addProperty("content", currentState);
        messages.add(currentMsg);

        // Build request body
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", model);
        requestBody.add("messages", messages);
        requestBody.addProperty("max_tokens", maxTokens);

        // Sampling + reasoning controls. All three pulled from config each
        // request so the user can tune them in RuneLite without restarting.
        if (config != null)
        {
            requestBody.addProperty("temperature", config.temperature());

            boolean isLocalEndpoint = !apiBaseUrl.isEmpty()
                && (apiBaseUrl.startsWith("http://") || apiBaseUrl.contains("localhost") || apiBaseUrl.contains("127.0.0.1") || apiBaseUrl.matches(".*\\d+\\.\\d+\\.\\d+\\.\\d+.*"));

            if (isLocalEndpoint && config.enableThinking())
            {
                // Qwen 3 chat template reads enable_thinking from
                // chat_template_kwargs. LM Studio passes this through verbatim.
                // Cloud providers (Gemini, OpenRouter, etc.) reject this field.
                JsonObject ctk = new JsonObject();
                ctk.addProperty("enable_thinking", true);
                requestBody.add("chat_template_kwargs", ctk);
            }

            String effort = config.reasoningEffort() != null
                ? config.reasoningEffort().apiValue() : null;
            if (effort != null && isLocalEndpoint)
            {
                // reasoning_effort is LM Studio-specific — skip for cloud endpoints
                requestBody.addProperty("reasoning_effort", effort);
            }

            if (config.structuredOutput())
            {
                // JSON-schema-constrained output. LM Studio / OpenAI-compatible
                // endpoints feed this to a grammar-constrained sampler so
                // every emitted token is forced onto the action schema. The
                // model literally cannot produce invalid JSON, invent an
                // action name, or drop the "name" field on INTERACT_OBJECT.
                // The retry / auto-recover paths in ResponseParser remain in
                // place as defense-in-depth in case the endpoint silently
                // ignores the schema (older LM Studio builds, etc.).
                requestBody.add("response_format", buildActionResponseFormat());
            }
        }
        else
        {
            requestBody.addProperty("temperature", 0.4);
        }

        // Send HTTP request
        String endpoint = apiBaseUrl + "/chat/completions";
        byte[] bodyBytes = requestBody.toString().getBytes(StandardCharsets.UTF_8);

        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        if (openAiApiKey != null && !openAiApiKey.isEmpty())
        {
            conn.setRequestProperty("Authorization", "Bearer " + openAiApiKey);
        }
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(120000); // 2 min for slow local models
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream())
        {
            os.write(bodyBytes);
        }

        int status = conn.getResponseCode();
        String responseBody;

        InputStream stream = (status >= 200 && status < 300)
            ? conn.getInputStream()
            : conn.getErrorStream();

        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(stream, StandardCharsets.UTF_8)))
        {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null)
            {
                sb.append(line);
            }
            responseBody = sb.toString();
        }
        finally
        {
            conn.disconnect();
        }

        if (status < 200 || status >= 300)
        {
            throw new IOException("HTTP " + status + ": " + responseBody);
        }

        // Parse OpenAI response: { "choices": [{ "message": { "content": "..." } }] }
        JsonObject response = JsonParser.parseString(responseBody).getAsJsonObject();
        JsonArray choices = response.getAsJsonArray("choices");
        if (choices == null || choices.size() == 0)
        {
            throw new IOException("No choices in response: " + responseBody);
        }

        return choices.get(0).getAsJsonObject()
            .getAsJsonObject("message")
            .get("content").getAsString();
    }

    /**
     * Builds the OpenAI {@code response_format} object that constrains the
     * LLM's output to a JSON OBJECT containing a {@code thinking} field
     * (so the model can still chain-of-thought) and an {@code actions}
     * array matching the bot's action schema.
     *
     * <p>The wrapper-with-thinking shape was a deliberate choice over a
     * bare array: a bare array forced the constrained sampler to suppress
     * Qwen's {@code <think>...</think>} blocks entirely, which on a 9B
     * reasoning model collapses planning quality. By giving the model an
     * explicit {@code thinking} field inside the schema, it can still
     * reason — just inside the JSON instead of around it.
     *
     * <p>The existing {@link #extractReasoning} parser is taught to read
     * the {@code thinking} key when present; the existing
     * {@link com.osrsbot.claude.brain.ResponseParser#parse} path finds
     * the inner {@code actions} array via its scan-for-[ logic.
     */
    private JsonObject buildActionResponseFormat()
    {
        // Action name enum, sourced from the canonical ActionType list so
        // the schema can never drift from the real enum.
        JsonArray actionEnum = new JsonArray();
        for (com.osrsbot.claude.action.ActionType t : com.osrsbot.claude.action.ActionType.values())
        {
            actionEnum.add(t.name());
        }

        // Per-action shape. Every field except `action` is optional. We list
        // all fields the parser knows about so the model has the full
        // vocabulary; additionalProperties=false rejects fabricated keys.
        JsonObject itemProps = new JsonObject();
        addProp(itemProps, "action",   "string",  actionEnum);
        addProp(itemProps, "name",     "string",  null);
        addProp(itemProps, "option",   "string",  null);
        addProp(itemProps, "x",        "integer", null);
        addProp(itemProps, "y",        "integer", null);
        addProp(itemProps, "plane",    "integer", null);
        addProp(itemProps, "ticks",    "integer", null);
        addProp(itemProps, "quantity", "integer", null);
        addProp(itemProps, "item",     "string",  null);
        addProp(itemProps, "item1",    "string",  null);
        addProp(itemProps, "item2",    "string",  null);
        addProp(itemProps, "npc",      "string",  null);
        addProp(itemProps, "object",   "string",  null);
        addProp(itemProps, "text",     "string",  null);
        addProp(itemProps, "goal",     "string",  null);

        JsonArray itemRequired = new JsonArray();
        itemRequired.add("action");

        JsonObject itemSchema = new JsonObject();
        itemSchema.addProperty("type", "object");
        itemSchema.add("properties", itemProps);
        itemSchema.add("required", itemRequired);
        itemSchema.addProperty("additionalProperties", false);

        JsonObject actionsArraySchema = new JsonObject();
        actionsArraySchema.addProperty("type", "array");
        actionsArraySchema.addProperty("minItems", 1);
        actionsArraySchema.addProperty("maxItems", 5);
        actionsArraySchema.add("items", itemSchema);

        // Top-level wrapper: {"thinking": "...", "actions": [...]}
        // maxLength on thinking caps the chain-of-thought to prevent runaway
        // generation. Without it, Qwen 9B can emit ~8 KB of repetitive
        // restated-plan text, hit the response token cap mid-string, and
        // produce unterminated JSON that fails to parse — leading to a
        // WAIT-only fallback loop and the player getting idle-kicked.
        JsonObject thinkingProp = new JsonObject();
        thinkingProp.addProperty("type", "string");
        thinkingProp.addProperty("maxLength", 1200);

        JsonObject rootProps = new JsonObject();
        rootProps.add("thinking", thinkingProp);
        rootProps.add("actions", actionsArraySchema);

        JsonArray rootRequired = new JsonArray();
        // Both required — making thinking optional caused the model to skip
        // it on follow-on turns (we observed it inline 1-of-4 times). Forcing
        // thinking on every turn restored the chain-of-thought behavior the
        // 9B model needs to reason through level-gated / item-required
        // actions like "burn willow logs at FM 20".
        rootRequired.add("thinking");
        rootRequired.add("actions");

        JsonObject rootSchema = new JsonObject();
        rootSchema.addProperty("type", "object");
        rootSchema.add("properties", rootProps);
        rootSchema.add("required", rootRequired);
        rootSchema.addProperty("additionalProperties", false);

        JsonObject jsonSchema = new JsonObject();
        jsonSchema.addProperty("name", "actions");
        jsonSchema.addProperty("strict", true);
        jsonSchema.add("schema", rootSchema);

        JsonObject responseFormat = new JsonObject();
        responseFormat.addProperty("type", "json_schema");
        responseFormat.add("json_schema", jsonSchema);
        return responseFormat;
    }

    /** Helper: add {@code "fieldName": {"type": "..."}} to a properties
     *  object. If {@code enumValues} is non-null, the property is also
     *  constrained to those values. */
    private static void addProp(JsonObject properties, String name, String type, JsonArray enumValues)
    {
        JsonObject p = new JsonObject();
        p.addProperty("type", type);
        if (enumValues != null)
        {
            p.add("enum", enumValues);
        }
        properties.add(name, p);
    }

    private void logRequest(List<ConversationManager.Exchange> conversationHistory, String currentState)
    {
        String provider = (apiBaseUrl != null && !apiBaseUrl.isEmpty()) ? "LOCAL (" + apiBaseUrl + ")" : "ANTHROPIC";
        StringBuilder logMsg = new StringBuilder();
        logMsg.append("\n╔══════════════════════════════════════════════════════════════\n");
        logMsg.append("║  API REQUEST [").append(provider).append("]\n");
        logMsg.append("║  Model: ").append(model).append("  Max Tokens: ").append(maxTokens).append("\n");
        logMsg.append("╠══════════════════════════════════════════════════════════════\n");
        logMsg.append("║  SYSTEM PROMPT:\n");
        for (String line : systemPrompt.split("\n"))
        {
            logMsg.append("║  ").append(line).append("\n");
        }
        logMsg.append("╠══════════════════════════════════════════════════════════════\n");
        logMsg.append("║  CONVERSATION (").append(conversationHistory.size()).append(" prior exchanges + current state):\n");
        int exchangeNum = 1;
        for (ConversationManager.Exchange exchange : conversationHistory)
        {
            logMsg.append("║  ── Exchange ").append(exchangeNum++).append(" ──\n");
            logMsg.append("║  [USER]  ").append(truncate(exchange.getUserMessage(), 200)).append("\n");
            logMsg.append("║  [ASST]  ").append(truncate(exchange.getAssistantMessage(), 200)).append("\n");
        }
        logMsg.append("║  ── Current State ──\n");
        for (String line : currentState.split("\n"))
        {
            logMsg.append("║  ").append(line).append("\n");
        }
        logMsg.append("╚══════════════════════════════════════════════════════════════");
        System.out.println(logMsg.toString());
    }

    private void logResponse(String responseText)
    {
        StringBuilder logMsg = new StringBuilder();
        logMsg.append("\n╔══════════════════════════════════════════════════════════════\n");
        logMsg.append("║  API RESPONSE\n");
        logMsg.append("╠══════════════════════════════════════════════════════════════\n");
        for (String line : responseText.split("\n"))
        {
            logMsg.append("║  ").append(line).append("\n");
        }
        logMsg.append("╚══════════════════════════════════════════════════════════════");
        System.out.println(logMsg.toString());
    }

    private static String truncate(String text, int maxLen)
    {
        if (text == null) return "(null)";
        String oneLine = text.replace("\n", " ").replace("\r", "");
        if (oneLine.length() <= maxLen) return oneLine;
        return oneLine.substring(0, maxLen) + "... (" + text.length() + " chars total)";
    }

    public void shutdown()
    {
        if (executor == null) return;
        executor.shutdown();
        try
        {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
        catch (InterruptedException e)
        {
            executor.shutdownNow();
        }
    }
}
