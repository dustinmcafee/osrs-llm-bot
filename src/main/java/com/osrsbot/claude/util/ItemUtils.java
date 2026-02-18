package com.osrsbot.claude.util;

import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.game.ItemManager;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.*;

@Slf4j
@Singleton
public class ItemUtils
{
    @Inject
    private Client client;

    @Inject
    private ItemManager itemManager;

    public Widget findInInventory(Client client, String name)
    {
        Widget inventory = client.getWidget(WidgetInfo.INVENTORY);
        if (inventory == null) return null;
        return findInWidget(inventory, name);
    }

    public Widget findInWidget(Widget container, String name)
    {
        if (container == null) return null;

        Widget[] children = container.getDynamicChildren();
        if (children == null) return null;

        for (Widget child : children)
        {
            if (child.getItemId() > 0)
            {
                net.runelite.api.ItemComposition comp = itemManager.getItemComposition(child.getItemId());
                if (comp != null)
                {
                    String itemName = comp.getName();
                    if (itemName != null && itemName.equalsIgnoreCase(name))
                    {
                        return child;
                    }
                }
            }
        }
        return null;
    }

    public String getItemName(Client client, int itemId)
    {
        net.runelite.api.ItemComposition comp = itemManager.getItemComposition(itemId);
        return comp != null ? comp.getName() : null;
    }

    public Point getWidgetScreenPoint(Widget widget)
    {
        if (widget == null) return null;
        Rectangle bounds = widget.getBounds();
        if (bounds == null) return null;
        return new Point(
            (int) bounds.getCenterX(),
            (int) bounds.getCenterY()
        );
    }
}
