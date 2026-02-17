package com.osrsbot.claude.state;

import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
@Builder
public class GameStateSnapshot
{
    private PlayerState player;
    private InventoryState inventory;
    private EquipmentState equipment;
    private List<NearbyEntity> nearbyNpcs;
    private List<NearbyEntity> nearbyObjects;
    private List<NearbyEntity> nearbyGroundItems;
    private List<NearbyEntity> nearbyPlayers;
    private EnvironmentState environment;
    private long timestamp;
}
