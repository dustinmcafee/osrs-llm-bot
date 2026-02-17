package com.osrsbot.claude.state;

import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
@Builder
public class InventoryState
{
    private List<ItemSlot> items;
    private int usedSlots;
    private int freeSlots;

    @Data
    @Builder
    public static class ItemSlot
    {
        private int slot;
        private int itemId;
        private String name;
        private int quantity;
    }
}
