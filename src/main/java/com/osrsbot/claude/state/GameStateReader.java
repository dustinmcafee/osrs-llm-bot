package com.osrsbot.claude.state;

import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.game.ItemManager;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Singleton
public class GameStateReader
{
    @Inject
    private Client client;

    @Inject
    private ItemManager itemManager;

    private int scanRadius = 25;

    // Region ID → human-readable name mapping
    private static final java.util.Map<Integer, String> REGION_NAMES = new java.util.HashMap<>();
    static
    {
        REGION_NAMES.put(12850, "Lumbridge");
        REGION_NAMES.put(12851, "Lumbridge East");
        REGION_NAMES.put(12594, "Lumbridge Swamp");
        REGION_NAMES.put(12595, "Lumbridge Swamp East");
        REGION_NAMES.put(12849, "Lumbridge West");
        REGION_NAMES.put(12342, "Varrock West");
        REGION_NAMES.put(12854, "Varrock East");
        REGION_NAMES.put(12853, "Varrock South");
        REGION_NAMES.put(12598, "Grand Exchange");
        REGION_NAMES.put(12597, "GE Area");
        REGION_NAMES.put(11828, "Falador");
        REGION_NAMES.put(11827, "Falador East");
        REGION_NAMES.put(12084, "Falador West");
        REGION_NAMES.put(11571, "Falador South");
        REGION_NAMES.put(13104, "Al Kharid");
        REGION_NAMES.put(13105, "Al Kharid Mine");
        REGION_NAMES.put(13106, "Shantay Pass");
        REGION_NAMES.put(12593, "Draynor Village");
        REGION_NAMES.put(12336, "Tutorial Island");
        REGION_NAMES.put(12337, "Tutorial Island");
        REGION_NAMES.put(12442, "Edgeville");
        REGION_NAMES.put(12443, "Edgeville");
        REGION_NAMES.put(11062, "Barbarian Village");
        REGION_NAMES.put(11318, "Barbarian Village");
        REGION_NAMES.put(12340, "Wizard Tower");
        REGION_NAMES.put(11826, "Rimmington");
        REGION_NAMES.put(11570, "Port Sarim");
        REGION_NAMES.put(11569, "Port Sarim Docks");
        REGION_NAMES.put(10804, "Karamja");
        REGION_NAMES.put(10805, "Karamja East");
        REGION_NAMES.put(10547, "Brimhaven");
        REGION_NAMES.put(11061, "Ice Mountain");
        REGION_NAMES.put(10548, "Catherby");
        REGION_NAMES.put(10292, "Seers Village");
        REGION_NAMES.put(10036, "Ardougne East");
        REGION_NAMES.put(9780, "Ardougne West");
        REGION_NAMES.put(9781, "Yanille");
        REGION_NAMES.put(11321, "Canifis");
        REGION_NAMES.put(13878, "Burgh de Rott");
        REGION_NAMES.put(14646, "Piscatoris");
        REGION_NAMES.put(12341, "Champions Guild");
        REGION_NAMES.put(12596, "Cooking Guild");
        REGION_NAMES.put(12338, "Lumbridge Farm");
        REGION_NAMES.put(12339, "Lumbridge Farms East");
        REGION_NAMES.put(13100, "Duel Arena");
        REGION_NAMES.put(9275, "Hosidius");
        REGION_NAMES.put(7222, "Fossil Island");
        REGION_NAMES.put(13358, "Mort Myre Swamp");
        REGION_NAMES.put(11319, "Stronghold of Security");
        REGION_NAMES.put(12855, "Varrock Palace");
        REGION_NAMES.put(11574, "Monastery");
        REGION_NAMES.put(11575, "Black Knights Fortress");
    }

    /**
     * Combat detection via hitsplat events (most reliable signal).
     * Decremented each game tick, reset to COMBAT_TIMEOUT when player takes a hit.
     * Matches RuneLite's IdleNotifierPlugin approach.
     */
    private static final int COMBAT_TIMEOUT_TICKS = 8; // slowest monster attack speed
    private int combatCountdown = 0;

    // Stuck detection: tracks position across ticks
    private int lastWorldX = -1;
    private int lastWorldY = -1;
    private int stuckTicks = 0;
    private int lastDestinationX = 0;
    private int lastDestinationY = 0;

    /**
     * Event-based game message buffer. Messages are added by the plugin via
     * onGameMessage() when ChatMessage events fire. This is far more reliable
     * than iterating client.getMessages() which has no guaranteed order.
     */
    private final ConcurrentLinkedDeque<String> gameMessageBuffer = new ConcurrentLinkedDeque<>();
    private static final int MAX_MESSAGE_BUFFER = 20;

    /**
     * Separate buffer for game messages that indicate action failure.
     * These get injected into [ACTION_RESULTS] as explicit FAILED entries.
     */
    private final ConcurrentLinkedDeque<String> failureMessageBuffer = new ConcurrentLinkedDeque<>();

    private static final String[] FAILURE_PATTERNS = {
        "I can't reach that",
        "You can't reach that",
        "Someone else is already",
        "Already under attack",
        "This rock contains no ore",
        "There is no ore currently",
        "The tree has been chopped down",
        "You can't do that",
        "Nothing interesting happens",
        "I can't do that",
        "You do not have enough",
        "You need a higher",
        "You don't have enough",
        "You can't use that",
        "That player is busy",
        "You are already under attack",
    };

    public void setScanRadius(int radius)
    {
        this.scanRadius = radius;
    }

