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
    /** The bank's contents as last seen by the client. Null if the player
     *  has not opened a bank since the session began (the engine doesn't
     *  populate the bank container until first interaction). May contain
     *  hundreds of items — the serializer caps display length. */
    private InventoryState bank;
    private EquipmentState equipment;
    private List<NearbyEntity> nearbyNpcs;
    private List<NearbyEntity> nearbyObjects;
    private List<NearbyEntity> nearbyGroundItems;
    private List<NearbyEntity> nearbyPlayers;
    private EnvironmentState environment;
    private long timestamp;
    /** Current Quest Helper step text, or null if Quest Helper is inactive / integration disabled. */
    private String questHelperStep;
}
