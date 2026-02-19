package com.osrsbot.claude.state;

import javax.inject.Singleton;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Singleton
public class GameStateSerializer
{
    // Standard OSRS XP table: XP_TABLE[i] = total XP needed for level i+1 (index 0 = level 1)
    private static final int[] XP_TABLE = {
        0, 83, 174, 276, 388, 512, 650, 801, 969, 1154,           // 1-10
        1358, 1584, 1833, 2107, 2411, 2746, 3115, 3523, 3973, 4470, // 11-20
        5018, 5624, 6291, 7028, 7842, 8740, 9730, 10824, 12031, 13363, // 21-30
        14833, 16456, 18247, 20224, 22406, 24815, 27473, 30408, 33648, 37224, // 31-40
        41171, 45529, 50339, 55649, 61512, 67983, 75127, 83014, 91721, 101333, // 41-50
        111945, 123660, 136594, 150872, 166636, 184040, 203254, 224466, 247886, 273742, // 51-60
        302288, 333804, 368599, 407015, 449428, 496254, 547953, 605032, 668051, 737627, // 61-70
        814445, 899257, 992895, 1096278, 1210421, 1336443, 1475581, 1629200, 1798808, 1986068, // 71-80
        2192818, 2421087, 2673114, 2951373, 3258594, 3597792, 3972294, 4385776, 4842295, 5346332, // 81-90
        5902831, 6517253, 7195629, 7944614, 8771558, 9684577, 10692629, 11805606, 13034431 // 91-99
    };
    public String serialize(GameStateSnapshot snapshot)
    {
        StringBuilder sb = new StringBuilder();

        serializePlayer(sb, snapshot.getPlayer());
        serializeInventory(sb, snapshot.getInventory());
        serializeEquipment(sb, snapshot.getEquipment());
        serializeNearby(sb, "NEARBY_NPCS", snapshot.getNearbyNpcs());
        serializeNearby(sb, "NEARBY_OBJECTS", snapshot.getNearbyObjects());
        serializeNearby(sb, "NEARBY_GROUND_ITEMS", snapshot.getNearbyGroundItems());
        serializeNearby(sb, "NEARBY_PLAYERS", snapshot.getNearbyPlayers());
        serializeEnvironment(sb, snapshot.getEnvironment());

        return sb.toString();
    }

