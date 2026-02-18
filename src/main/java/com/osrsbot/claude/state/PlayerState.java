package com.osrsbot.claude.state;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PlayerState
{
    private String name;
    private int combatLevel;
    private int currentHp;
    private int maxHp;
    private int currentPrayer;
    private int maxPrayer;
    private int runEnergy;
    private int specialAttackPercent;
    private boolean isRunEnabled;
    private int worldX;
    private int worldY;
    private int plane;
    private int animationId;
    private boolean isMoving;
    private boolean isInCombat;
    private boolean isIdle;
    // Skills (base levels)
    private int attackLevel;
    private int strengthLevel;
    private int defenceLevel;
    private int rangedLevel;
    private int magicLevel;
    // Boosted combat levels (includes potion boosts and stat drains)
    private int boostedAttack;
    private int boostedStrength;
    private int boostedDefence;
    private int boostedRanged;
    private int boostedMagic;
    private int hitpointsLevel;
    private int prayerLevel;
    private int woodcuttingLevel;
    private int miningLevel;
    private int fishingLevel;
    private int cookingLevel;
    private int firemakingLevel;
    private int craftingLevel;
    private int smithingLevel;
    private int fletchingLevel;
    private int slayerLevel;
    private int farmingLevel;
    private int constructionLevel;
    private int hunterLevel;
    private int agilityLevel;
    private int thievingLevel;
    private int herbloreLevel;
    private int runecraftingLevel;
    private int weight; // kg, affects run energy drain
    // XP values (raw experience points per skill)
    private int attackXp;
    private int strengthXp;
    private int defenceXp;
    private int rangedXp;
    private int magicXp;
    private int hitpointsXp;
    private int prayerXp;
    private int woodcuttingXp;
    private int miningXp;
    private int fishingXp;
    private int cookingXp;
    private int firemakingXp;
    private int craftingXp;
    private int smithingXp;
    private int fletchingXp;
    private int slayerXp;
    private int farmingXp;
    private int constructionXp;
    private int hunterXp;
    private int agilityXp;
    private int thievingXp;
    private int herbloreXp;
    private int runecraftingXp;
}
