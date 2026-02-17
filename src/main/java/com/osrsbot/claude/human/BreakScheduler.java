package com.osrsbot.claude.human;

import lombok.extern.slf4j.Slf4j;

import java.util.Random;

@Slf4j
public class BreakScheduler
{
    private final Random random = new Random();
    private boolean enabled = true;
    private int minBreakIntervalMinutes = 15;
    private int maxBreakIntervalMinutes = 45;
    private int minBreakDurationMinutes = 5;
    private int maxBreakDurationMinutes = 30;

    private long nextBreakTime;
    private long breakEndTime;
    private boolean onBreak = false;

    public BreakScheduler()
    {
        scheduleNextBreak();
    }

    public void configure(boolean enabled, int minInterval, int maxInterval, int minDuration, int maxDuration)
    {
        this.enabled = enabled;
        this.minBreakIntervalMinutes = minInterval;
        this.maxBreakIntervalMinutes = maxInterval;
        this.minBreakDurationMinutes = minDuration;
        this.maxBreakDurationMinutes = maxDuration;
        scheduleNextBreak();
    }

    public boolean shouldBreak()
    {
        if (!enabled) return false;

        long now = System.currentTimeMillis();

        if (onBreak)
        {
            if (now >= breakEndTime)
            {
                onBreak = false;
                log.info("Break ended. Resuming bot.");
                scheduleNextBreak();
                return false;
            }
            return true;
        }

        if (now >= nextBreakTime)
        {
            onBreak = true;
            int durationMs = randomBetween(
                minBreakDurationMinutes * 60 * 1000,
                maxBreakDurationMinutes * 60 * 1000
            );
            breakEndTime = now + durationMs;
            log.info("Taking a break for {} minutes", durationMs / 60000);
            return true;
        }

        return false;
    }

    public boolean isOnBreak()
    {
        return onBreak;
    }

    public long getBreakTimeRemaining()
    {
        if (!onBreak) return 0;
        return Math.max(0, breakEndTime - System.currentTimeMillis());
    }

    public long getTimeUntilNextBreak()
    {
        if (onBreak) return 0;
        return Math.max(0, nextBreakTime - System.currentTimeMillis());
    }

    private void scheduleNextBreak()
    {
        int intervalMs = randomBetween(
            minBreakIntervalMinutes * 60 * 1000,
            maxBreakIntervalMinutes * 60 * 1000
        );
        nextBreakTime = System.currentTimeMillis() + intervalMs;
        log.info("Next break in {} minutes", intervalMs / 60000);
    }

    private int randomBetween(int min, int max)
    {
        return min + random.nextInt(max - min + 1);
    }
}