    private void serializePlayer(StringBuilder sb, PlayerState p)
    {
        sb.append("[PLAYER] ").append(p.getName())
            .append(" | Combat:").append(p.getCombatLevel())
            .append(" | HP:").append(p.getCurrentHp()).append("/").append(p.getMaxHp())
            .append(" | Prayer:").append(p.getCurrentPrayer()).append("/").append(p.getMaxPrayer())
            .append(" | Run:").append(p.getRunEnergy()).append("%")
            .append(p.isRunEnabled() ? " [ON]" : " [OFF]")
            .append(" | Weight:").append(p.getWeight()).append("kg")
            .append(" | SpecAtk:").append(p.getSpecialAttackPercent()).append("%")
            .append(" | Pos:(").append(p.getWorldX()).append(",").append(p.getWorldY()).append(",").append(p.getPlane()).append(")")
            .append("\n");

        sb.append("[STATUS] ");
        if (p.isIdle())
        {
            boolean hasDestination = p.getDestinationX() != 0 || p.getDestinationY() != 0;
            if (p.getStuckTicks() >= 4 && hasDestination)
            {
                sb.append("STUCK(").append(p.getStuckTicks()).append(" ticks)");
                sb.append(" intended_dest:(").append(p.getDestinationX())
                  .append(",").append(p.getDestinationY()).append(")");
                sb.append(" *** PATH BLOCKED — use PATH_TO or open nearby doors/gates ***");
            }
            else
            {
                sb.append("IDLE");
            }
        }
        else if (p.isInCombat()) sb.append("IN_COMBAT");
        else if (p.isMoving())
        {
            sb.append("MOVING");
            if (p.getDestinationX() != 0 || p.getDestinationY() != 0)
            {
                sb.append(" dest:(").append(p.getDestinationX())
                  .append(",").append(p.getDestinationY()).append(")");
            }
        }
        else sb.append("ANIMATING(").append(p.getAnimationId()).append(")");
        sb.append("\n");

        sb.append("[SKILLS] ")
            .append("Atk:").append(p.getAttackLevel())
            .append(" Str:").append(p.getStrengthLevel())
            .append(" Def:").append(p.getDefenceLevel())
            .append(" Rng:").append(p.getRangedLevel())
            .append(" Mag:").append(p.getMagicLevel())
            .append(" WC:").append(p.getWoodcuttingLevel())
            .append(" Mine:").append(p.getMiningLevel())
            .append(" Fish:").append(p.getFishingLevel())
            .append(" Cook:").append(p.getCookingLevel())
            .append(" FM:").append(p.getFiremakingLevel())
            .append(" Craft:").append(p.getCraftingLevel())
            .append(" Smith:").append(p.getSmithingLevel())
            .append(" Fletch:").append(p.getFletchingLevel())
            .append(" Slay:").append(p.getSlayerLevel())
            .append(" Farm:").append(p.getFarmingLevel())
            .append(" Con:").append(p.getConstructionLevel())
            .append(" Hunt:").append(p.getHunterLevel())
            .append(" Agi:").append(p.getAgilityLevel())
            .append(" Thiev:").append(p.getThievingLevel())
            .append(" Herb:").append(p.getHerbloreLevel())
            .append(" RC:").append(p.getRunecraftingLevel())
            .append("\n");

        // Show boosted levels only when they differ from base (potion boosts / stat drains)
        StringBuilder boosts = new StringBuilder();
        if (p.getBoostedAttack() != p.getAttackLevel()) boosts.append(" Atk:").append(p.getBoostedAttack());
        if (p.getBoostedStrength() != p.getStrengthLevel()) boosts.append(" Str:").append(p.getBoostedStrength());
        if (p.getBoostedDefence() != p.getDefenceLevel()) boosts.append(" Def:").append(p.getBoostedDefence());
        if (p.getBoostedRanged() != p.getRangedLevel()) boosts.append(" Rng:").append(p.getBoostedRanged());
        if (p.getBoostedMagic() != p.getMagicLevel()) boosts.append(" Mag:").append(p.getBoostedMagic());
        if (boosts.length() > 0)
        {
            sb.append("[BOOSTED]").append(boosts).append("\n");
        }

        // XP progress for non-zero skills
        StringBuilder xp = new StringBuilder();
        appendXp(xp, "Atk", p.getAttackXp(), p.getAttackLevel());
        appendXp(xp, "Str", p.getStrengthXp(), p.getStrengthLevel());
        appendXp(xp, "Def", p.getDefenceXp(), p.getDefenceLevel());
        appendXp(xp, "Rng", p.getRangedXp(), p.getRangedLevel());
        appendXp(xp, "Mag", p.getMagicXp(), p.getMagicLevel());
        appendXp(xp, "HP", p.getHitpointsXp(), p.getHitpointsLevel());
        appendXp(xp, "Pray", p.getPrayerXp(), p.getPrayerLevel());
        appendXp(xp, "WC", p.getWoodcuttingXp(), p.getWoodcuttingLevel());
        appendXp(xp, "Mine", p.getMiningXp(), p.getMiningLevel());
        appendXp(xp, "Fish", p.getFishingXp(), p.getFishingLevel());
        appendXp(xp, "Cook", p.getCookingXp(), p.getCookingLevel());
        appendXp(xp, "FM", p.getFiremakingXp(), p.getFiremakingLevel());
        appendXp(xp, "Craft", p.getCraftingXp(), p.getCraftingLevel());
        appendXp(xp, "Smith", p.getSmithingXp(), p.getSmithingLevel());
        appendXp(xp, "Fletch", p.getFletchingXp(), p.getFletchingLevel());
        appendXp(xp, "Slay", p.getSlayerXp(), p.getSlayerLevel());
        appendXp(xp, "Farm", p.getFarmingXp(), p.getFarmingLevel());
        appendXp(xp, "Con", p.getConstructionXp(), p.getConstructionLevel());
        appendXp(xp, "Hunt", p.getHunterXp(), p.getHunterLevel());
        appendXp(xp, "Agi", p.getAgilityXp(), p.getAgilityLevel());
        appendXp(xp, "Thiev", p.getThievingXp(), p.getThievingLevel());
        appendXp(xp, "Herb", p.getHerbloreXp(), p.getHerbloreLevel());
        appendXp(xp, "RC", p.getRunecraftingXp(), p.getRunecraftingLevel());
        if (xp.length() > 0)
        {
            sb.append("[XP]").append(xp).append("\n");
        }
    }

    private void appendXp(StringBuilder sb, String abbrev, int currentXp, int level)
    {
        if (currentXp <= 0) return;
        if (level >= 99)
        {
            sb.append(" ").append(abbrev).append(":").append(currentXp).append("(MAX)");
            return;
        }
        int nextLevelXp = XP_TABLE[level]; // XP_TABLE[level] = XP needed for level+1
        int pct = (nextLevelXp > 0) ? (currentXp * 100) / nextLevelXp : 100;
        sb.append(" ").append(abbrev).append(":").append(currentXp).append("/").append(nextLevelXp).append("(").append(pct).append("%)");
    }

