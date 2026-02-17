package com.osrsbot.claude;

import com.google.inject.Provides;
import com.osrsbot.claude.action.ActionExecutor;
import com.osrsbot.claude.action.ActionQueue;
import com.osrsbot.claude.action.BotAction;
import com.osrsbot.claude.brain.ClaudeBrainClient;
import com.osrsbot.claude.brain.ConversationManager;
import com.osrsbot.claude.brain.ResponseParser;
import com.osrsbot.claude.brain.SystemPromptBuilder;
import com.osrsbot.claude.human.HumanSimulator;
import com.osrsbot.claude.state.GameStateReader;
import com.osrsbot.claude.state.GameStateSerializer;
import com.osrsbot.claude.state.GameStateSnapshot;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameTick;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@PluginDescriptor(
    name = "Claude Bot",
    description = "AI-powered bot using Claude as its decision-making brain",
    tags = {"bot", "ai", "claude"}
)
public class ClaudeBotPlugin extends Plugin
{
    @Inject
    private Client client;

    @Inject
    private ClaudeBotConfig config;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private ClaudeBotOverlay overlay;

    @Inject
    private GameStateReader gameStateReader;

    @Inject
    private GameStateSerializer gameStateSerializer;

    @Inject
    private ClaudeBrainClient brainClient;

    @Inject
    private ConversationManager conversationManager;

    @Inject
    private SystemPromptBuilder systemPromptBuilder;

    @Inject
    private ResponseParser responseParser;

    @Inject
    private ActionQueue actionQueue;

    @Inject
    private ActionExecutor actionExecutor;

    @Inject
    private HumanSimulator humanSimulator;

    private int tickCounter = 0;
    private final AtomicBoolean awaitingResponse = new AtomicBoolean(false);
    private String lastSerializedState = "";
    private String lastClaudeResponse = "";
    private String botStatus = "Idle";

    @Override
    protected void startUp()
    {
        try
        {
            System.out.println("[ClaudeBot] startUp() called");
            log.info("Claude Bot plugin starting up");
            overlayManager.add(overlay);
            System.out.println("[ClaudeBot] overlay added");

            // Initialize brain client
            if (!config.apiKey().isEmpty())
            {
                brainClient.initialize(config.apiKey(), config.model(), config.maxTokens());
                String systemPrompt = systemPromptBuilder.build(config.task());
                brainClient.setSystemPrompt(systemPrompt);
                System.out.println("[ClaudeBot] brain initialized with model: " + config.model());
            }
            else
            {
                System.out.println("[ClaudeBot] WARNING: No API key configured");
            }

            // Initialize conversation manager
            conversationManager.setMaxExchanges(config.contextWindowSize());

            // Initialize humanization
            humanSimulator.initialize(
                config.mouseSpeed(),
                config.minActionDelay(),
                config.maxActionDelay()
            );
            humanSimulator.configureBreaks(
                config.breaksEnabled(),
                config.minBreakInterval(),
                config.maxBreakInterval(),
                config.minBreakDuration(),
                config.maxBreakDuration()
            );
            System.out.println("[ClaudeBot] humanSimulator initialized");

            // Configure state reader
            gameStateReader.setScanRadius(config.nearbyEntityRadius());

            botStatus = "Ready";
            System.out.println("[ClaudeBot] startUp() complete - status: Ready");
            System.out.println("[ClaudeBot] enabled=" + config.enabled() + " showOverlay=" + config.showOverlay());
        }
        catch (Throwable t)
        {
            System.err.println("[ClaudeBot] startUp() FAILED: " + t.getClass().getName() + ": " + t.getMessage());
            t.printStackTrace(System.err);
            botStatus = "Error: " + t.getMessage();
        }
    }

    @Override
    protected void shutDown()
    {
        log.info("Claude Bot plugin shutting down");
        overlayManager.remove(overlay);
        brainClient.shutdown();
        actionExecutor.shutdown();
        actionQueue.clear();
        conversationManager.clear();
        botStatus = "Stopped";
    }

    @Subscribe
    @SuppressWarnings("all")
    public void onGameTick(GameTick event)
    {
        try
        {
            doGameTick();
        }
        catch (Throwable t)
        {
            // Catch EVERYTHING including Error to prevent RuneLite from disabling the plugin
            botStatus = "Error: " + t.getMessage();
            System.err.println("[ClaudeBot] onGameTick THROWABLE: " + t.getClass().getName() + ": " + t.getMessage());
            t.printStackTrace(System.err);
        }
    }

    private void doGameTick()
    {
        if (!config.enabled())
        {
            botStatus = "Disabled";
            return;
        }

        if (client.getGameState() != GameState.LOGGED_IN)
        {
            return;
        }

        // Ensure the human simulator has a reference to the game canvas
        // so it can dispatch synthetic input events using canvas-relative coordinates
        if (client.getCanvas() != null)
        {
            humanSimulator.setGameCanvas(client.getCanvas());
        }

        // Check for break
        if (humanSimulator.shouldTakeBreak())
        {
            botStatus = "On break (" + (humanSimulator.getBreakScheduler().getBreakTimeRemaining() / 60000) + "m remaining)";
            return;
        }

        // Execute queued actions
        if (!actionQueue.isEmpty() || actionExecutor.isBusy())
        {
            actionExecutor.tick();
            BotAction current = actionExecutor.getCurrentAction();
            botStatus = "Executing: " + (current != null ? current.getType().name() : "processing");
            return;
        }

        // Tick counter for API throttling
        tickCounter++;
        if (tickCounter < config.tickRate())
        {
            botStatus = "Waiting (" + tickCounter + "/" + config.tickRate() + " ticks)";
            return;
        }
        tickCounter = 0;

        // Don't send another request if we're still waiting
        if (awaitingResponse.get())
        {
            botStatus = "Awaiting Claude response...";
            return;
        }

        // Capture and serialize game state
        GameStateSnapshot snapshot = gameStateReader.capture();
        String serialized = gameStateSerializer.serialize(snapshot);
        lastSerializedState = serialized;

        if (config.logState())
        {
            log.info("Game state:\n{}", serialized);
        }

        // Query Claude
        if (config.apiKey().isEmpty())
        {
            botStatus = "No API key";
            return;
        }

        botStatus = "Querying Claude...";
        awaitingResponse.set(true);

        CompletableFuture<String> future = brainClient.queryAsync(
            conversationManager.getHistory(),
            serialized
        );

        future.thenAccept(response -> {
            awaitingResponse.set(false);
            lastClaudeResponse = response;

            if (config.logApiCalls())
            {
                log.info("Claude response: {}", response);
            }

            // Parse and enqueue actions
            List<BotAction> actions = responseParser.parse(response);
            for (BotAction action : actions)
            {
                actionQueue.enqueue(action);
            }

            // Add to conversation history
            conversationManager.addExchange(serialized, response);

            botStatus = "Queued " + actions.size() + " actions";
        }).exceptionally(e -> {
            awaitingResponse.set(false);
            botStatus = "API Error: " + e.getMessage();
            System.err.println("[ClaudeBot] Claude query FAILED: " + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace(System.err);
            return null;
        });
    }

    @Provides
    ClaudeBotConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(ClaudeBotConfig.class);
    }

    // Accessors for overlay
    public String getBotStatus()
    {
        return botStatus;
    }

    public String getLastSerializedState()
    {
        return lastSerializedState;
    }

    public String getLastClaudeResponse()
    {
        return lastClaudeResponse;
    }

    public int getQueueSize()
    {
        return actionQueue.size();
    }

    public boolean isAwaitingResponse()
    {
        return awaitingResponse.get();
    }
}
