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
    private AnthropicClient anthropicClient;
    private ExecutorService executor;
    private String model;
    private int maxTokens;
    private String systemPrompt;
    private boolean logApiCalls;

    /** If non-empty, use OpenAI-compatible HTTP endpoint instead of Anthropic SDK */
    private String apiBaseUrl;

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

    private String query(List<ConversationManager.Exchange> conversationHistory, String currentState)
    {
        if (logApiCalls)
        {
            logRequest(conversationHistory, currentState);
        }

        try
        {
            String responseText;
            if (apiBaseUrl != null && !apiBaseUrl.isEmpty())
            {
                responseText = queryOpenAI(conversationHistory, currentState);
            }
            else
            {
                responseText = queryAnthropic(conversationHistory, currentState);
            }

            if (logApiCalls)
            {
                logResponse(responseText);
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
        requestBody.addProperty("temperature", 0.7);

        // Send HTTP request
        String endpoint = apiBaseUrl + "/chat/completions";
        byte[] bodyBytes = requestBody.toString().getBytes(StandardCharsets.UTF_8);

        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
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
