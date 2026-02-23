package com.osrsbot.claude.action;

import com.osrsbot.claude.ClaudeBotConfig;
import com.osrsbot.claude.action.impl.*;
import com.osrsbot.claude.human.HumanSimulator;
import com.osrsbot.claude.util.ItemUtils;
import com.osrsbot.claude.util.NpcUtils;
import com.osrsbot.claude.util.ObjectUtils;
import com.osrsbot.claude.util.TileUtils;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Singleton
public class ActionExecutor
{
    @Inject
    private Client client;

    @Inject
    private ActionQueue actionQueue;

    @Inject
    private HumanSimulator humanSimulator;

    @Inject
    private TileUtils tileUtils;

    @Inject
    private NpcUtils npcUtils;

    @Inject
    private ItemUtils itemUtils;

    @Inject
    private ObjectUtils objectUtils;

    @Inject
    private ClientThread clientThread;

    @Inject
    private ItemManager itemManager;

    @Inject
    private ClaudeBotConfig config;

    @Inject
    private com.osrsbot.claude.pathfinder.PathfinderService pathfinderService;

    private ExecutorService executor;

    private volatile BotAction currentAction;
    private volatile ActionResult lastResult;
    private final AtomicBoolean executing = new AtomicBoolean(false);

    // Collects results from all actions in a batch so Claude can see what worked/failed
    private final ConcurrentLinkedQueue<ExecutedAction> recentResults = new ConcurrentLinkedQueue<>();

    /**
     * An executed action paired with its result, for feedback to Claude.
     */
    public static class ExecutedAction
    {
        public final BotAction action;
        public final ActionResult result;

        public ExecutedAction(BotAction action, ActionResult result)
        {
            this.action = action;
            this.result = result;
        }

        public String describe()
        {
            StringBuilder sb = new StringBuilder();
            sb.append(action.getType().name());
            sb.append("(");
            boolean hasParam = false;
            if (action.getName() != null)
            {
                sb.append("name=").append(action.getName());
                hasParam = true;
            }
            if (action.getOption() != null)
            {
                if (hasParam) sb.append(",");
                sb.append("option=").append(action.getOption());
                hasParam = true;
            }
            if (action.getX() != 0 || action.getY() != 0)
            {
                if (hasParam) sb.append(",");
                sb.append("x=").append(action.getX()).append(",y=").append(action.getY());
            }
            sb.append(")");
            sb.append(" -> ");
            if (result.isSuccess())
            {
                sb.append("OK");
                if (result.getMessage() != null && !result.getMessage().isEmpty())
                {
                    sb.append(": ").append(result.getMessage());
                }
            }
            else
            {
                sb.append("FAILED: ").append(result.getMessage());
            }
            // Append parse warning (e.g. auto-corrected action ID) so LLM learns
            if (action.getParseWarning() != null)
            {
                sb.append(" [").append(action.getParseWarning()).append("]");
            }
            return sb.toString();
        }
    }

