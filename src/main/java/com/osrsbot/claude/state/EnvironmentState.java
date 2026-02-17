package com.osrsbot.claude.state;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class EnvironmentState
{
    private int regionId;
    private int plane;
    private boolean isInInstance;
    private boolean isBankOpen;
    private boolean isDialogOpen;
    private boolean isShopOpen;
    private int gameTickCount;
    private String loginState;
}
