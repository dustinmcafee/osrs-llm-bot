package com.osrsbot.claude.state;

import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
@Builder
public class NearbyEntity
{
    public enum EntityType
    {
        NPC, OBJECT, GROUND_ITEM, PLAYER
    }

    private EntityType type;
    private int id;
    private String name;
    private int worldX;
    private int worldY;
    private int distance;
    private int healthRatio;  // -1 if not in combat
    private int healthScale;
    private List<String> actions;
    private int quantity;     // for ground items
    private int combatLevel;  // for NPCs/players
    private int animationId;
}