    /**
     * Called by ClaudeBotPlugin when a ChatMessage event fires.
     * Maintains a chronological buffer of recent game messages.
     */
    public void onGameMessage(String message)
    {
        if (message == null || message.isEmpty()) return;
        gameMessageBuffer.addFirst(message);
        while (gameMessageBuffer.size() > MAX_MESSAGE_BUFFER)
        {
            gameMessageBuffer.removeLast();
        }

        // Check if this message indicates an action failure
        for (String pattern : FAILURE_PATTERNS)
        {
            if (message.contains(pattern))
            {
                failureMessageBuffer.addFirst(message);
                break;
            }
        }
    }

    /**
     * Drains all pending failure messages (game messages that indicate action failure).
     * Called by ClaudeBotPlugin to inject into [ACTION_RESULTS].
     */
    public List<String> drainFailureMessages()
    {
        List<String> failures = new ArrayList<>();
        String msg;
        while ((msg = failureMessageBuffer.pollFirst()) != null)
        {
            failures.add(msg);
        }
        return failures;
    }

    /**
     * Clears the message buffer. Called on plugin shutdown.
     */
    public void clearMessages()
    {
        gameMessageBuffer.clear();
        failureMessageBuffer.clear();
    }

    /**
     * Called by ClaudeBotPlugin when a HitsplatApplied event fires on the local player.
     * This is the most reliable combat signal — hitsplats only appear during real combat.
     */
    public void onPlayerHitsplat()
    {
        combatCountdown = COMBAT_TIMEOUT_TICKS;
    }

    /**
     * Called each game tick to decay the combat countdown.
     */
    public void onGameTick()
    {
        combatCountdown = Math.max(combatCountdown - 1, 0);

        // Stuck detection — track position across ticks
        Player local = client.getLocalPlayer();
        if (local == null)
        {
            stuckTicks = 0;
            return;
        }

        WorldPoint pos = local.getWorldLocation();
        int currentX = pos.getX();
        int currentY = pos.getY();

        // Track walking destination
        LocalPoint destLocal = client.getLocalDestinationLocation();
        if (destLocal != null)
        {
            WorldPoint destWorld = WorldPoint.fromLocal(client, destLocal);
            lastDestinationX = destWorld.getX();
            lastDestinationY = destWorld.getY();
        }

        if (currentX != lastWorldX || currentY != lastWorldY)
        {
            // Player moved — reset stuck counter
            stuckTicks = 0;
            lastWorldX = currentX;
            lastWorldY = currentY;

            // If moved and no active destination, player arrived — clear remembered dest
            if (destLocal == null)
            {
                lastDestinationX = 0;
                lastDestinationY = 0;
            }
        }
        else
        {
            // Position unchanged — check if truly idle
            boolean isAnimating = local.getAnimation() != AnimationID.IDLE;
            boolean inCombat = combatCountdown > 0;

            if (!isAnimating && !inCombat)
            {
                stuckTicks++;
            }
            else
            {
                // Stationary but doing something useful (mining, combat, etc.)
                stuckTicks = 0;
            }
        }
    }

    public GameStateSnapshot capture()
    {
        return GameStateSnapshot.builder()
            .player(readPlayerState())
            .inventory(readInventoryState())
            .equipment(readEquipmentState())
            .nearbyNpcs(readNearbyNpcs())
            .nearbyObjects(readNearbyObjects())
            .nearbyGroundItems(readNearbyGroundItems())
            .nearbyPlayers(readNearbyPlayers())
            .environment(readEnvironmentState())
            .timestamp(System.currentTimeMillis())
            .build();
    }

