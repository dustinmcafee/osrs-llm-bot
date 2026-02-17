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

            Message response = anthropicClient.messages().create(params);

            return response.content().stream()
                .filter(ContentBlock::isText)
                .map(block -> block.asText().text())
                .collect(Collectors.joining());
        }
        catch (Throwable e)
        {
            System.err.println("[ClaudeBot] Claude API error: " + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace(System.err);
            return "[{\"action\":\"WAIT\",\"ticks\":5}]";
        }
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