    /**
     * Ensures the executor is running. Called on first tick or after a plugin toggle.
     */
    private void ensureExecutor()
    {
        if (executor == null || executor.isShutdown())
        {
            executor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "BotActionExecutor");
                t.setDaemon(true);
                t.setUncaughtExceptionHandler((thread, throwable) -> {
                    System.err.println("[ClaudeBot] UNCAUGHT in " + thread.getName() + ": " + throwable);
                    throwable.printStackTrace(System.err);
                });
                return t;
            });
        }
    }

    /**
     * Called from game tick. If not already executing an action, dequeues the next one
     * and dispatches it to the background executor thread where it can sleep between
     * mouse movements, clicks, and menu interactions without blocking the game thread.
     */
    public void tick()
    {
        ensureExecutor();

        if (executing.get())
        {
            return;
        }

        BotAction action = actionQueue.dequeue();
        if (action == null)
        {
            return;
        }

        // CLEAR_ACTION_QUEUE: instantly clear remaining queue, no background dispatch
        if (action.getType() == ActionType.CLEAR_ACTION_QUEUE)
        {
            int cleared = actionQueue.size();
            actionQueue.clear();
            System.out.println("[ClaudeBot] CLEAR_ACTION_QUEUE: cleared " + cleared + " queued actions");
            recentResults.add(new ExecutedAction(action,
                ActionResult.success(ActionType.CLEAR_ACTION_QUEUE, "Cleared " + cleared + " queued actions")));
            return;
        }

        currentAction = action;
        executing.set(true);

        executor.submit(() -> {
            try
            {
                System.out.println("[ClaudeBot] Executing action: " + action.getType() +
                    " name=" + action.getName() + " option=" + action.getOption());
                lastResult = executeAction(action);
                recentResults.add(new ExecutedAction(action, lastResult));
                System.out.println("[ClaudeBot] Action " + action.getType() + ": " +
                    (lastResult.isSuccess() ? "OK" : "FAIL - " + lastResult.getMessage()));

                // On failure, auto-clear remaining queued actions so the LLM can re-plan
                if (!lastResult.isSuccess())
                {
                    int cleared = actionQueue.size();
                    if (cleared > 0)
                    {
                        actionQueue.clear();
                        System.out.println("[ClaudeBot] Auto-cleared " + cleared +
                            " queued actions after failure of " + action.getType());
                    }
                }

                // Humanized delay before allowing next action
                int delay = humanSimulator.getTimingEngine().nextActionDelay();
                humanSimulator.getTimingEngine().sleep(delay);
            }
            catch (Throwable t)
            {
                System.err.println("[ClaudeBot] Action " + action.getType() + " THROWABLE: " +
                    t.getClass().getName() + ": " + t.getMessage());
                t.printStackTrace(System.err);
                lastResult = ActionResult.failure(action.getType(),
                    t.getClass().getSimpleName() + ": " + t.getMessage());
                recentResults.add(new ExecutedAction(action, lastResult));

                // Auto-clear remaining queue on exception too
                int cleared = actionQueue.size();
                if (cleared > 0)
                {
                    actionQueue.clear();
                    System.out.println("[ClaudeBot] Auto-cleared " + cleared +
                        " queued actions after exception in " + action.getType());
                }
            }
            finally
            {
                currentAction = null;
                executing.set(false);
            }
        });
    }

    private ActionResult executeAction(BotAction action)
    {
        switch (action.getType())
        {
            case WALK_TO:
                return WalkToAction.execute(client, humanSimulator, tileUtils, clientThread, action);
            case INTERACT_NPC:
                return InteractNpcAction.execute(client, humanSimulator, npcUtils, clientThread, action);
            case INTERACT_OBJECT:
                return InteractObjectAction.execute(client, humanSimulator, objectUtils, clientThread, action);
            case USE_ITEM:
                return UseItemAction.execute(client, humanSimulator, itemUtils, clientThread, action);
            case EQUIP_ITEM:
                return EquipItemAction.execute(client, humanSimulator, itemUtils, clientThread, action);
            case DROP_ITEM:
                return DropItemAction.execute(client, humanSimulator, itemUtils, clientThread, action);
            case PICKUP_ITEM:
                return PickupItemAction.execute(client, humanSimulator, tileUtils, itemUtils, clientThread, action);
            case EAT_FOOD:
                return EatFoodAction.execute(client, humanSimulator, itemUtils, clientThread, action);
            case SELECT_DIALOGUE:
                return DialogueAction.execute(client, humanSimulator, clientThread, action);
            case CONTINUE_DIALOGUE:
                return DialogueAction.executeContinue(client, humanSimulator, clientThread);
            case BANK_DEPOSIT:
                return BankDepositAction.execute(client, humanSimulator, itemUtils, clientThread, action);
            case BANK_WITHDRAW:
                return BankWithdrawAction.execute(client, humanSimulator, itemUtils, clientThread, action,
                    config.minGoldReserve());
            case BANK_CLOSE:
                return BankCloseAction.execute(client, humanSimulator, clientThread);
            case TOGGLE_PRAYER:
                return TogglePrayerAction.execute(client, humanSimulator, clientThread, action);
            case TOGGLE_RUN:
                return ToggleRunAction.execute(client, humanSimulator, clientThread);
            case WAIT:
                return WaitAction.execute(action);
            case SPECIAL_ATTACK:
                return SpecialAttackAction.execute(client, humanSimulator, clientThread);
            case USE_ITEM_ON_ITEM:
                return UseItemOnItemAction.execute(client, humanSimulator, itemUtils, clientThread, action);
            case USE_ITEM_ON_NPC:
                return UseItemOnNpcAction.execute(client, humanSimulator, itemUtils, npcUtils, clientThread, action);
            case USE_ITEM_ON_OBJECT:
                return UseItemOnObjectAction.execute(client, humanSimulator, itemUtils, objectUtils, clientThread, action);
            case CLICK_WIDGET:
                return ClickWidgetAction.execute(humanSimulator, action);
            case CAST_SPELL:
                return CastSpellAction.execute(client, humanSimulator, npcUtils, itemUtils, objectUtils, clientThread, action);
            case MAKE_ITEM:
                return MakeItemAction.execute(client, humanSimulator, itemManager, clientThread, action);
            case SHOP_BUY:
                return ShopBuyAction.execute(client, humanSimulator, itemManager, clientThread, action);
            case SHOP_SELL:
                return ShopSellAction.execute(client, humanSimulator, itemManager, clientThread, action);
            case MINIMAP_WALK:
                return MinimapWalkAction.execute(client, humanSimulator, clientThread, action);
            case ROTATE_CAMERA:
                return RotateCameraAction.execute(client, humanSimulator, clientThread, action);
            case GE_BUY:
                if (!config.geEnabled())
                {
                    return ActionResult.failure(ActionType.GE_BUY,
                        "The Grand Exchange is currently unavailable. Come back in a few hours. "
                        + "Buy items from NPC shops, gather resources yourself, or find another way.");
                }
                return GeBuyAction.execute(client, humanSimulator, clientThread, action);
            case GE_SELL:
                if (!config.geEnabled())
                {
                    return ActionResult.failure(ActionType.GE_SELL,
                        "The Grand Exchange is currently unavailable. Come back in a few hours. "
                        + "Sell items to NPC shops or drop them instead.");
                }
                return GeSellAction.execute(client, humanSimulator, itemManager, clientThread, action);
            case OPEN_TAB:
                return OpenTabAction.execute(client, humanSimulator, clientThread, action);
            case TYPE_TEXT:
                return TypeTextAction.execute(humanSimulator, action);
            case UNEQUIP_ITEM:
                return UnequipItemAction.execute(client, humanSimulator, itemManager, clientThread, action);
            case PRESS_KEY:
                return PressKeyAction.execute(humanSimulator, action);
            case BANK_DEPOSIT_ALL:
                return BankDepositAllAction.execute(client, humanSimulator, clientThread);
            case SET_ATTACK_STYLE:
                return SetAttackStyleAction.execute(client, humanSimulator, clientThread, action);
            case SET_AUTOCAST:
                return SetAutocastAction.execute(client, humanSimulator, clientThread, action);
            case WORLD_HOP:
                return WorldHopAction.execute(client, humanSimulator, clientThread, action,
                    config.worldHopEnabled(), config.worldHopType());
            case PATH_TO:
                return PathToAction.execute(client, humanSimulator, pathfinderService,
                    objectUtils, clientThread, action);
            case WAIT_ANIMATION:
                return WaitAnimationAction.execute(client, humanSimulator, clientThread, action);
            default:
                return ActionResult.failure(action.getType(), "Unimplemented action type");
        }
    }

    /**
     * Returns all action results collected since the last call, then clears the list.
     * Called by ClaudeBotPlugin before sending the next query to Claude.
     */
    public List<ExecutedAction> getAndClearResults()
    {
        List<ExecutedAction> drained = new ArrayList<>();
        ExecutedAction item;
        while ((item = recentResults.poll()) != null)
        {
            drained.add(item);
        }
        return drained;
    }

    public BotAction getCurrentAction()
    {
        return currentAction;
    }

    public ActionResult getLastResult()
    {
        return lastResult;
    }

    public boolean isBusy()
    {
        return executing.get();
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
        executor = null;
        currentAction = null;
        executing.set(false);
        recentResults.clear();
    }
}
