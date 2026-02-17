package com.osrsbot.claude.state;

import lombok.Builder;
import lombok.Data;
import java.util.Map;

@Data
@Builder
public class EquipmentState
{
    private Map<String, EquipmentSlot> slots;

    @Data
    @Builder
    public static class EquipmentSlot
    {
        private String slotName;
        private int itemId;
        private String name;
        private int quantity;
    }
}
