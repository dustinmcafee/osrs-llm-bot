package com.osrsbot.claude;

import com.google.inject.Provides;
import com.osrsbot.claude.action.ActionExecutor;
import com.osrsbot.claude.action.ActionQueue;
import com.osrsbot.claude.action.BotAction;
import com.osrsbot.claude.brain.ClaudeBrainClient;
import com.osrsbot.claude.brain.ConversationManager;
import com.osrsbot.claude.brain.LoopDetector;
import com.osrsbot.claude.brain.ResponseParser;
import com.osrsbot.claude.brain.SystemPromptBuilder;
import com.osrsbot.claude.gear.GearRecommender;
import com.osrsbot.claude.gear.RecipeRegistry;
import com.osrsbot.claude.human.HumanSimulator;
import com.osrsbot.claude.state.GameStateReader;
import com.osrsbot.claude.state.GameStateSerializer;
import com.osrsbot.claude.state.GameStateSnapshot;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Hitsplat;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.PostMenuSort;
import net.runelite.api.events.StatChanged;
import net.runelite.client.events.ConfigChanged;
import net.runelite.api.Skill;
import com.osrsbot.claude.util.BankMenuSwap;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;
import java.util.ArrayList;
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
    private LoopDetector loopDetector;

    @Inject
    private GearRecommender gearRecommender;

    @Inject
    private RecipeRegistry recipeRegistry;

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

    @Inject
    private com.osrsbot.claude.pathfinder.PathfinderService pathfinderService;

    @Inject
    private ConfigManager configManager;

    @Inject
    private net.runelite.client.plugins.PluginManager pluginManager;

    @Inject
    private net.runelite.client.callback.ClientThread clientThread;

    private int tickCounter = 0;
    private final AtomicBoolean awaitingResponse = new AtomicBoolean(false);
    private volatile boolean active = false;
    private volatile int generation = 0;
    private volatile String lastSerializedState = "";
    private volatile String lastClaudeResponse = "";
    private volatile String lastReasoning = "";
    private volatile List<String> lastParseErrors = new ArrayList<>();

    /** Last seen base level per skill — used to detect level-ups in
     *  onStatChanged so we can fire a one-shot [LEVEL_UP] notice on the
     *  next prompt. Initialized lazily on first event from each skill. */
    private final java.util.Map<Skill, Integer> lastSkillLevel = new java.util.concurrent.ConcurrentHashMap<>();

    /** Pending [LEVEL_UP] notices, drained into the next prompt and cleared. */
    private final java.util.Queue<String> pendingLevelUps = new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** Inventory item names that are currently present in the player's
     *  inventory. Used to detect "newly added" items: any item name in
     *  this tick's inventory that wasn't in this set last tick triggers
     *  a one-shot [NEW_INGREDIENT_RECIPES] block on the next prompt. */
    private final java.util.Set<String> presentInventoryItems = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile String botStatus = "Idle";
    private volatile String currentGoal = "";

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
            String baseUrl = config.apiBaseUrl().trim();
            boolean hasApiKey = !config.apiKey().isEmpty();
            boolean hasLocalUrl = !baseUrl.isEmpty();

            if (hasApiKey || hasLocalUrl)
            {
                brainClient.initialize(config.apiKey(), config.model(), config.maxTokens(), baseUrl);
                brainClient.setLogApiCalls(config.logApiCalls());
                String systemPrompt = systemPromptBuilder.build(config.task(), config.enableThinking());
                brainClient.setSystemPrompt(systemPrompt);
                if (hasLocalUrl)
                {
                    System.out.println("[ClaudeBot] brain initialized with LOCAL model: "
                        + config.model() + " at " + baseUrl);
                }
                else
                {
                    System.out.println("[ClaudeBot] brain initialized with model: " + config.model());
                }
            }
            else
            {
                System.out.println("[ClaudeBot] WARNING: No API key or local URL configured");
            }

            // Initialize conversation manager
            conversationManager.setRecentCount(config.contextWindowSize());
            conversationManager.setMaxSessionNotesChars(config.memoryBudget());

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

            // Load pathfinding data (collision map + transports)
            try
            {
                pathfinderService.load();
                pathfinderService.setAllowTolls(config.allowTolls());
                System.out.println("[ClaudeBot] pathfinder loaded (tolls=" + config.allowTolls() + ")");
            }
            catch (Throwable t)
            {
                System.err.println("[ClaudeBot] Pathfinder load failed (non-fatal): " + t.getMessage());
            }

            generation++;
            active = true;
            botStatus = "Ready";
            System.out.println("[ClaudeBot] startUp() complete - status: Ready");
            System.out.println("[ClaudeBot] enabled=" + config.enabled() + " showOverlay=" + config.showOverlay());
            boolean qhFound = pluginManager.getPlugins().stream()
                .anyMatch(p -> p instanceof com.questhelper.QuestHelperPlugin);
            System.out.println("[ClaudeBot] QuestHelper loaded: " + qhFound
                + " (" + pluginManager.getPlugins().size() + " total plugins)");

            if (config.questToTrack() != null && config.questToTrack() != TrackedQuest.NONE)
                applyTrackedQuest();
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
        System.out.println("[ClaudeBot] shutDown() called — deactivating plugin");

        // Deactivate first so in-flight async callbacks are ignored
        active = false;
        awaitingResponse.set(false);

        overlayManager.remove(overlay);
        brainClient.shutdown();
        actionExecutor.shutdown();
        actionQueue.clear();
        conversationManager.clear();
        gameStateReader.clearMessages();
        loopDetector.reset();

        // Reset all state for clean re-enable
        tickCounter = 0;
        lastSerializedState = "";
        lastClaudeResponse = "";
        botStatus = "Stopped";

        System.out.println("[ClaudeBot] shutDown() complete — all state cleared");
    }

    @Subscribe
    public void onChatMessage(ChatMessage event)
    {
        ChatMessageType type = event.getType();
        // Capture engine messages and NPC speech that explains why an action
        // failed. DIALOG covers NPC dialogue boxes (e.g. the Lumbridge Cook's
        // "Hey, who said you could use that?" when the player tries to use
        // his range without completing Cook's Assistant). NPC_SAY covers
        // overhead NPC speech (aggressive monsters yelling, etc.). Without
        // these the model only sees the dialogue widget summary, not the
        // verbatim line, and can't reason about what the NPC actually said.
        if (type == ChatMessageType.GAMEMESSAGE
            || type == ChatMessageType.SPAM
            || type == ChatMessageType.MESBOX
            || type == ChatMessageType.ENGINE
            || type == ChatMessageType.DIALOG
            || type == ChatMessageType.NPC_SAY)
        {
            String text = event.getMessage();
            if (text != null)
            {
                // Strip HTML color tags from the message
                text = text.replaceAll("<[^>]+>", "").trim();
                if (!text.isEmpty())
                {
                    // For NPC speech and dialogue, prefix the speaker so the
                    // model can attribute the line correctly. GAMEMESSAGE and
                    // friends typically have an empty name, so this only adds
                    // a prefix when a real speaker is identified.
                    String speaker = event.getName();
                    if (speaker != null)
                    {
                        speaker = speaker.replaceAll("<[^>]+>", "").trim();
                        if (!speaker.isEmpty())
                        {
                            text = speaker + ": " + text;
                        }
                    }
                    gameStateReader.onGameMessage(text);
                }
            }
        }
    }

    @Subscribe
    public void onHitsplatApplied(HitsplatApplied event)
    {
        if (event.getActor() == client.getLocalPlayer())
        {
            Hitsplat hitsplat = event.getHitsplat();
            if (hitsplat.isMine())
            {
                // Player took a hit (damage or block) — definitive combat signal
                gameStateReader.onPlayerHitsplat();
            }
        }
    }

    @Subscribe
    public void onPostMenuSort(PostMenuSort event)
    {
        if (client.isMenuOpen()) return;
        BankMenuSwap.processSwap(client);
    }

    /**
     * Detects base-level increases per skill and queues a [LEVEL_UP]
     * notice for the next prompt. Boosts (potions / drains) are ignored —
     * we only fire when the canonical level changes. The notice includes
     * gear newly unlocked at this level, derived from the registry.
     */
    @Subscribe
    public void onStatChanged(StatChanged event)
    {
        Skill skill = event.getSkill();
        if (skill == null) return;
        int newLevel = event.getLevel();
        Integer prev = lastSkillLevel.put(skill, newLevel);
        if (prev == null || newLevel <= prev) return;

        StringBuilder msg = new StringBuilder("[LEVEL_UP] ");
        msg.append(skill.getName()).append(" reached level ").append(newLevel).append(".");
        String unlocked = gearRecommender.recommendForLevelUp(client, skill, newLevel);
        if (unlocked != null && !unlocked.isEmpty())
        {
            msg.append(" ").append(unlocked).append(".");
        }
        pendingLevelUps.add(msg.toString());
        System.out.println("[ClaudeBot] " + msg);
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event)
    {
        if (!"claudebot".equals(event.getGroup()) || !"questToTrack".equals(event.getKey())) return;
        applyTrackedQuest();
    }

    private void applyTrackedQuest()
    {
        TrackedQuest tracked = config.questToTrack();
        com.questhelper.QuestHelperPlugin qh = pluginManager.getPlugins().stream()
            .filter(p -> p instanceof com.questhelper.QuestHelperPlugin)
            .map(p -> (com.questhelper.QuestHelperPlugin) p)
            .findFirst().orElse(null);
        if (qh == null)
        {
            System.out.println("[ClaudeBot] questToTrack changed but QuestHelper not loaded");
            return;
        }
        if (tracked == TrackedQuest.NONE)
        {
            clientThread.invokeLater(() -> qh.getQuestManager().shutDownQuest(false));
            System.out.println("[ClaudeBot] Quest tracking disabled");
            return;
        }
        try
        {
            com.questhelper.questhelpers.QuestHelper questHelper =
                com.questhelper.questinfo.QuestHelperQuest.valueOf(tracked.name()).getQuestHelper();
            if (questHelper == null)
            {
                System.out.println("[ClaudeBot] No QuestHelper for: " + tracked.name());
                return;
            }
            clientThread.invokeLater(() -> qh.getQuestManager().startUpQuest(questHelper, false));
            System.out.println("[ClaudeBot] Now tracking quest: " + tracked.getName());
        }
        catch (Exception e)
        {
            System.err.println("[ClaudeBot] Failed to start quest " + tracked.name() + ": " + e.getMessage());
        }
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
        // Decay combat countdown every tick (must run even when bot is disabled)
        gameStateReader.onGameTick();

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

        // Prepend action results from the last batch so Claude knows what worked/failed
        List<ActionExecutor.ExecutedAction> results = actionExecutor.getAndClearResults();
        List<String> gameFailures = gameStateReader.drainFailureMessages();
        List<String> parseErrs = lastParseErrors;
        lastParseErrors = new ArrayList<>();

        // Loop detection: if the last 3 batches issued the same actions with
        // no XP gain and no inventory change, surface a [LOOP_DETECTED]
        // warning so the LLM has explicit text to react to instead of
        // silently grinding the same dead-end. Gated by config.
        String loopWarning = null;
        if (config.loopDetectionEnabled())
        {
            loopWarning = loopDetector.evaluate(results, snapshot).orElse(null);
        }
        if (!results.isEmpty() || !gameFailures.isEmpty() || !parseErrs.isEmpty())
        {
            StringBuilder resultBlock = new StringBuilder();
            resultBlock.append("[ACTION_RESULTS] Your previous actions:\n");
            // Show parse errors first so the LLM knows its JSON was malformed
            for (String err : parseErrs)
            {
                resultBlock.append("  ** ").append(err).append(" **\n");
            }
            for (int i = 0; i < results.size(); i++)
            {
                resultBlock.append("  ").append(i + 1).append(". ")
                    .append(results.get(i).describe()).append("\n");
            }
            // Append game-detected failures (async messages like "I can't reach that")
            for (String failure : gameFailures)
            {
                resultBlock.append("  ** GAME_FAILURE: ").append(failure).append(" **\n");
            }
            serialized = resultBlock.toString() + serialized;
        }

        // Loop warning is prepended above [ACTION_RESULTS] so it's the very
        // first thing the LLM reads — it's a louder signal than "your action
        // failed", which the model has been ignoring while looping.
        if (loopWarning != null)
        {
            serialized = loopWarning + "\n" + serialized;
            System.out.println("[ClaudeBot] " + loopWarning);
        }

        // Drain queued level-up notices (one-shot per level) and prepend
        // them so the model can react in the same prompt the level-up
        // happened. These call out gear newly unlocked at the new level.
        if (!pendingLevelUps.isEmpty())
        {
            StringBuilder lvl = new StringBuilder();
            String n;
            while ((n = pendingLevelUps.poll()) != null)
            {
                lvl.append(n).append("\n");
            }
            serialized = lvl.toString() + serialized;
        }

        // Always-on recommended-gear summary based on current levels.
        // Cheap (one line) and persistent — gives the model a concrete
        // upgrade target instead of having to remember the tier table.
        String recs = gearRecommender.recommendCurrent(client);
        if (recs != null && !recs.isEmpty())
        {
            serialized = "[RECOMMENDED_GEAR] " + recs + "\n" + serialized;
        }

        // [NEW_INGREDIENT_RECIPES]: when an item first appears in the
        // player's inventory this session, attach the recipes that use it
        // so the LLM can see what the new ingredient unlocks. Fires only
        // on first appearance (count 0 → N), not every tick — otherwise
        // chopping logs would spam the recipe block continuously.
        String recipesBlock = buildNewIngredientRecipesBlock(snapshot);
        if (recipesBlock != null && !recipesBlock.isEmpty())
        {
            serialized = recipesBlock + serialized;
        }

        // Prepend current goal so Claude maintains multi-step plans
        if (currentGoal != null && !currentGoal.isEmpty())
        {
            serialized = "[CURRENT_GOAL] " + currentGoal + "\n" + serialized;
        }

        // Prepend compressed session notes so Claude remembers earlier activity
        String sessionNotes = conversationManager.getSessionNotes();
        if (!sessionNotes.isEmpty())
        {
            serialized = "[SESSION_NOTES] Summary of earlier activity:\n" + sessionNotes + "\n" + serialized;
        }

        // Inject user nudge if present, then auto-clear (one-shot semantics)
        String nudge = config.nudge();
        if (nudge != null && !nudge.trim().isEmpty())
        {
            serialized = "[USER_NUDGE] The user wants you to: " + nudge.trim() + "\n"
                + "Acknowledge this nudge and adjust your plan accordingly. This is a one-time instruction.\n"
                + serialized;
            configManager.setConfiguration("claudebot", "nudge", "");
        }

        // Inject the persistent directive on every turn while non-empty.
        // Unlike the one-shot nudge, this does NOT auto-clear — the user
        // edits/clears the field manually. Designed for steering goals
        // the model keeps drifting from. Placed AFTER the one-shot nudge
        // so it appears closer to the top of the prompt (highest priority).
        String directive = config.persistentDirective();
        if (directive != null && !directive.trim().isEmpty())
        {
            serialized = "[USER_DIRECTIVE] STANDING ORDER (re-fired every turn until the user clears it): "
                + directive.trim() + "\n"
                + "Treat this as a hard constraint. Do not deviate even if you previously planned otherwise.\n"
                + serialized;
        }

        final String gameState = serialized;
        lastSerializedState = gameState;

        if (config.logState())
        {
            System.out.println("[ClaudeBot] Game state:\n" + gameState);
        }

        // Query Claude (skip if no API key AND no local/proxy URL configured)
        if (config.apiKey().isEmpty() && config.apiBaseUrl().trim().isEmpty())
        {
            botStatus = "No API key";
            return;
        }

        botStatus = "Querying Claude...";
        awaitingResponse.set(true);
        brainClient.setLogApiCalls(config.logApiCalls());

        final int queryGeneration = generation;

        CompletableFuture<String> future = brainClient.queryAsync(
            conversationManager.getHistory(),
            gameState
        );

        future.thenAccept(response -> {
            awaitingResponse.set(false);

            // Guard: if plugin was toggled while API call was in flight, discard the response
            if (!active || generation != queryGeneration)
            {
                System.out.println("[ClaudeBot] Discarding stale API response (plugin was restarted)");
                return;
            }

            lastClaudeResponse = response;

            // Log Claude's reasoning if present
            String reasoning = responseParser.extractReasoning(response);
            if (reasoning != null)
            {
                lastReasoning = reasoning;
                System.out.println("[ClaudeBot] Claude's thinking: " + reasoning);
            }

            // Parse and enqueue actions
            List<BotAction> actions = responseParser.parse(response);

            // Collect parse errors — these get prepended to action results on the next query
            lastParseErrors = responseParser.getAndClearParseErrors();
            for (String err : lastParseErrors)
            {
                System.out.println("[ClaudeBot] Parse error: " + err);
            }

            // Log parsed actions in readable format
            StringBuilder actionLog = new StringBuilder();
            actionLog.append("[ClaudeBot] ── Actions (").append(actions.size()).append(") ──\n");
            for (int i = 0; i < actions.size(); i++)
            {
                BotAction a = actions.get(i);
                actionLog.append("  ").append(i + 1).append(". ").append(a.getType().name());
                if (a.getName() != null) actionLog.append(" name=\"").append(a.getName()).append("\"");
                if (a.getOption() != null) actionLog.append(" option=\"").append(a.getOption()).append("\"");
                if (a.getX() != 0 || a.getY() != 0) actionLog.append(" x=").append(a.getX()).append(" y=").append(a.getY());
                if (a.getTicks() != 0) actionLog.append(" ticks=").append(a.getTicks());
                if (a.getQuantity() != 0) actionLog.append(" qty=").append(a.getQuantity());
                if (a.getItem() != null) actionLog.append(" item=\"").append(a.getItem()).append("\"");
                if (a.getItem1() != null) actionLog.append(" item1=\"").append(a.getItem1()).append("\"");
                if (a.getItem2() != null) actionLog.append(" item2=\"").append(a.getItem2()).append("\"");
                if (a.getNpc() != null) actionLog.append(" npc=\"").append(a.getNpc()).append("\"");
                if (a.getObject() != null) actionLog.append(" object=\"").append(a.getObject()).append("\"");
                if (a.getText() != null) actionLog.append(" text=\"").append(a.getText()).append("\"");
                actionLog.append("\n");
            }
            System.out.print(actionLog);

            // Enforce batch size limit — only enqueue up to maxActionsPerBatch
            int batchLimit = config.maxActionsPerBatch();
            int enqueued = 0;
            for (BotAction action : actions)
            {
                if (enqueued >= batchLimit)
                {
                    System.out.println("[ClaudeBot] Batch limit reached (" + batchLimit +
                        "), skipping remaining " + (actions.size() - enqueued) + " actions");
                    break;
                }
                actionQueue.enqueue(action);
                enqueued++;
            }

            // Update persistent goal if Claude set one
            String newGoal = responseParser.getLastGoal();
            if (newGoal != null && !newGoal.isEmpty())
            {
                currentGoal = newGoal;
                System.out.println("[ClaudeBot] Goal updated: " + currentGoal);
            }

            // Add to conversation history. Store ONLY the parsed JSON action
            // array, NOT the surrounding prose / <think> blocks. Storing the
            // full response causes the LLM's own speculative reasoning
            // ("the user wants me to do X") to be re-fed on later turns and
            // gradually mutate into fabricated user instructions.
            conversationManager.addExchange(gameState, responseParser.extractStorableResponse(response));

            botStatus = "Queued " + enqueued + "/" + actions.size() + " actions";
        }).exceptionally(e -> {
            awaitingResponse.set(false);
            if (!active || generation != queryGeneration)
            {
                return null;
            }
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

    public String getLastReasoning()
    {
        return lastReasoning;
    }

    public int getQueueSize()
    {
        return actionQueue.size();
    }

    public boolean isAwaitingResponse()
    {
        return awaitingResponse.get();
    }

    /**
     * Builds a [NEW_INGREDIENT_RECIPES] block listing recipes for any
     * inventory items that have just appeared this tick (i.e. were not
     * present last tick). Updates {@link #presentInventoryItems} so the
     * block fires once per "new" ingredient — not every tick during a
     * grinding loop where the same item type keeps stacking.
     *
     * Returns an empty string if nothing new appeared, or no recipes are
     * known for the new items.
     */
    private String buildNewIngredientRecipesBlock(GameStateSnapshot snapshot)
    {
        if (recipeRegistry == null || snapshot == null || snapshot.getInventory() == null)
        {
            return "";
        }
        java.util.Set<String> currentItems = new java.util.HashSet<>();
        if (snapshot.getInventory().getItems() != null)
        {
            for (com.osrsbot.claude.state.InventoryState.ItemSlot s : snapshot.getInventory().getItems())
            {
                if (s.getName() != null) currentItems.add(s.getName());
            }
        }
        java.util.Set<String> newlyAppeared = new java.util.LinkedHashSet<>();
        for (String n : currentItems)
        {
            if (!presentInventoryItems.contains(n))
            {
                newlyAppeared.add(n);
            }
        }
        // Update the presence set: drop items that vanished, add the new ones.
        presentInventoryItems.retainAll(currentItems);
        presentInventoryItems.addAll(currentItems);

        if (newlyAppeared.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        boolean any = false;
        for (String item : newlyAppeared)
        {
            java.util.List<RecipeRegistry.Recipe> recipes = recipeRegistry.recipesUsing(item);
            if (recipes.isEmpty()) continue;
            if (!any)
            {
                sb.append("[NEW_INGREDIENT_RECIPES] Items just added to inventory and what they can craft:\n");
                any = true;
            }
            sb.append("- ").append(item).append(":\n");
            int shown = 0;
            int maxShow = 8;
            // Sort: satisfied first (lowest level), then unsatisfied (lowest level).
            java.util.List<RecipeRegistry.Recipe> sorted = new java.util.ArrayList<>(recipes);
            sorted.sort((a, b) -> {
                boolean as = RecipeRegistry.isSatisfied(client, a);
                boolean bs = RecipeRegistry.isSatisfied(client, b);
                if (as != bs) return as ? -1 : 1;
                int al = a.skills.isEmpty() ? 1 : a.skills.get(0).level;
                int bl = b.skills.isEmpty() ? 1 : b.skills.get(0).level;
                return Integer.compare(al, bl);
            });
            for (RecipeRegistry.Recipe r : sorted)
            {
                if (shown >= maxShow) { sb.append("  +").append(sorted.size() - shown).append(" more recipes\n"); break; }
                sb.append("  ").append(formatRecipeLine(r)).append("\n");
                shown++;
            }
        }
        return any ? sb.toString() : "";
    }

    /** Formats one recipe as a single line: "✓ Iron platebody [Anvil, Smithing 33] (5x Iron bar)". */
    private String formatRecipeLine(RecipeRegistry.Recipe r)
    {
        StringBuilder sb = new StringBuilder();
        boolean satisfied = RecipeRegistry.isSatisfied(client, r);
        sb.append(satisfied ? "✓ " : "✗ ");
        if (!r.outputs.isEmpty())
        {
            sb.append(r.outputs.get(0));
        }
        else if (r.page != null)
        {
            sb.append(r.page);
        }
        // Bracketed: facility + skill gates.
        java.util.List<String> meta = new java.util.ArrayList<>();
        if (r.facilities != null && !r.facilities.isEmpty()) meta.add(r.facilities);
        for (RecipeRegistry.RecipeSkill s : r.skills)
        {
            meta.add(s.skill + " " + s.level);
        }
        if (!meta.isEmpty())
        {
            sb.append(" [").append(String.join(", ", meta)).append("]");
        }
        // Ingredients with quantity.
        if (!r.ingredients.isEmpty())
        {
            sb.append(" (");
            for (int i = 0; i < r.ingredients.size(); i++)
            {
                if (i > 0) sb.append(", ");
                RecipeRegistry.Ingredient ing = r.ingredients.get(i);
                sb.append(ing.qty).append("x ").append(ing.name);
            }
            sb.append(")");
        }
        // Membership / quest gating shown only when blocking.
        if (!satisfied)
        {
            java.util.List<String> blockers = new java.util.ArrayList<>();
            if (r.members)
            {
                try
                {
                    java.util.Set<net.runelite.api.WorldType> wt = client.getWorldType();
                    if (wt == null || !wt.contains(net.runelite.api.WorldType.MEMBERS))
                    {
                        blockers.add("members");
                    }
                }
                catch (Throwable ignored) {}
            }
            if (r.quest != null)
            {
                try
                {
                    if (r.quest.getState(client) != net.runelite.api.QuestState.FINISHED)
                    {
                        blockers.add("Q:" + r.quest.getName());
                    }
                }
                catch (Throwable ignored) {}
            }
            else if (r.questName != null && !r.questName.isEmpty())
            {
                blockers.add("Q:" + r.questName);
            }
            if (!blockers.isEmpty())
            {
                sb.append(" — needs ").append(String.join(", ", blockers));
            }
        }
        return sb.toString();
    }
}
