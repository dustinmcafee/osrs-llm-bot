package com.osrsbot.claude;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class PluginLauncher
{
    public static void main(String[] args) throws Exception
    {
        System.out.println("[ClaudeBot] PluginLauncher.main() - registering plugin");
        ExternalPluginManager.loadBuiltin(ClaudeBotPlugin.class);
        System.out.println("[ClaudeBot] PluginLauncher.main() - calling RuneLite.main()");
        RuneLite.main(args);
    }
}
