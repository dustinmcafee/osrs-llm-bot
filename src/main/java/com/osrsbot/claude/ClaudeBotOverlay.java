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

            // Show last Claude response (truncated)
            String lastResponse = plugin.getLastClaudeResponse();
            if (lastResponse != null && !lastResponse.isEmpty())
            {
                String truncated = lastResponse.length() > 80
                    ? lastResponse.substring(0, 80) + "..."
                    : lastResponse;
                panelComponent.getChildren().add(LineComponent.builder()
                    .left("Last:")
                    .right(truncated)
                    .build());
            }

            panelComponent.setPreferredSize(new Dimension(250, 0));
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
}
