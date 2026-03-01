package com.osrsbot.claude.state;

import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
@Builder
public class EnvironmentState
{
    private int regionId;
    private String regionName;
    private int plane;
    private boolean isInInstance;
    private boolean isBankOpen;
    private boolean isDialogOpen;
    private boolean isShopOpen;
    private boolean isMakeInterfaceOpen;
    private boolean isGrandExchangeOpen;
    private int gameTickCount;
    private String loginState;

    // Dialogue details — tells the brain what the NPC/player is saying
    private String dialogueType;     // "npc_continue", "player_continue", "options", "sprite_continue"
    private String dialogueSpeaker;  // NPC name or "Player"
    private String dialogueText;     // what they're saying
    private List<String> dialogueOptions; // available choices (for option dialogues)

    // Hint arrow — the game's built-in "do this next" indicator
    // On Tutorial Island, this points at the exact NPC/object/tile to interact with
    private String hintArrowType;    // "npc", "tile", "player", or null
    private String hintArrowTarget;  // NPC name, "tile(x,y)", or player name
    private int hintArrowX;
    private int hintArrowY;

    // Tutorial Island progress (varp 281)
    // Each value maps to a specific step (e.g., 405 = "Equip bronze dagger")
    private int tutorialProgress;

    // Recent game messages — feedback like "You can't reach that", "You need to fight the rats"
    private List<String> recentGameMessages;

    // Which game tab is currently selected (0=combat, 3=inventory, 4=equipment, etc.)
    private int activeTab;
    private String activeTabName;

    // Tutorial overlay instruction text (widget group 613)
    private String tutorialInstruction;

    // Who/what the player is currently interacting with
    private String interactingWith;

    // Current world number
    private int currentWorld;

    // Poison status: negative = immune, 0 = not poisoned, >0 = poisoned, >1000000 = venomed
    private int poisonStatus;

    // Combat attack style index (0-3)
    private int attackStyleIndex;
    private String attackStyleName;

    // Auto-retaliate toggle
    private boolean autoRetaliate;

    // Quest points total
    private int questPoints;

    // Slayer task (0 = no task)
    private int slayerTaskId;
    private int slayerTaskCount;

    // Spellbook: 0=Standard, 1=Ancient, 2=Lunar, 3=Arceuus
    private int spellbookType;

    // Skull status (PvP)
    private boolean isSkulled;

    // Boosted/drained non-combat skills (e.g. "Mine+3", "WC-2")
    private List<String> boostedSkills;

    // Currently active prayers
    private List<String> activePrayers;

    // NPCs currently targeting (attacking) the player
    private List<String> attackingNpcs;

    // Bank contents (when bank is open) — grouped "ItemName(xQty)" strings
    private List<String> bankContents;
    private int bankUniqueItems;

    // Shop contents (when shop is open) — "ItemName(xQty)" strings
    private List<String> shopContents;

    // Grand Exchange active offers — "BUY/SELL ItemName qty/total @price" strings
    private List<String> geOffers;

    // Make interface items (when make/craft/smelt interface is open) — item names the player can choose
    private List<String> makeInterfaceItems;
}