    private PlayerState readPlayerState()
    {
        Player local = client.getLocalPlayer();
        if (local == null)
        {
            return PlayerState.builder().name("Unknown").build();
        }

        WorldPoint pos = local.getWorldLocation();
        LocalPoint destLocal = client.getLocalDestinationLocation();
        boolean isMoving = destLocal != null;
        int destX = 0, destY = 0;
        if (destLocal != null)
        {
            WorldPoint destWorld = WorldPoint.fromLocal(client, destLocal);
            destX = destWorld.getX();
            destY = destWorld.getY();
        }

        // Multi-signal combat detection (matches RuneLite IdleNotifierPlugin approach).
        // npc.getInteracting() == local just means FACING — happens in dialogue too.
        // We use three signals, any one is sufficient:
        //
        // 1. Hitsplat countdown: player recently took a hit (definitive combat signal)
        // 2. Attackable NPC targeting player: NPC has "Attack" in its actions AND is
        //    targeting the player. Tutorial/dialogue NPCs only have "Talk-to".
        // 3. NPC health bar visible: NPC has been hit (player attacked it)
        boolean isInCombat = false;

        // Signal 1: Player recently took a hitsplat
        if (combatCountdown > 0)
        {
            isInCombat = true;
        }

        // Signal 2: Player targeting an attackable NPC that's targeting back
        if (!isInCombat)
        {
            Actor playerTarget = local.getInteracting();
            if (playerTarget instanceof NPC)
            {
                NPC targetNpc = (NPC) playerTarget;
                if (targetNpc.getInteracting() == local
                    && !targetNpc.isDead()
                    && isAttackableNpc(targetNpc))
                {
                    isInCombat = true;
                }
            }
        }

        // Signal 3: Any attackable NPC targeting the player (being attacked)
        if (!isInCombat)
        {
            for (NPC npc : client.getNpcs())
            {
                if (npc.getInteracting() == local
                    && !npc.isDead()
                    && isAttackableNpc(npc))
                {
                    isInCombat = true;
                    break;
                }
            }
        }

        // Signal 4: NPC health bar visible (player has hit the NPC)
        if (!isInCombat)
        {
            Actor playerTarget = local.getInteracting();
            if (playerTarget instanceof NPC)
            {
                NPC targetNpc = (NPC) playerTarget;
                if (!targetNpc.isDead() && targetNpc.getHealthRatio() > 0)
                {
                    isInCombat = true;
                }
            }
        }

        boolean isIdle = local.getAnimation() == AnimationID.IDLE
            && !isMoving
            && local.getInteracting() == null;

        return PlayerState.builder()
            .name(local.getName())
            .combatLevel(local.getCombatLevel())
            .currentHp(client.getBoostedSkillLevel(Skill.HITPOINTS))
            .maxHp(client.getRealSkillLevel(Skill.HITPOINTS))
            .currentPrayer(client.getBoostedSkillLevel(Skill.PRAYER))
            .maxPrayer(client.getRealSkillLevel(Skill.PRAYER))
            .runEnergy(client.getEnergy() / 100)
            .specialAttackPercent(client.getVarpValue(VarPlayer.SPECIAL_ATTACK_PERCENT) / 10)
            .isRunEnabled(client.getVarpValue(173) == 1)
            .worldX(pos.getX())
            .worldY(pos.getY())
            .plane(pos.getPlane())
            .animationId(local.getAnimation())
            .isMoving(isMoving)
            .isInCombat(isInCombat)
            .isIdle(isIdle)
            .weight(client.getWeight())
            .attackLevel(client.getRealSkillLevel(Skill.ATTACK))
            .strengthLevel(client.getRealSkillLevel(Skill.STRENGTH))
            .defenceLevel(client.getRealSkillLevel(Skill.DEFENCE))
            .rangedLevel(client.getRealSkillLevel(Skill.RANGED))
            .magicLevel(client.getRealSkillLevel(Skill.MAGIC))
            .hitpointsLevel(client.getRealSkillLevel(Skill.HITPOINTS))
            .prayerLevel(client.getRealSkillLevel(Skill.PRAYER))
            .woodcuttingLevel(client.getRealSkillLevel(Skill.WOODCUTTING))
            .miningLevel(client.getRealSkillLevel(Skill.MINING))
            .fishingLevel(client.getRealSkillLevel(Skill.FISHING))
            .cookingLevel(client.getRealSkillLevel(Skill.COOKING))
            .firemakingLevel(client.getRealSkillLevel(Skill.FIREMAKING))
            .craftingLevel(client.getRealSkillLevel(Skill.CRAFTING))
            .smithingLevel(client.getRealSkillLevel(Skill.SMITHING))
            .fletchingLevel(client.getRealSkillLevel(Skill.FLETCHING))
            .slayerLevel(client.getRealSkillLevel(Skill.SLAYER))
            .farmingLevel(client.getRealSkillLevel(Skill.FARMING))
            .constructionLevel(client.getRealSkillLevel(Skill.CONSTRUCTION))
            .hunterLevel(client.getRealSkillLevel(Skill.HUNTER))
            .agilityLevel(client.getRealSkillLevel(Skill.AGILITY))
            .thievingLevel(client.getRealSkillLevel(Skill.THIEVING))
            .herbloreLevel(client.getRealSkillLevel(Skill.HERBLORE))
            .runecraftingLevel(client.getRealSkillLevel(Skill.RUNECRAFT))
            // Boosted combat levels (includes potion boosts)
            .boostedAttack(client.getBoostedSkillLevel(Skill.ATTACK))
            .boostedStrength(client.getBoostedSkillLevel(Skill.STRENGTH))
            .boostedDefence(client.getBoostedSkillLevel(Skill.DEFENCE))
            .boostedRanged(client.getBoostedSkillLevel(Skill.RANGED))
            .boostedMagic(client.getBoostedSkillLevel(Skill.MAGIC))
            // XP values
            .attackXp(client.getSkillExperience(Skill.ATTACK))
            .strengthXp(client.getSkillExperience(Skill.STRENGTH))
            .defenceXp(client.getSkillExperience(Skill.DEFENCE))
            .rangedXp(client.getSkillExperience(Skill.RANGED))
            .magicXp(client.getSkillExperience(Skill.MAGIC))
            .hitpointsXp(client.getSkillExperience(Skill.HITPOINTS))
            .prayerXp(client.getSkillExperience(Skill.PRAYER))
            .woodcuttingXp(client.getSkillExperience(Skill.WOODCUTTING))
            .miningXp(client.getSkillExperience(Skill.MINING))
            .fishingXp(client.getSkillExperience(Skill.FISHING))
            .cookingXp(client.getSkillExperience(Skill.COOKING))
            .firemakingXp(client.getSkillExperience(Skill.FIREMAKING))
            .craftingXp(client.getSkillExperience(Skill.CRAFTING))
            .smithingXp(client.getSkillExperience(Skill.SMITHING))
            .fletchingXp(client.getSkillExperience(Skill.FLETCHING))
            .slayerXp(client.getSkillExperience(Skill.SLAYER))
            .farmingXp(client.getSkillExperience(Skill.FARMING))
            .constructionXp(client.getSkillExperience(Skill.CONSTRUCTION))
            .hunterXp(client.getSkillExperience(Skill.HUNTER))
            .agilityXp(client.getSkillExperience(Skill.AGILITY))
            .thievingXp(client.getSkillExperience(Skill.THIEVING))
            .herbloreXp(client.getSkillExperience(Skill.HERBLORE))
            .runecraftingXp(client.getSkillExperience(Skill.RUNECRAFT))
            // Stuck detection
            .stuckTicks(stuckTicks)
            .destinationX(destX != 0 ? destX : lastDestinationX)
            .destinationY(destY != 0 ? destY : lastDestinationY)
            .build();
    }

