package com.osrsbot.claude.human;

import java.util.Random;

public class ClickProfile
{
    private final Random random = new Random();

    // 80% within 3px, 15% within 5px, 5% within 8px
    public int getOffsetX()
    {
        return getOffset();
    }

    public int getOffsetY()
    {
        return getOffset();
    }

    private int getOffset()
    {
        double roll = random.nextDouble();
        int maxOffset;

        if (roll < 0.80)
        {
            maxOffset = 3;
        }
        else if (roll < 0.95)
        {
            maxOffset = 5;
        }
        else
        {
            maxOffset = 8;
        }

        return random.nextInt(maxOffset * 2 + 1) - maxOffset;
    }
}
