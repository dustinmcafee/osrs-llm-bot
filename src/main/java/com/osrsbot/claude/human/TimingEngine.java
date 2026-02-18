package com.osrsbot.claude.human;

import java.util.Random;

public class TimingEngine
{
    private final Random random = new Random();
    private int minDelay = 80;
    private int maxDelay = 300;

    public void setDelayRange(int min, int max)
    {
        this.minDelay = min;
        this.maxDelay = max;
    }

    public int nextActionDelay()
    {
        return gaussianDelay(minDelay, maxDelay);
    }

    public int nextClickDelay()
    {
        return gaussianDelay(40, 120);
    }

    public int nextShortPause()
    {
        return gaussianDelay(200, 600);
    }

    public int nextTypingDelay()
    {
        return gaussianDelay(30, 90);
    }

    /**
     * Returns a humanized delay that guarantees at least one full game tick (600ms).
     * Range: 620-980ms with gaussian distribution centered around ~800ms.
     * Used between consecutive server-mutating actions (bank ops, equips, shop buys, drops).
     */
    public int nextTickDelay()
    {
        return gaussianDelay(620, 980);
    }

    private int gaussianDelay(int min, int max)
    {
        double mean = (min + max) / 2.0;
        double stdDev = (max - min) / 4.0;
        double value = random.nextGaussian() * stdDev + mean;
        return (int) Math.max(min, Math.min(max, value));
    }

    public void sleep(int ms)
    {
        try
        {
            Thread.sleep(ms);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }

    public void sleepTicks(int ticks)
    {
        sleep(ticks * 600);
    }

    // Occasionally add a "distracted" pause (1-3 seconds)
    public void maybeDistractedPause()
    {
        if (random.nextDouble() < 0.05) // 5% chance
        {
            sleep(gaussianDelay(1000, 3000));
        }
    }
}
