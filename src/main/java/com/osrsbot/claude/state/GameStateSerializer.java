package com.osrsbot.claude.state;

import javax.inject.Singleton;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Singleton
public class GameStateSerializer
{
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
            .append(" | SpecAtk:").append(p.getSpecialAttackPercent()).append("%")
            .append(" | Pos:(").append(p.getWorldX()).append(",").append(p.getWorldY()).append(",").append(p.getPlane()).append(")")
            .append("\n");

        sb.append("[STATUS] ");
        if (p.isIdle()) sb.append("IDLE");
        else if (p.isInCombat()) sb.append("IN_COMBAT");
        else if (p.isMoving()) sb.append("MOVING");
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
        // Limit to top 10 closest to save tokens
        List<NearbyEntity> limited = entities.stream().limit(10).collect(Collectors.toList());
        sb.append(limited.stream()
            .map(e -> {
                StringBuilder entry = new StringBuilder();
                entry.append(e.getName());
                if (e.getCombatLevel() > 0) entry.append("(lvl:").append(e.getCombatLevel()).append(")");
                entry.append(" dist:").append(e.getDistance());
                if (e.getQuantity() > 1) entry.append(" qty:").append(e.getQuantity());
                if (e.getHealthRatio() > 0)
                {
                    int pct = (e.getHealthRatio() * 100) / e.getHealthScale();
                    entry.append(" hp:").append(pct).append("%");
                }
                if (e.getActions() != null && !e.getActions().isEmpty())
                {
                    entry.append(" [").append(String.join(",", e.getActions())).append("]");
                }
                return entry.toString();
            })
            .collect(Collectors.joining(" | ")));
        sb.append("\n");
    }

    private void serializeEnvironment(StringBuilder sb, EnvironmentState env)
    {
        sb.append("[ENVIRONMENT] Region:").append(env.getRegionId())
            .append(" Plane:").append(env.getPlane())
            .append(" Tab:").append(env.getActiveTabName());
        if (env.isInInstance()) sb.append(" [INSTANCED]");
        if (env.isBankOpen()) sb.append(" [BANK_OPEN]");
        if (env.isShopOpen()) sb.append(" [SHOP_OPEN]");
        if (env.isMakeInterfaceOpen()) sb.append(" [MAKE_INTERFACE_OPEN]");
        if (env.isGrandExchangeOpen()) sb.append(" [GE_OPEN]");
        sb.append(" Tick:").append(env.getGameTickCount());
        sb.append("\n");

        // Interaction target — who/what the player is currently engaged with
        if (env.getInteractingWith() != null)
        {
            sb.append("[INTERACTING] ").append(env.getInteractingWith()).append("\n");
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

        // Recent game messages — feedback on what happened (failures, instructions, etc.)
        if (env.getRecentGameMessages() != null && !env.getRecentGameMessages().isEmpty())
        {
            sb.append("[GAME_MESSAGES] ");
            sb.append(String.join(" | ", env.getRecentGameMessages()));
            sb.append("\n");
        }
    }
}
