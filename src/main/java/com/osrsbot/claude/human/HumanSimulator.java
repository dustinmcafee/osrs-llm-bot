package com.osrsbot.claude.human;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.*;

@Slf4j
@Singleton
public class HumanSimulator
{
    @Getter
    private MouseController mouseController;
    @Getter
    private TimingEngine timingEngine;
    @Getter
    private BreakScheduler breakScheduler;
    @Getter
    private ClickProfile clickProfile;
    @Getter
    private MenuInteractor menuInteractor;

    @Inject
    private ClientThread clientThread;

    private volatile boolean initialized = false;

    public void initialize(int mouseSpeed, int minDelay, int maxDelay)
    {
        this.clickProfile = new ClickProfile();
        this.timingEngine = new TimingEngine();
        this.timingEngine.setDelayRange(minDelay, maxDelay);
        this.mouseController = new MouseController();
        this.mouseController.setSpeed(mouseSpeed);
        this.breakScheduler = new BreakScheduler();
        this.menuInteractor = new MenuInteractor(this);
        this.initialized = true;
        log.info("HumanSimulator initialized (canvas event injection mode)");
    }

    /**
     * Must be called with the RuneLite game canvas so MouseController
     * can dispatch synthetic events to it.
     */
    public void setGameCanvas(Canvas canvas)
    {
        if (mouseController != null)
        {
            mouseController.setCanvas(canvas);
        }
    }

    public boolean isInitialized()
    {
        return initialized;
    }

    /**
     * Moves the virtual mouse to the given canvas coordinates by dispatching
     * MOUSE_MOVED events along a natural Bezier curve.
     */
    public void moveMouse(int canvasX, int canvasY)
    {
        if (!initialized) return;
        timingEngine.maybeDistractedPause();
        log.debug("moveMouse({},{})", canvasX, canvasY);
        mouseController.moveTo(canvasX, canvasY);
    }

    public void click()
    {
        if (!initialized) return;
        int delay = timingEngine.nextClickDelay();
        timingEngine.sleep(delay);
        mouseController.click();
    }

    public void rightClick()
    {
        if (!initialized) return;
        int delay = timingEngine.nextClickDelay();
        timingEngine.sleep(delay);
        mouseController.rightClick();
    }

    /**
     * Moves mouse to target canvas position, right-clicks to open context menu,
     * finds the specified option, and clicks it.
     */
    public boolean moveAndRightClickSelect(Client client, int canvasX, int canvasY, String option, String target)
    {
        if (!initialized) return false;
        moveMouse(canvasX, canvasY);
        return menuInteractor.rightClickSelect(client, clientThread, option, target);
    }

    /**
     * Moves mouse to target canvas position and left-clicks.
     */
    public void moveAndClick(int canvasX, int canvasY)
    {
        if (!initialized) return;
        moveMouse(canvasX, canvasY);
        click();
    }

    public void pressKey(int keyCode)
    {
        if (!initialized) return;
        mouseController.pressKey(keyCode);
    }

    /**
     * Holds a key down for a duration then releases — simulates a human
     * holding an arrow key to rotate the camera.
     */
    public void holdKey(int keyCode, int durationMs)
    {
        if (!initialized) return;
        mouseController.holdKey(keyCode, durationMs);
    }

    /**
     * Types a string character by character with humanized inter-key delays —
     * simulates a human typing into a search box or chat.
     */
    public void typeText(String text)
    {
        if (!initialized) return;
        mouseController.typeText(text);
    }

    /**
     * Moves mouse to target and right-clicks (no menu selection).
     */
    public void moveAndRightClick(int canvasX, int canvasY)
    {
        if (!initialized) return;
        moveMouse(canvasX, canvasY);
        rightClick();
    }

    public void shortPause()
    {
        if (!initialized) return;
        int delay = timingEngine.nextShortPause();
        timingEngine.sleep(delay);
    }

    public boolean shouldTakeBreak()
    {
        if (!initialized) return false;
        return breakScheduler.shouldBreak();
    }

    public void configureBreaks(boolean enabled, int minInterval, int maxInterval, int minDuration, int maxDuration)
    {
        if (breakScheduler != null)
        {
            breakScheduler.configure(enabled, minInterval, maxInterval, minDuration, maxDuration);
        }
    }
}