    private InventoryState readInventoryState()
    {
        ItemContainer container = client.getItemContainer(InventoryID.INVENTORY);
        List<InventoryState.ItemSlot> items = new ArrayList<>();
        int usedSlots = 0;

        if (container != null)
        {
            Item[] containerItems = container.getItems();
            for (int i = 0; i < containerItems.length && i < 28; i++)
            {
                Item item = containerItems[i];
                if (item == null) continue;
                if (item.getId() != -1 && item.getId() != 0)
                {
                    net.runelite.api.ItemComposition comp = itemManager.getItemComposition(item.getId());
                    String name = comp != null ? comp.getName() : "Unknown";
                    items.add(InventoryState.ItemSlot.builder()
                        .slot(i)
                        .itemId(item.getId())
                        .name(name)
                        .quantity(item.getQuantity())
                        .build());
                    usedSlots++;
                }
            }
        }

        return InventoryState.builder()
            .items(items)
            .usedSlots(usedSlots)
            .freeSlots(28 - usedSlots)
            .build();
    }

    private EquipmentState readEquipmentState()
    {
        Map<String, EquipmentState.EquipmentSlot> slots = new LinkedHashMap<>();
        ItemContainer container = client.getItemContainer(InventoryID.EQUIPMENT);

        String[] slotNames = {"Head", "Cape", "Amulet", "Weapon", "Body", "Shield",
            "Legs", "Gloves", "Boots", "Ring", "Ammo"};
        int[] slotIndices = {
            EquipmentInventorySlot.HEAD.getSlotIdx(),
            EquipmentInventorySlot.CAPE.getSlotIdx(),
            EquipmentInventorySlot.AMULET.getSlotIdx(),
            EquipmentInventorySlot.WEAPON.getSlotIdx(),
            EquipmentInventorySlot.BODY.getSlotIdx(),
            EquipmentInventorySlot.SHIELD.getSlotIdx(),
            EquipmentInventorySlot.LEGS.getSlotIdx(),
            EquipmentInventorySlot.GLOVES.getSlotIdx(),
            EquipmentInventorySlot.BOOTS.getSlotIdx(),
            EquipmentInventorySlot.RING.getSlotIdx(),
            EquipmentInventorySlot.AMMO.getSlotIdx()
        };

        if (container != null)
        {
            Item[] items = container.getItems();
            for (int i = 0; i < slotNames.length; i++)
            {
                if (slotIndices[i] < items.length)
                {
                    Item item = items[slotIndices[i]];
                    if (item == null) continue;
                    if (item.getId() != -1 && item.getId() != 0)
                    {
                        net.runelite.api.ItemComposition comp = itemManager.getItemComposition(item.getId());
                        String name = comp != null ? comp.getName() : "Unknown";
                        slots.put(slotNames[i], EquipmentState.EquipmentSlot.builder()
                            .slotName(slotNames[i])
                            .itemId(item.getId())
                            .name(name)
                            .quantity(item.getQuantity())
                            .build());
                    }
                }
            }
        }

        return EquipmentState.builder().slots(slots).build();
    }

    private List<NearbyEntity> readNearbyNpcs()
    {
        Player local = client.getLocalPlayer();
        if (local == null) return Collections.emptyList();

        WorldPoint playerPos = local.getWorldLocation();
        return client.getNpcs().stream()
            .filter(npc -> npc.getWorldLocation().distanceTo(playerPos) <= scanRadius)
            .map(npc -> {
                WorldPoint npcPos = npc.getWorldLocation();
                NPCComposition comp = npc.getTransformedComposition();
                List<String> actions = new ArrayList<>();
                if (comp != null)
                {
                    for (String action : comp.getActions())
                    {
                        if (action != null) actions.add(action);
                    }
                }
                return NearbyEntity.builder()
                    .type(NearbyEntity.EntityType.NPC)
                    .id(npc.getId())
                    .name(npc.getName())
                    .worldX(npcPos.getX())
                    .worldY(npcPos.getY())
                    .distance(npcPos.distanceTo(playerPos))
                    .healthRatio(npc.getHealthRatio())
                    .healthScale(npc.getHealthScale())
                    .actions(actions)
                    .combatLevel(npc.getCombatLevel())
                    .animationId(npc.getAnimation())
                    .build();
            })
            .sorted(Comparator.comparingInt(NearbyEntity::getDistance))
            .collect(Collectors.toList());
    }

    private List<NearbyEntity> readNearbyObjects()
    {
        Player local = client.getLocalPlayer();
        if (local == null) return Collections.emptyList();

        WorldPoint playerPos = local.getWorldLocation();
        Scene scene = client.getScene();
        Tile[][][] tiles = scene.getTiles();
        int plane = client.getPlane();
        List<NearbyEntity> entities = new ArrayList<>();

        for (int x = 0; x < Constants.SCENE_SIZE; x++)
        {
            for (int y = 0; y < Constants.SCENE_SIZE; y++)
            {
                Tile tile = tiles[plane][x][y];
                if (tile == null) continue;

                for (GameObject obj : tile.getGameObjects())
                {
                    if (obj == null) continue;
                    addObjectEntity(obj, playerPos, entities);
                }

                if (tile.getWallObject() != null)
                {
                    addObjectEntity(tile.getWallObject(), playerPos, entities);
                }
                if (tile.getDecorativeObject() != null)
                {
                    addObjectEntity(tile.getDecorativeObject(), playerPos, entities);
                }
                if (tile.getGroundObject() != null)
                {
                    addObjectEntity(tile.getGroundObject(), playerPos, entities);
                }
            }
        }

        return entities.stream()
            .sorted(Comparator.comparingInt(NearbyEntity::getDistance))
            .collect(Collectors.toList());
    }

