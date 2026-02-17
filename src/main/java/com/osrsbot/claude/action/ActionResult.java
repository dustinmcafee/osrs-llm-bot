package com.osrsbot.claude.action;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ActionResult
{
    private boolean success;
    private String message;
    private ActionType actionType;

    public static ActionResult success(ActionType type)
    {
        return ActionResult.builder()
            .success(true)
            .actionType(type)
            .message("OK")
            .build();
    }

    public static ActionResult failure(ActionType type, String reason)
    {
        return ActionResult.builder()
            .success(false)
            .actionType(type)
            .message(reason)
            .build();
    }
}
