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
import java.util.concurrent.CopyOnWriteArrayList;
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
    private final CopyOnWriteArrayList<ExecutedAction> recentResults = new CopyOnWriteArrayList<>();

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
            sb.append(result.isSuccess() ? "OK" : "FAILED: " + result.getMessage());
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
                return BankWithdrawAction.execute(client, humanSimulator, itemUtils, clientThread, action);
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
                return CastSpellAction.execute(client, humanSimulator, npcUtils, itemUtils, clientThread, action);
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
                return GeBuyAction.execute(client, humanSimulator, clientThread, action);
            case GE_SELL:
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
        List<ExecutedAction> snapshot = new ArrayList<>(recentResults);
        recentResults.clear();
        return snapshot;
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
