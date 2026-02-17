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

    private int scanRadius = 15;

    public void setScanRadius(int radius)
    {
        this.scanRadius = radius;
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
        boolean isMoving = client.getLocalDestinationLocation() != null;
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
            .isInCombat(local.getInteracting() != null && local.getInteracting() instanceof NPC)
            .isIdle(isIdle)
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
                if (item.getId() != -1 && item.getId() != 0)
                {
                    String name = itemManager.getItemComposition(item.getId()).getName();
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
                    if (item.getId() != -1 && item.getId() != 0)
                    {
                        String name = itemManager.getItemComposition(item.getId()).getName();
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

                    String name = itemManager.getItemComposition(tileItem.getId()).getName();
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

        return EnvironmentState.builder()
            .regionId(regionId)
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
     * Reads recent game messages (GAMEMESSAGE type) from the chatbox.
     * These contain critical feedback like "You can't reach that",
     * "You need to fight the rats first", etc.
     */
    private List<String> readRecentGameMessages(int maxMessages)
    {
        List<String> messages = new ArrayList<>();
        try
        {
            // Iterate all messages and collect recent GAMEMESSAGE/SPAM/MESBOX types
            for (MessageNode node : client.getMessages())
            {
                ChatMessageType type = node.getType();
                if (type == ChatMessageType.GAMEMESSAGE
                    || type == ChatMessageType.SPAM
                    || type == ChatMessageType.MESBOX)
                {
                    String text = stripTags(node.getValue());
                    if (text != null && !text.isEmpty())
                    {
                        messages.add(text);
                    }
                }
                if (messages.size() >= maxMessages) break;
            }
        }
        catch (Throwable t)
        {
            // Message iteration can fail if the message buffer is being modified
        }
        return messages;
    }

    /**
     * Reads the tutorial overlay instruction text (widget group 613).
     * This is the panel that tells players what to do on Tutorial Island,
     * e.g., "Click on the door to leave" or "Now equip the bronze dagger."
     */
    private String readTutorialInstruction()
    {
        try
        {
            // Widget group 263 is the tutorial progress/instruction panel
            // Try both 263 and 613 as different RuneLite versions may use either
            for (int groupId : new int[]{ 263, 613 })
            {
                Widget overlay = client.getWidget(groupId, 0);
                if (overlay == null || overlay.isHidden()) continue;

                // Search children for non-empty text content
                StringBuilder instructionText = new StringBuilder();
                for (int childIdx = 0; childIdx < 30; childIdx++)
                {
                    Widget child = client.getWidget(groupId, childIdx);
                    if (child == null || child.isHidden()) continue;

                    String text = stripTags(child.getText());
                    if (text != null && !text.isEmpty() && text.length() > 5)
                    {
                        if (instructionText.length() > 0) instructionText.append(" ");
                        instructionText.append(text);
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