    private void serializeInventory(StringBuilder sb, InventoryState inv)
    {
        sb.append("[INVENTORY] (").append(inv.getUsedSlots()).append("/28) ");
        if (inv.getItems().isEmpty())
        {
            sb.append("Empty");
        }
        else
        {
            // Group items by name for compact representation
            Map<String, Integer> grouped = inv.getItems().stream()
                .collect(Collectors.groupingBy(
                    InventoryState.ItemSlot::getName,
                    Collectors.summingInt(InventoryState.ItemSlot::getQuantity)));

            sb.append(grouped.entrySet().stream()
                .map(e -> e.getKey() + "(x" + e.getValue() + ")")
                .collect(Collectors.joining(" | ")));
        }
        sb.append("\n");
    }

    private void serializeEquipment(StringBuilder sb, EquipmentState equip)
    {
        sb.append("[EQUIPMENT] ");
        if (equip.getSlots().isEmpty())
        {
            sb.append("None");
        }
        else
        {
            sb.append(equip.getSlots().values().stream()
                .map(s -> s.getSlotName() + ":" + s.getName())
                .collect(Collectors.joining(" | ")));
        }
        sb.append("\n");
    }

    private void serializeNearby(StringBuilder sb, String label, List<NearbyEntity> entities)
    {
        if (entities == null || entities.isEmpty()) return;

        sb.append("[").append(label).append("] ");

        // Limit to top 15 closest, then deduplicate by name
        List<NearbyEntity> limited = entities.stream().limit(15).collect(Collectors.toList());

        // Group by name to deduplicate (e.g. 4x "Oak tree" at same spot)
        Map<String, List<NearbyEntity>> grouped = new java.util.LinkedHashMap<>();
        for (NearbyEntity e : limited)
        {
            String key = e.getName() + (e.getCombatLevel() > 0 ? "(lvl:" + e.getCombatLevel() + ")" : "");
            grouped.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(e);
        }

        List<String> entries = new java.util.ArrayList<>();
        for (Map.Entry<String, List<NearbyEntity>> group : grouped.entrySet())
        {
            List<NearbyEntity> list = group.getValue();
            NearbyEntity nearest = list.get(0); // already sorted by distance

            StringBuilder entry = new StringBuilder();
            if (list.size() > 1)
            {
                // Deduplicated: "Oak tree(x4) nearest:pos(3100,3200) dist:3 [Chop down]"
                entry.append(group.getKey()).append("(x").append(list.size()).append(")");
                entry.append(" nearest:pos(").append(nearest.getWorldX()).append(",").append(nearest.getWorldY()).append(")");
                entry.append(" dist:").append(nearest.getDistance());
            }
            else
            {
                // Single entity: full detail
                entry.append(group.getKey());
                entry.append(" pos:(").append(nearest.getWorldX()).append(",").append(nearest.getWorldY()).append(")");
                entry.append(" dist:").append(nearest.getDistance());
            }
            if (nearest.getQuantity() > 1) entry.append(" qty:").append(nearest.getQuantity());
            if (nearest.getHealthRatio() > 0)
            {
                int pct = (nearest.getHealthRatio() * 100) / nearest.getHealthScale();
                entry.append(" hp:").append(pct).append("%");
            }
            if (nearest.getActions() != null && !nearest.getActions().isEmpty())
            {
                entry.append(" [").append(String.join(",", nearest.getActions())).append("]");
            }
            entries.add(entry.toString());
        }

        sb.append(String.join(" | ", entries));
        sb.append("\n");
    }

