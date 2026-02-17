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
    // Skills
    private int attackLevel;
    private int strengthLevel;
    private int defenceLevel;
    private int rangedLevel;
    private int magicLevel;
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
}
