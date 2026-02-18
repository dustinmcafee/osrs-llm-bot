package com.osrsbot.claude.brain;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.*;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;
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

    public void initialize(String apiKey, String model, int maxTokens)
    {
        this.model = model;
        this.maxTokens = maxTokens;
        this.anthropicClient = AnthropicOkHttpClient.builder()
            .apiKey(apiKey)
            .build();

        // Recreate executor in case shutdown() was called previously (plugin toggle)
        if (executor == null || executor.isShutdown())
        {
            executor = Executors.newSingleThreadExecutor();
        }
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
        try
        {
            List<MessageParam> messages = new java.util.ArrayList<>();

            // Add conversation history
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

            // Add current state as latest user message
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

            if (logApiCalls)
            {
                StringBuilder logMsg = new StringBuilder();
                logMsg.append("\n╔══════════════════════════════════════════════════════════════\n");
                logMsg.append("║  CLAUDE API REQUEST\n");
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

            Message response = anthropicClient.messages().create(params);

            String responseText = response.content().stream()
                .filter(ContentBlock::isText)
                .map(block -> block.asText().text())
                .collect(Collectors.joining());

            if (logApiCalls)
            {
                StringBuilder logMsg = new StringBuilder();
                logMsg.append("\n╔══════════════════════════════════════════════════════════════\n");
                logMsg.append("║  CLAUDE API RESPONSE\n");
                logMsg.append("║  Usage: input=").append(response.usage().inputTokens())
                    .append(" output=").append(response.usage().outputTokens())
                    .append("  Stop: ").append(response.stopReason()).append("\n");
                logMsg.append("╠══════════════════════════════════════════════════════════════\n");
                for (String line : responseText.split("\n"))
                {
                    logMsg.append("║  ").append(line).append("\n");
                }
                logMsg.append("╚══════════════════════════════════════════════════════════════");
                System.out.println(logMsg.toString());
            }

            return responseText;
        }
        catch (Throwable e)
        {
            System.err.println("[ClaudeBot] Claude API error: " + e.getClass().getName() + ": " + e.getMessage());
            if (logApiCalls)
            {
                System.err.println("╔══════════════════════════════════════════════════════════════\n"
                    + "║  CLAUDE API ERROR: " + e.getClass().getName() + "\n"
                    + "║  " + e.getMessage() + "\n"
                    + "╚══════════════════════════════════════════════════════════════");
            }
            return "[{\"action\":\"WAIT\",\"ticks\":5}]";
        }
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