    private void serializeEnvironment(StringBuilder sb, EnvironmentState env)
    {
        sb.append("[ENVIRONMENT] Region:");
        if (env.getRegionName() != null)
        {
            sb.append(env.getRegionName()).append("(").append(env.getRegionId()).append(")");
        }
        else
        {
            sb.append(env.getRegionId());
        }
        sb.append(" Plane:").append(env.getPlane())
            .append(" World:").append(env.getCurrentWorld())
            .append(" Tab:").append(env.getActiveTabName())
            .append(" Style:").append(env.getAttackStyleName());
        if (env.isInInstance()) sb.append(" [INSTANCED]");
        if (env.isBankOpen()) sb.append(" [BANK_OPEN]");
        if (env.isShopOpen()) sb.append(" [SHOP_OPEN]");
        if (env.isMakeInterfaceOpen()) sb.append(" [MAKE_INTERFACE_OPEN]");
        if (env.isGrandExchangeOpen()) sb.append(" [GE_OPEN]");
        if (env.getPoisonStatus() > 0)
        {
            if (env.getPoisonStatus() > 1000000) sb.append(" [VENOMED]");
            else sb.append(" [POISONED]");
        }
        sb.append(" Tick:").append(env.getGameTickCount());
        sb.append("\n");

        // Interaction target — who/what the player is currently engaged with
        if (env.getInteractingWith() != null)
        {
            sb.append("[INTERACTING] ").append(env.getInteractingWith()).append("\n");
        }

        // NPCs attacking the player — critical for combat awareness
        if (env.getAttackingNpcs() != null && !env.getAttackingNpcs().isEmpty())
        {
            sb.append("[UNDER_ATTACK] ");
            sb.append(String.join(", ", env.getAttackingNpcs()));
            sb.append(" *** YOU ARE BEING ATTACKED ***\n");
        }

        // Hint arrow — the game's "do this next" indicator (critical for tutorials and quests)
        if (env.getHintArrowType() != null)
        {
            sb.append("[HINT_ARROW] ").append(env.getHintArrowType());
            sb.append(" -> ").append(env.getHintArrowTarget());
            sb.append(" at (").append(env.getHintArrowX()).append(",").append(env.getHintArrowY()).append(")");
            sb.append(" *** THIS IS WHAT YOU SHOULD INTERACT WITH NEXT ***\n");
        }

        // Tutorial instruction overlay — literal text telling the player what to do
        if (env.getTutorialInstruction() != null)
        {
            sb.append("[INSTRUCTION] ").append(env.getTutorialInstruction()).append("\n");
        }

        // Tutorial island progress (0 = not on tutorial, 1000 = complete)
        if (env.getTutorialProgress() > 0 && env.getTutorialProgress() < 1000)
        {
            sb.append("[TUTORIAL_PROGRESS] step:").append(env.getTutorialProgress()).append("\n");
        }

        // Dialogue details — critical for the brain to handle conversations correctly
        if (env.isDialogOpen() && env.getDialogueType() != null)
        {
            sb.append("[DIALOGUE] type:").append(env.getDialogueType());
            if (env.getDialogueSpeaker() != null)
            {
                sb.append(" speaker:\"").append(env.getDialogueSpeaker()).append("\"");
            }
            if (env.getDialogueText() != null)
            {
                sb.append(" text:\"").append(env.getDialogueText()).append("\"");
            }
            if (env.getDialogueType().equals("npc_continue") || env.getDialogueType().equals("player_continue")
                || env.getDialogueType().equals("sprite_continue"))
            {
                sb.append(" -> Use CONTINUE_DIALOGUE to proceed");
            }
            sb.append("\n");

            if (env.getDialogueOptions() != null && !env.getDialogueOptions().isEmpty())
            {
                sb.append("[DIALOGUE_OPTIONS] ");
                sb.append(String.join(" | ", env.getDialogueOptions()));
                sb.append(" -> Use SELECT_DIALOGUE with option number\n");
            }
        }
        else if (env.isDialogOpen())
        {
            sb.append("[DIALOGUE] open (use CONTINUE_DIALOGUE)\n");
        }

        // Active prayers
        if (env.getActivePrayers() != null && !env.getActivePrayers().isEmpty())
        {
            sb.append("[ACTIVE_PRAYERS] ");
            sb.append(String.join(", ", env.getActivePrayers()));
            sb.append("\n");
        }

        // Recent game messages — feedback on what happened (failures, instructions, etc.)
        if (env.getRecentGameMessages() != null && !env.getRecentGameMessages().isEmpty())
        {
            sb.append("[GAME_MESSAGES] ");
            sb.append(String.join(" | ", env.getRecentGameMessages()));
            sb.append("\n");
        }

        // Bank contents (when bank is open)
        if (env.getBankContents() != null && !env.getBankContents().isEmpty())
        {
            sb.append("[BANK_CONTENTS] (").append(env.getBankUniqueItems()).append(" unique items) ");
            // Limit display to ~40 items to save tokens
            List<String> display = env.getBankContents().stream().limit(40).collect(Collectors.toList());
            sb.append(String.join(" | ", display));
            if (env.getBankContents().size() > 40)
            {
                sb.append(" | ... (").append(env.getBankContents().size() - 40).append(" more)");
            }
            sb.append("\n");
        }

        // Shop contents (when shop is open)
        if (env.getShopContents() != null && !env.getShopContents().isEmpty())
        {
            sb.append("[SHOP_CONTENTS] ");
            sb.append(String.join(" | ", env.getShopContents()));
            sb.append("\n");
        }

        // GE offers
        if (env.getGeOffers() != null && !env.getGeOffers().isEmpty())
        {
            sb.append("[GE_OFFERS] ");
            sb.append(String.join(" | ", env.getGeOffers()));
            sb.append("\n");
        }
    }
}
