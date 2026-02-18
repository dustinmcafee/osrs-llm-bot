package com.osrsbot.claude;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup("claudebot")
public interface ClaudeBotConfig extends Config
{
    @ConfigSection(
        name = "API Settings",
        description = "Anthropic API configuration",
        position = 0
    )
    String apiSection = "api";

    @ConfigSection(
        name = "Bot Behavior",
        description = "Bot timing and behavior settings",
        position = 1
    )
    String behaviorSection = "behavior";

    @ConfigSection(
        name = "Humanization",
        description = "Anti-detection settings",
        position = 2
    )
    String humanSection = "human";

    @ConfigSection(
        name = "Debug",
        description = "Debug and overlay settings",
        position = 3
    )
    String debugSection = "debug";

    // --- API Settings ---

    @ConfigItem(
        keyName = "apiKey",
        name = "API Key",
        description = "Anthropic API key",
        secret = true,
        section = apiSection,
        position = 0
    )
    default String apiKey()
    {
        return "";
    }

    @ConfigItem(
        keyName = "model",
        name = "Model",
        description = "Claude model to use",
        section = apiSection,
        position = 1
    )
    default String model()
    {
        return "claude-sonnet-4-5-20250929";
    }

    @ConfigItem(
        keyName = "maxTokens",
        name = "Max Tokens",
        description = "Max tokens in Claude response",
        section = apiSection,
        position = 2
    )
    default int maxTokens()
    {
        return 512;
    }

    // --- Bot Behavior ---

    @ConfigItem(
        keyName = "enabled",
        name = "Bot Enabled",
        description = "Enable or disable the bot",
        section = behaviorSection,
        position = 0
    )
    default boolean enabled()
    {
        return false;
    }

    @ConfigItem(
        keyName = "task",
        name = "Task Description",
        description = "What should the bot do? (e.g., 'Chop oak trees and bank the logs')",
        section = behaviorSection,
        position = 1
    )
    default String task()
    {
        return "Chop trees and bank the logs";
    }

    @Range(min = 3, max = 20)
    @ConfigItem(
        keyName = "tickRate",
        name = "Tick Rate",
        description = "Query Claude every N game ticks (1 tick = 0.6s)",
        section = behaviorSection,
        position = 2
    )
    default int tickRate()
    {
        return 5;
    }

    @Range(min = 1, max = 10)
    @ConfigItem(
        keyName = "contextWindowSize",
        name = "Recent Exchanges",
        description = "Number of full conversation exchanges to keep (working memory)",
        section = behaviorSection,
        position = 3
    )
    default int contextWindowSize()
    {
        return 3;
    }

    @Range(min = 4000, max = 128000)
    @ConfigItem(
        keyName = "memoryBudget",
        name = "Memory Budget (chars)",
        description = "Max characters for compressed session notes (~4 chars = 1 token). 32000 = ~30-60 min of history.",
        section = behaviorSection,
        position = 4
    )
    default int memoryBudget()
    {
        return 32000;
    }

    @ConfigItem(
        keyName = "nearbyEntityRadius",
        name = "Entity Scan Radius",
        description = "Tile radius to scan for nearby entities",
        section = behaviorSection,
        position = 5
    )
    default int nearbyEntityRadius()
    {
        return 25;
    }

    // --- Humanization ---

    @ConfigItem(
        keyName = "mouseSpeed",
        name = "Mouse Speed",
        description = "Base mouse movement speed (lower = slower)",
        section = humanSection,
        position = 0
    )
    default int mouseSpeed()
    {
        return 15;
    }

    @Range(min = 50, max = 500)
    @ConfigItem(
        keyName = "minActionDelay",
        name = "Min Action Delay (ms)",
        description = "Minimum delay between actions",
        section = humanSection,
        position = 1
    )
    default int minActionDelay()
    {
        return 80;
    }

    @Range(min = 100, max = 1000)
    @ConfigItem(
        keyName = "maxActionDelay",
        name = "Max Action Delay (ms)",
        description = "Maximum delay between actions",
        section = humanSection,
        position = 2
    )
    default int maxActionDelay()
    {
        return 300;
    }

    @ConfigItem(
        keyName = "breaksEnabled",
        name = "Breaks Enabled",
        description = "Enable AFK break scheduling",
        section = humanSection,
        position = 3
    )
    default boolean breaksEnabled()
    {
        return true;
    }

    @Range(min = 5, max = 120)
    @ConfigItem(
        keyName = "minBreakInterval",
        name = "Min Break Interval (min)",
        description = "Minimum minutes between breaks",
        section = humanSection,
        position = 4
    )
    default int minBreakInterval()
    {
        return 15;
    }

    @Range(min = 15, max = 180)
    @ConfigItem(
        keyName = "maxBreakInterval",
        name = "Max Break Interval (min)",
        description = "Maximum minutes between breaks",
        section = humanSection,
        position = 5
    )
    default int maxBreakInterval()
    {
        return 45;
    }

    @Range(min = 1, max = 60)
    @ConfigItem(
        keyName = "minBreakDuration",
        name = "Min Break Duration (min)",
        description = "Minimum break length in minutes",
        section = humanSection,
        position = 6
    )
    default int minBreakDuration()
    {
        return 5;
    }

    @Range(min = 5, max = 120)
    @ConfigItem(
        keyName = "maxBreakDuration",
        name = "Max Break Duration (min)",
        description = "Maximum break length in minutes",
        section = humanSection,
        position = 7
    )
    default int maxBreakDuration()
    {
        return 30;
    }

    // --- Debug ---

    @ConfigItem(
        keyName = "showOverlay",
        name = "Show Overlay",
        description = "Show debug overlay with bot status",
        section = debugSection,
        position = 0
    )
    default boolean showOverlay()
    {
        return true;
    }

    @ConfigItem(
        keyName = "logState",
        name = "Log Game State",
        description = "Log serialized game state to RuneLite console",
        section = debugSection,
        position = 1
    )
    default boolean logState()
    {
        return false;
    }

    @ConfigItem(
        keyName = "logApiCalls",
        name = "Log API Calls",
        description = "Log Claude API requests and responses",
        section = debugSection,
        position = 2
    )
    default boolean logApiCalls()
    {
        return false;
    }
}
