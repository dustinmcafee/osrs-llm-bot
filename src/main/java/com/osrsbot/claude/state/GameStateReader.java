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

        return EnvironmentState.builder()
            .regionId(regionId)
            .plane(client.getPlane())
            .isInInstance(client.isInInstancedRegion())
            .isBankOpen(client.getItemContainer(InventoryID.BANK) != null)
            .isDialogOpen(isDialogOpen())
            .isShopOpen(client.getWidget(300, 0) != null)
            .gameTickCount(client.getTickCount())
            .loginState(client.getGameState().name())
            .build();
    }

    private boolean isDialogOpen()
    {
        Widget dialogNpc = client.getWidget(WidgetInfo.DIALOG_NPC_TEXT);
        Widget dialogPlayer = client.getWidget(WidgetInfo.DIALOG_PLAYER_TEXT);
        Widget dialogOption = client.getWidget(WidgetInfo.DIALOG_OPTION_OPTIONS);
        return (dialogNpc != null && !dialogNpc.isHidden())
            || (dialogPlayer != null && !dialogPlayer.isHidden())
            || (dialogOption != null && !dialogOption.isHidden());
    }
}
