package com.osrsbot.claude.action;

import java.util.HashMap;
import java.util.Map;

public enum ActionType
{
    WALK_TO(1),
    INTERACT_NPC(2),
    INTERACT_OBJECT(3),
    USE_ITEM(4),
    USE_ITEM_ON_ITEM(5),
    USE_ITEM_ON_NPC(6),
    USE_ITEM_ON_OBJECT(7),
    EQUIP_ITEM(8),
    DROP_ITEM(9),
    PICKUP_ITEM(10),
    EAT_FOOD(11),
    TOGGLE_PRAYER(12),
    TOGGLE_RUN(13),
    SELECT_DIALOGUE(14),
    CONTINUE_DIALOGUE(15),
    WAIT(16),
    SPECIAL_ATTACK(17),
    BANK_DEPOSIT(18),
    BANK_WITHDRAW(19),
    BANK_CLOSE(20),
    CLICK_WIDGET(21),
    CAST_SPELL(22),
    MAKE_ITEM(23),
    SHOP_BUY(24),
    SHOP_SELL(25),
    MINIMAP_WALK(26),
    ROTATE_CAMERA(27),
    GE_BUY(28),
    GE_SELL(29),
    OPEN_TAB(30),
    TYPE_TEXT(31),
    UNEQUIP_ITEM(32),
    PRESS_KEY(33),
    BANK_DEPOSIT_ALL(34),
    SET_ATTACK_STYLE(35),
    SET_AUTOCAST(36),
    WORLD_HOP(37),
    PATH_TO(38),
    WAIT_ANIMATION(39);

    private final int id;

    private static final Map<Integer, ActionType> BY_ID = new HashMap<>();

    static
    {
        for (ActionType t : values())
        {
            BY_ID.put(t.id, t);
        }
    }

    ActionType(int id)
    {
        this.id = id;
    }

    public int getId()
    {
        return id;
    }

    public static ActionType fromId(int id)
    {
        return BY_ID.get(id);
    }
}