    private void addObjectEntity(TileObject obj, WorldPoint playerPos, List<NearbyEntity> entities)
    {
        WorldPoint objPos = obj.getWorldLocation();
        int dist = objPos.distanceTo(playerPos);
        if (dist > scanRadius) return;

        ObjectComposition comp = client.getObjectDefinition(obj.getId());
        if (comp == null) return;

        // Skip unnamed objects
        String name = comp.getName();
        if (name == null || name.equals("null") || name.isEmpty()) return;

        // Handle transformations
        if (comp.getImpostorIds() != null)
        {
            ObjectComposition impostor = comp.getImpostor();
            if (impostor != null)
            {
                comp = impostor;
                name = comp.getName();
            }
        }

        List<String> actions = new ArrayList<>();
        for (String action : comp.getActions())
        {
            if (action != null) actions.add(action);
        }
        if (actions.isEmpty()) return;

        entities.add(NearbyEntity.builder()
            .type(NearbyEntity.EntityType.OBJECT)
            .id(obj.getId())
            .name(name)
            .worldX(objPos.getX())
            .worldY(objPos.getY())
            .distance(dist)
            .healthRatio(-1)
            .actions(actions)
            .build());
    }

    private List<NearbyEntity> readNearbyGroundItems()
    {
        Player local = client.getLocalPlayer();
        if (local == null) return Collections.emptyList();

        WorldPoint playerPos = local.getWorldLocation();
        Scene scene = client.getScene();
        Tile[][][] tiles = scene.getTiles();
        int plane = client.getPlane();
        List<NearbyEntity> entities = new ArrayList<>();

        for (int x = 0; x < Constants.SCENE_SIZE; x++)
        {
            for (int y = 0; y < Constants.SCENE_SIZE; y++)
            {
                Tile tile = tiles[plane][x][y];
                if (tile == null || tile.getGroundItems() == null) continue;

                for (TileItem tileItem : tile.getGroundItems())
                {
                    WorldPoint itemPos = tile.getWorldLocation();
                    int dist = itemPos.distanceTo(playerPos);
                    if (dist > scanRadius) continue;

                    net.runelite.api.ItemComposition comp = itemManager.getItemComposition(tileItem.getId());
                    String name = comp != null ? comp.getName() : "Unknown";
                    entities.add(NearbyEntity.builder()
                        .type(NearbyEntity.EntityType.GROUND_ITEM)
                        .id(tileItem.getId())
                        .name(name)
                        .worldX(itemPos.getX())
                        .worldY(itemPos.getY())
                        .distance(dist)
                        .healthRatio(-1)
                        .quantity(tileItem.getQuantity())
                        .actions(Arrays.asList("Take"))
                        .build());
                }
            }
        }

        return entities.stream()
            .sorted(Comparator.comparingInt(NearbyEntity::getDistance))
            .collect(Collectors.toList());
    }

    private List<NearbyEntity> readNearbyPlayers()
    {
        Player local = client.getLocalPlayer();
        if (local == null) return Collections.emptyList();

        WorldPoint playerPos = local.getWorldLocation();
        return client.getPlayers().stream()
            .filter(p -> p != local)
            .filter(p -> p.getWorldLocation().distanceTo(playerPos) <= scanRadius)
            .map(p -> {
                WorldPoint pPos = p.getWorldLocation();
                return NearbyEntity.builder()
                    .type(NearbyEntity.EntityType.PLAYER)
                    .name(p.getName())
                    .worldX(pPos.getX())
                    .worldY(pPos.getY())
                    .distance(pPos.distanceTo(playerPos))
                    .healthRatio(p.getHealthRatio())
                    .healthScale(p.getHealthScale())
                    .combatLevel(p.getCombatLevel())
                    .animationId(p.getAnimation())
                    .actions(Arrays.asList("Follow", "Trade with"))
                    .build();
            })
            .sorted(Comparator.comparingInt(NearbyEntity::getDistance))
            .collect(Collectors.toList());
    }

