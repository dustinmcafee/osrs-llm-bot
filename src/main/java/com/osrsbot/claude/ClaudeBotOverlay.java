package com.osrsbot.claude;

import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.PanelComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

import javax.inject.Inject;
import java.awt.*;

public class ClaudeBotOverlay extends Overlay
{
    private final ClaudeBotPlugin plugin;
    private final ClaudeBotConfig config;
    private final PanelComponent panelComponent = new PanelComponent();

    @Inject
    public ClaudeBotOverlay(ClaudeBotPlugin plugin, ClaudeBotConfig config)
    {
        this.plugin = plugin;
        this.config = config;
        setPosition(OverlayPosition.TOP_LEFT);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
        setPriority(OverlayPriority.HIGH);
    }

    private static final int PANEL_WIDTH = 480;
    private static final int CHARS_PER_LINE = 58;
    private static final int MAX_THINKING_LINES = 12;

    @Override
    public Dimension render(Graphics2D graphics)
    {
        try
        {
            if (!config.showOverlay())
            {
                return null;
            }

            panelComponent.getChildren().clear();

            panelComponent.getChildren().add(TitleComponent.builder()
                .text("Claude Bot")
                .color(config.enabled() ? Color.GREEN : Color.RED)
                .build());

            panelComponent.getChildren().add(LineComponent.builder()
                .left("Status:")
                .right(plugin.getBotStatus())
                .build());

            panelComponent.getChildren().add(LineComponent.builder()
                .left("Queue:")
                .right(String.valueOf(plugin.getQueueSize()))
                .build());

            panelComponent.getChildren().add(LineComponent.builder()
                .left("API:")
                .right(plugin.isAwaitingResponse() ? "Waiting..." : "Ready")
                .rightColor(plugin.isAwaitingResponse() ? Color.YELLOW : Color.GREEN)
                .build());

            // Show last actions (compact, single line)
            String lastResponse = plugin.getLastClaudeResponse();
            if (lastResponse != null && !lastResponse.isEmpty())
            {
                String truncated = lastResponse.length() > CHARS_PER_LINE
                    ? lastResponse.substring(0, CHARS_PER_LINE) + "..."
                    : lastResponse;
                panelComponent.getChildren().add(LineComponent.builder()
                    .left("Actions: " + truncated)
                    .leftColor(Color.CYAN)
                    .build());
            }

            // Show thinking/reasoning text, word-wrapped across multiple lines
            String reasoning = plugin.getLastReasoning();
            if (reasoning != null && !reasoning.isEmpty())
            {
                panelComponent.getChildren().add(LineComponent.builder()
                    .left("── Thinking ──")
                    .leftColor(new Color(180, 180, 100))
                    .build());

                // Take the last ~700 chars so we see the most recent reasoning
                String excerpt = reasoning.length() > 700
                    ? reasoning.substring(reasoning.length() - 700)
                    : reasoning;
                // Trim to a sentence boundary if possible
                int trim = excerpt.indexOf(' ');
                if (reasoning.length() > 700 && trim > 0 && trim < 20)
                {
                    excerpt = excerpt.substring(trim + 1);
                }

                for (String wrappedLine : wordWrap(excerpt, CHARS_PER_LINE))
                {
                    panelComponent.getChildren().add(LineComponent.builder()
                        .left(wrappedLine)
                        .leftColor(new Color(210, 210, 210))
                        .build());
                }
            }

            panelComponent.setPreferredSize(new Dimension(PANEL_WIDTH, 0));
            return panelComponent.render(graphics);
        }
        catch (Throwable t)
        {
            System.err.println("[ClaudeBot] Overlay render THROWABLE: " +
                t.getClass().getName() + ": " + t.getMessage());
            t.printStackTrace(System.err);
            return null;
        }
    }

    private java.util.List<String> wordWrap(String text, int lineWidth)
    {
        java.util.List<String> lines = new java.util.ArrayList<>();
        // Normalize whitespace
        String[] words = text.replaceAll("\\s+", " ").trim().split(" ");
        StringBuilder current = new StringBuilder();
        int count = 0;
        for (String word : words)
        {
            if (current.length() + word.length() + 1 > lineWidth)
            {
                if (current.length() > 0)
                {
                    lines.add(current.toString());
                    count++;
                    current = new StringBuilder();
                    if (count >= MAX_THINKING_LINES) break;
                }
            }
            if (current.length() > 0) current.append(' ');
            current.append(word);
        }
        if (current.length() > 0 && count < MAX_THINKING_LINES)
        {
            lines.add(current.toString());
        }
        return lines;
    }
}