    private EnvironmentState readEnvironmentState()
    {
        Player local = client.getLocalPlayer();
        int regionId = 0;
        if (local != null)
        {
            regionId = local.getWorldLocation().getRegionID();
        }

        // Read dialogue state with full details
        DialogueInfo dialogueInfo = readDialogueInfo();

        // Check for make/smithing interfaces (groups 270, 312, 446)
        boolean isMakeOpen = isWidgetGroupVisible(270)
            || isWidgetGroupVisible(312)
            || isWidgetGroupVisible(446);

        // Check for Grand Exchange (group 465)
        boolean isGeOpen = isWidgetGroupVisible(465);

        // Read hint arrow — the game's "do this next" indicator
        HintArrowInfo hintArrow = readHintArrow();

        // Tutorial Island progress (varp 281)
        int tutorialProgress = client.getVarpValue(281);

        // Recent game messages — feedback on what happened
        List<String> recentMessages = readRecentGameMessages(8);

        // Active game tab (varc int 171)
        int activeTab = client.getVarcIntValue(171);

        // Tutorial overlay instruction text (widget group 613)
        String tutorialInstruction = readTutorialInstruction();

        // Who the player is currently interacting with
        String interactingWith = null;
        if (local != null && local.getInteracting() != null)
        {
            Actor target = local.getInteracting();
            if (target instanceof NPC)
            {
                interactingWith = ((NPC) target).getName();
            }
            else if (target instanceof Player)
            {
                interactingWith = "Player:" + target.getName();
            }
        }

        // Current world
        int currentWorld = client.getWorld();

        // Poison status (VarPlayer 102): negative = immune, 0 = clean, >0 = poisoned/venomed
        int poisonStatus = client.getVarpValue(102);

        // Attack style (VarPlayer 43): 0-3 maps to combat style buttons
        int attackStyleIndex = client.getVarpValue(43);

        // Active prayers — search prayer widget actions for "Deactivate" prefix
        List<String> activePrayers = readActivePrayers();

        // NPCs targeting (attacking) the player
        List<String> attackingNpcs = readAttackingNpcs(local);

        // Region name lookup
        String regionName = REGION_NAMES.getOrDefault(regionId, null);

        // Shop contents (when shop is open)
        List<String> shopContents = null;
        if (isWidgetGroupVisible(300))
        {
            shopContents = readShopContents();
        }

        // GE offer status
        List<String> geOffers = null;
        if (isGeOpen)
        {
            geOffers = readGeOffers();
        }

        // Bank contents (when bank is open)
        List<String> bankContents = null;
        int bankUniqueItems = 0;
        ItemContainer bankContainer = client.getItemContainer(InventoryID.BANK);
        if (bankContainer != null)
        {
            Map<String, Integer> bankGrouped = new LinkedHashMap<>();
            Item[] bankItems = bankContainer.getItems();
            for (Item item : bankItems)
            {
                if (item == null || item.getId() == -1 || item.getId() == 0) continue;
                net.runelite.api.ItemComposition comp = itemManager.getItemComposition(item.getId());
                String itemName = comp != null ? comp.getName() : "Unknown";
                bankGrouped.merge(itemName, item.getQuantity(), Integer::sum);
            }
            bankUniqueItems = bankGrouped.size();
            bankContents = new ArrayList<>();
            int count = 0;
            for (Map.Entry<String, Integer> entry : bankGrouped.entrySet())
            {
                bankContents.add(entry.getKey() + "(x" + entry.getValue() + ")");
                if (++count >= 100) break;
            }
        }

        return EnvironmentState.builder()
            .regionId(regionId)
            .regionName(regionName)
            .plane(client.getPlane())
            .isInInstance(client.isInInstancedRegion())
            .isBankOpen(client.getItemContainer(InventoryID.BANK) != null)
            .isDialogOpen(dialogueInfo.isOpen)
            .isShopOpen(isWidgetGroupVisible(300))
            .isMakeInterfaceOpen(isMakeOpen)
            .isGrandExchangeOpen(isGeOpen)
            .gameTickCount(client.getTickCount())
            .loginState(client.getGameState().name())
            .dialogueType(dialogueInfo.type)
            .dialogueSpeaker(dialogueInfo.speaker)
            .dialogueText(dialogueInfo.text)
            .dialogueOptions(dialogueInfo.options)
            .hintArrowType(hintArrow.type)
            .hintArrowTarget(hintArrow.target)
            .hintArrowX(hintArrow.x)
            .hintArrowY(hintArrow.y)
            .tutorialProgress(tutorialProgress)
            .recentGameMessages(recentMessages)
            .activeTab(activeTab)
            .activeTabName(getTabName(activeTab))
            .tutorialInstruction(tutorialInstruction)
            .interactingWith(interactingWith)
            .currentWorld(currentWorld)
            .poisonStatus(poisonStatus)
            .attackStyleIndex(attackStyleIndex)
            .attackStyleName(getAttackStyleName(attackStyleIndex))
            .activePrayers(activePrayers)
            .attackingNpcs(attackingNpcs)
            .bankContents(bankContents)
            .bankUniqueItems(bankUniqueItems)
            .shopContents(shopContents)
            .geOffers(geOffers)
            .build();
    }

    /**
     * Reads the hint arrow — OSRS's built-in "do this next" indicator.
     * On Tutorial Island this points at the exact NPC/object/tile to interact with.
     * Also appears during quests, random events, etc.
     */
    private HintArrowInfo readHintArrow()
    {
        HintArrowInfo info = new HintArrowInfo();
        try
        {
            if (!client.hasHintArrow()) return info;

            int arrowType = client.getHintArrowType();

            if (arrowType == HintArrowType.NPC)
            {
                NPC npc = client.getHintArrowNpc();
                if (npc != null)
                {
                    info.type = "npc";
                    info.target = npc.getName();
                    WorldPoint pos = npc.getWorldLocation();
                    info.x = pos.getX();
                    info.y = pos.getY();
                }
            }
            else if (arrowType == HintArrowType.COORDINATE)
            {
                WorldPoint point = client.getHintArrowPoint();
                if (point != null)
                {
                    info.type = "tile";
                    info.target = "tile(" + point.getX() + "," + point.getY() + ")";
                    info.x = point.getX();
                    info.y = point.getY();
                }
            }
            else if (arrowType == HintArrowType.PLAYER)
            {
                Player player = client.getHintArrowPlayer();
                if (player != null)
                {
                    info.type = "player";
                    info.target = player.getName();
                    WorldPoint pos = player.getWorldLocation();
                    info.x = pos.getX();
                    info.y = pos.getY();
                }
            }
        }
        catch (Throwable t)
        {
            // Hint arrow API may not be available in all RuneLite versions
        }
        return info;
    }

    /**
     * Drains recent game messages from the event-based buffer.
     * Each call returns only NEW messages since the last call, preventing
     * stale messages like "I can't reach that" from repeating on every tick.
     */
    private List<String> readRecentGameMessages(int maxMessages)
    {
        List<String> messages = new ArrayList<>();
        String msg;
        while ((msg = gameMessageBuffer.pollFirst()) != null)
        {
            messages.add(msg);
            if (messages.size() >= maxMessages) break;
        }
        // Discard any remaining messages beyond the limit
        gameMessageBuffer.clear();
        return messages;
    }

    /**
     * Reads the tutorial overlay instruction text.
     * Searches multiple widget groups that can contain tutorial instructions
     * in different OSRS/RuneLite versions.
     */
    private String readTutorialInstruction()
    {
        try
        {
            // Widget groups that may contain tutorial instructions:
            // 263 = tutorial progress panel, 613 = alternative tutorial overlay, 664 = newer tutorial
            for (int groupId : new int[]{ 263, 613, 664 })
            {
                Widget overlay = client.getWidget(groupId, 0);
                if (overlay == null || overlay.isHidden()) continue;

                // Search direct children for non-empty text content
                StringBuilder instructionText = new StringBuilder();
                for (int childIdx = 0; childIdx < 30; childIdx++)
                {
                    Widget child = client.getWidget(groupId, childIdx);
                    if (child == null || child.isHidden()) continue;

                    String text = stripTags(child.getText());
                    if (text != null && !text.isEmpty() && text.length() > 2)
                    {
                        if (instructionText.length() > 0) instructionText.append(" ");
                        instructionText.append(text);
                    }

                    // Also search nested children (some tutorial widgets nest text)
                    Widget[] nested = child.getChildren();
                    if (nested != null)
                    {
                        for (Widget n : nested)
                        {
                            if (n == null || n.isHidden()) continue;
                            String nText = stripTags(n.getText());
                            if (nText != null && !nText.isEmpty() && nText.length() > 2)
                            {
                                if (instructionText.length() > 0) instructionText.append(" ");
                                instructionText.append(nText);
                            }
                        }
                    }
                }

                if (instructionText.length() > 0)
                {
                    return instructionText.toString();
                }
            }
        }
        catch (Throwable t)
        {
            // Widget may not exist
        }
        return null;
    }

    private static String getTabName(int tabIndex)
    {
        switch (tabIndex)
        {
            case 0: return "Combat";
            case 1: return "Skills";
            case 2: return "Quests";
            case 3: return "Inventory";
            case 4: return "Equipment";
            case 5: return "Prayer";
            case 6: return "Spellbook";
            case 7: return "Friends Chat";
            case 8: return "Friends List";
            case 9: return "Ignore List";
            case 10: return "Logout";
            case 11: return "Settings";
            case 12: return "Emotes";
            case 13: return "Music";
            default: return "Unknown(" + tabIndex + ")";
        }
    }

    /**
     * Finds all NPCs currently targeting (attacking) the local player.
     * This lets the brain know when the player is under attack, even if
     * the player hasn't targeted them back.
     */
    private List<String> readAttackingNpcs(Player local)
    {
        List<String> attackers = new ArrayList<>();
        if (local == null) return attackers;
        try
        {
            for (NPC npc : client.getNpcs())
            {
                if (npc.getInteracting() == local
                    && npc.getName() != null
                    && !npc.isDead()
                    && isAttackableNpc(npc))
                {
                    attackers.add(npc.getName() + "(lvl:" + npc.getCombatLevel() + ")");
                }
            }
        }
        catch (Throwable t)
        {
            // NPC iteration can fail during world loading
        }
        return attackers;
    }

    /**
     * Reads items from the shop interface when it's open (widget group 300, child 16).
     */
    private List<String> readShopContents()
    {
        List<String> contents = new ArrayList<>();
        try
        {
            Widget shopContainer = client.getWidget(300, 16);
            if (shopContainer == null || shopContainer.isHidden()) return contents;

            Widget[] children = shopContainer.getDynamicChildren();
            if (children == null) return contents;

            for (Widget child : children)
            {
                if (child == null || child.getItemId() <= 0) continue;
                net.runelite.api.ItemComposition comp = itemManager.getItemComposition(child.getItemId());
                String name = comp != null ? comp.getName() : "Unknown";
                int qty = child.getItemQuantity();
                contents.add(name + "(x" + qty + ")");
            }
        }
        catch (Throwable t)
        {
            // Shop widget may not be available
        }
        return contents;
    }

    /**
     * Reads Grand Exchange offer status via client API.
     */
    private List<String> readGeOffers()
    {
        List<String> offers = new ArrayList<>();
        try
        {
            GrandExchangeOffer[] geOffers = client.getGrandExchangeOffers();
            if (geOffers == null) return offers;

            for (int i = 0; i < geOffers.length; i++)
            {
                GrandExchangeOffer offer = geOffers[i];
                if (offer == null || offer.getState() == GrandExchangeOfferState.EMPTY) continue;

                String state = offer.getState().name();
                net.runelite.api.ItemComposition comp = itemManager.getItemComposition(offer.getItemId());
                String name = comp != null ? comp.getName() : "ID:" + offer.getItemId();
                int transferred = offer.getQuantitySold();
                int total = offer.getTotalQuantity();
                int price = offer.getPrice();

                offers.add(state + " " + name + " " + transferred + "/" + total + " @" + price + "gp");
            }
        }
        catch (Throwable t)
        {
            // GE API may not be available in all versions
        }
        return offers;
    }

    /**
     * Searches prayer widget children for actions starting with "Deactivate"
     * to determine which prayers are currently active.
     */
    private List<String> readActivePrayers()
    {
        List<String> active = new ArrayList<>();
        try
        {
            for (int childIdx = 0; childIdx < 50; childIdx++)
            {
                Widget child = client.getWidget(541, childIdx);
                if (child == null || child.isHidden()) continue;

                String[] actions = child.getActions();
                if (actions != null)
                {
                    for (String act : actions)
                    {
                        if (act != null && act.startsWith("Deactivate "))
                        {
                            active.add(act.substring("Deactivate ".length()));
                        }
                    }
                }
            }
        }
        catch (Throwable t)
        {
            // Prayer widget may not be available
        }
        return active;
    }

    /**
     * Maps attack style index (VarPlayer 43) to a human-readable name.
     * Note: actual names depend on weapon type, these are generic labels.
     */
    private static String getAttackStyleName(int index)
    {
        switch (index)
        {
            case 0: return "Accurate";
            case 1: return "Aggressive";
            case 2: return "Controlled";
            case 3: return "Defensive";
            default: return "Unknown(" + index + ")";
        }
    }

    private static class HintArrowInfo
    {
        String type = null;
        String target = null;
        int x = 0;
        int y = 0;
    }

    private boolean isWidgetGroupVisible(int groupId)
    {
        Widget w = client.getWidget(groupId, 0);
        return w != null && !w.isHidden();
    }

    /**
     * Checks if an NPC has "Attack" in its right-click actions.
     * This is how RuneLite's IdleNotifierPlugin distinguishes combat NPCs from
     * dialogue/tutorial NPCs. Shopkeepers, instructors, and quest NPCs only
     * have "Talk-to" — they never have "Attack".
     */
    private static boolean isAttackableNpc(NPC npc)
    {
        NPCComposition comp = npc.getTransformedComposition();
        if (comp == null) return false;
        String[] actions = comp.getActions();
        if (actions == null) return false;
        for (String action : actions)
        {
            if ("Attack".equals(action)) return true;
        }
        return false;
    }

    /**
     * Reads the current dialogue state, including who is speaking, what they're saying,
     * and what options are available. This gives the brain enough context to handle
     * multi-step dialogues without blindly looping.
     */
    private DialogueInfo readDialogueInfo()
    {
        DialogueInfo info = new DialogueInfo();

        // Check for NPC dialogue (group 231)
        Widget dialogNpc = client.getWidget(WidgetInfo.DIALOG_NPC_TEXT);
        if (dialogNpc != null && !dialogNpc.isHidden())
        {
            info.isOpen = true;
            info.type = "npc_continue";
            info.text = stripTags(dialogNpc.getText());

            // Get NPC name from widget 231, child 4
            Widget npcNameWidget = client.getWidget(231, 4);
            if (npcNameWidget != null)
            {
                info.speaker = stripTags(npcNameWidget.getText());
            }
            return info;
        }

        // Check for Player dialogue (group 217)
        Widget dialogPlayer = client.getWidget(WidgetInfo.DIALOG_PLAYER_TEXT);
        if (dialogPlayer != null && !dialogPlayer.isHidden())
        {
            info.isOpen = true;
            info.type = "player_continue";
            info.speaker = "Player";
            info.text = stripTags(dialogPlayer.getText());
            return info;
        }

        // Check for dialogue options (group 219)
        Widget dialogOption = client.getWidget(WidgetInfo.DIALOG_OPTION_OPTIONS);
        if (dialogOption != null && !dialogOption.isHidden())
        {
            info.isOpen = true;
            info.type = "options";

            Widget[] children = dialogOption.getChildren();
            if (children != null)
            {
                info.options = new ArrayList<>();
                // Child 0 is always the title "Select an Option" — skip it.
                // Real options start at child 1. Label them 1-indexed to match
                // DialogueAction which accesses children[idx] directly.
                for (int i = 1; i < children.length; i++)
                {
                    Widget child = children[i];
                    if (child != null)
                    {
                        String optionText = stripTags(child.getText());
                        if (optionText != null && !optionText.isEmpty())
                        {
                            info.options.add(i + ": " + optionText);
                        }
                    }
                }
            }
            return info;
        }

        // Check for chatbox "Click here to continue" sprite dialogue (group 229)
        Widget spriteDialog = client.getWidget(229, 2);
        if (spriteDialog != null && !spriteDialog.isHidden())
        {
            info.isOpen = true;
            info.type = "sprite_continue";
            info.text = stripTags(spriteDialog.getText());
            return info;
        }

        return info;
    }

    private String stripTags(String text)
    {
        if (text == null) return null;
        return text.replaceAll("<[^>]+>", "").trim();
    }

    /**
     * Holds dialogue state details.
     */
    private static class DialogueInfo
    {
        boolean isOpen = false;
        String type = null;       // "npc_continue", "player_continue", "options", "sprite_continue"
        String speaker = null;
        String text = null;
        List<String> options = null;
    }
}
