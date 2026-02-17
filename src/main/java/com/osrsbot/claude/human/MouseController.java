package com.osrsbot.claude.human;

import lombok.extern.slf4j.Slf4j;

import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.util.Random;

/**
 * Dispatches synthetic MouseEvents directly to a Canvas component.
 * Simulates natural mouse movement along randomized Bezier curves
 * so the game client sees a realistic event stream.
 *
 * Events are posted to the system EventQueue via postEvent(),
 * matching the flow of real hardware input.
 */
@Slf4j
public class MouseController
{
    private final ClickProfile clickProfile;
    private final Random random = new Random();

    private Canvas canvas;
    private int currentX = -1;
    private int currentY = -1;
    private boolean enteredCanvas = false;

    // Movement tuning
    private int baseSpeed = 15; // lower = faster, higher = slower (ms per step)
    private static final int MIN_STEPS_SHORT = 8;
    private static final int MIN_STEPS_LONG = 20;
    private static final int MAX_STEPS = 80;
    private static final double OVERSHOOT_CHANCE = 0.15;
    private static final int OVERSHOOT_MAX_PX = 8;

    public MouseController()
    {
        this.clickProfile = new ClickProfile();
    }

    public void setCanvas(Canvas canvas)
    {
        this.canvas = canvas;
        this.enteredCanvas = false;
    }

    public void setSpeed(int speed)
    {
        this.baseSpeed = speed;
    }

    /**
     * Moves the virtual mouse to (x, y) on the canvas by dispatching
     * a series of MOUSE_MOVED events along a natural Bezier curve.
     */
    public void moveTo(int x, int y)
    {
        if (canvas == null)
        {
            System.err.println("[ClaudeBot] Canvas not set, cannot dispatch mouse events");
            return;
        }

        // Add click profile offset for humanization
        int targetX = x + clickProfile.getOffsetX();
        int targetY = y + clickProfile.getOffsetY();

        // Clamp to canvas bounds
        targetX = Math.max(0, Math.min(targetX, canvas.getWidth() - 1));
        targetY = Math.max(0, Math.min(targetY, canvas.getHeight() - 1));

        // Dispatch MOUSE_ENTERED if this is the first interaction with the canvas
        if (!enteredCanvas)
        {
            currentX = canvas.getWidth() / 2;
            currentY = canvas.getHeight() / 2;
            postEvent(new MouseEvent(
                canvas, MouseEvent.MOUSE_ENTERED, System.currentTimeMillis(),
                0, currentX, currentY, 0, false
            ));
            enteredCanvas = true;
        }

        // Calculate distance for step count
        double dist = Math.hypot(targetX - currentX, targetY - currentY);
        if (dist < 2)
        {
            // Already there
            currentX = targetX;
            currentY = targetY;
            return;
        }

        // Determine number of intermediate steps
        int steps = (int) Math.min(MAX_STEPS, Math.max(
            dist < 100 ? MIN_STEPS_SHORT : MIN_STEPS_LONG,
            dist / 8
        ));

        // Decide if we overshoot
        boolean overshoot = dist > 50 && random.nextDouble() < OVERSHOOT_CHANCE;
        int overshootX = targetX;
        int overshootY = targetY;
        if (overshoot)
        {
            double angle = Math.atan2(targetY - currentY, targetX - currentX);
            int overshootDist = 3 + random.nextInt(OVERSHOOT_MAX_PX);
            overshootX = targetX + (int) (Math.cos(angle) * overshootDist);
            overshootY = targetY + (int) (Math.sin(angle) * overshootDist);
        }

        // Generate Bezier curve to (possibly overshoot) target
        int finalTargetX = overshoot ? overshootX : targetX;
        int finalTargetY = overshoot ? overshootY : targetY;
        Point[] curve = generateBezierCurve(currentX, currentY, finalTargetX, finalTargetY, steps);

        // Dispatch movement events
        dispatchMovementPath(curve);

        // If overshoot, correct to actual target
        if (overshoot)
        {
            int correctionSteps = 3 + random.nextInt(4);
            Point[] correction = generateBezierCurve(overshootX, overshootY, targetX, targetY, correctionSteps);
            sleep(20 + random.nextInt(40)); // brief pause before correction
            dispatchMovementPath(correction);
        }

        currentX = targetX;
        currentY = targetY;
    }

    public void click()
    {
        dispatchClick(MouseEvent.BUTTON1, InputEvent.BUTTON1_DOWN_MASK);
    }

    public void rightClick()
    {
        dispatchClick(MouseEvent.BUTTON3, InputEvent.BUTTON3_DOWN_MASK);
    }

    public void pressKey(int keyCode)
    {
        if (canvas == null) return;
        long now = System.currentTimeMillis();

        postEvent(new java.awt.event.KeyEvent(
            canvas, java.awt.event.KeyEvent.KEY_PRESSED, now, 0,
            keyCode, java.awt.event.KeyEvent.CHAR_UNDEFINED
        ));
        sleep(50 + random.nextInt(80));
        postEvent(new java.awt.event.KeyEvent(
            canvas, java.awt.event.KeyEvent.KEY_RELEASED, now + 60, 0,
            keyCode, java.awt.event.KeyEvent.CHAR_UNDEFINED
        ));
    }

    /**
     * Holds a key down for a specified duration, then releases it.
     * Dispatches KEY_PRESSED immediately, sleeps for the duration,
     * then dispatches KEY_RELEASED — mimicking a human holding an arrow key.
     */
    public void holdKey(int keyCode, int durationMs)
    {
        if (canvas == null) return;
        long now = System.currentTimeMillis();

        postEvent(new java.awt.event.KeyEvent(
            canvas, java.awt.event.KeyEvent.KEY_PRESSED, now, 0,
            keyCode, java.awt.event.KeyEvent.CHAR_UNDEFINED
        ));

        sleep(durationMs);

        postEvent(new java.awt.event.KeyEvent(
            canvas, java.awt.event.KeyEvent.KEY_RELEASED, System.currentTimeMillis(), 0,
            keyCode, java.awt.event.KeyEvent.CHAR_UNDEFINED
        ));
    }

    /**
     * Types a string character by character with humanized inter-key delays.
     * Each character dispatches KEY_PRESSED, KEY_TYPED, KEY_RELEASED events
     * so the game client sees realistic keyboard input.
     */
    public void typeText(String text)
    {
        if (canvas == null || text == null) return;

        for (char c : text.toCharArray())
        {
            long now = System.currentTimeMillis();
            int keyCode = java.awt.event.KeyEvent.getExtendedKeyCodeForChar(c);

            postEvent(new java.awt.event.KeyEvent(
                canvas, java.awt.event.KeyEvent.KEY_PRESSED, now, 0,
                keyCode, c
            ));
            postEvent(new java.awt.event.KeyEvent(
                canvas, java.awt.event.KeyEvent.KEY_TYPED, now + 10, 0,
                java.awt.event.KeyEvent.VK_UNDEFINED, c
            ));

            int holdDelay = 30 + random.nextInt(60);
            sleep(holdDelay);

            postEvent(new java.awt.event.KeyEvent(
                canvas, java.awt.event.KeyEvent.KEY_RELEASED, System.currentTimeMillis(), 0,
                keyCode, c
            ));

            // Inter-key delay — Gaussian distributed like real typing
            int typingDelay = 30 + random.nextInt(90);
            sleep(typingDelay);
        }
    }

    public Point getMousePosition()
    {
        return new Point(currentX, currentY);
    }

    // ---- Bezier curve generation ----

    /**
     * Generates points along a cubic Bezier curve with randomized control points
     * to simulate natural hand movement.
     */
    private Point[] generateBezierCurve(int startX, int startY, int endX, int endY, int steps)
    {
        double dist = Math.hypot(endX - startX, endY - startY);

        // Control point spread scales with distance
        double spread = dist * (0.2 + random.nextDouble() * 0.4);

        // Random perpendicular offset for control points
        double angle = Math.atan2(endY - startY, endX - startX);
        double perpAngle = angle + Math.PI / 2;

        // Control point 1: ~1/3 along the line, offset perpendicular
        double cp1Frac = 0.2 + random.nextDouble() * 0.2;
        double cp1PerpOffset = (random.nextDouble() - 0.5) * spread;
        int cp1X = (int) (startX + (endX - startX) * cp1Frac + Math.cos(perpAngle) * cp1PerpOffset);
        int cp1Y = (int) (startY + (endY - startY) * cp1Frac + Math.sin(perpAngle) * cp1PerpOffset);

        // Control point 2: ~2/3 along the line, smaller perpendicular offset
        double cp2Frac = 0.6 + random.nextDouble() * 0.2;
        double cp2PerpOffset = (random.nextDouble() - 0.5) * spread * 0.5;
        int cp2X = (int) (startX + (endX - startX) * cp2Frac + Math.cos(perpAngle) * cp2PerpOffset);
        int cp2Y = (int) (startY + (endY - startY) * cp2Frac + Math.sin(perpAngle) * cp2PerpOffset);

        Point[] points = new Point[steps];
        for (int i = 0; i < steps; i++)
        {
            double t = (double) (i + 1) / steps;

            // Apply ease-in-out for natural acceleration/deceleration
            t = easeInOut(Math.min(1.0, Math.max(0.0, t)));

            double oneMinusT = 1.0 - t;
            double x = oneMinusT * oneMinusT * oneMinusT * startX
                     + 3 * oneMinusT * oneMinusT * t * cp1X
                     + 3 * oneMinusT * t * t * cp2X
                     + t * t * t * endX;
            double y = oneMinusT * oneMinusT * oneMinusT * startY
                     + 3 * oneMinusT * oneMinusT * t * cp1Y
                     + 3 * oneMinusT * t * t * cp2Y
                     + t * t * t * endY;

            // Small per-point noise (simulates hand tremor)
            if (i < steps - 1) // don't add noise to final point
            {
                x += (random.nextGaussian() * 0.8);
                y += (random.nextGaussian() * 0.8);
            }

            points[i] = new Point((int) Math.round(x), (int) Math.round(y));
        }
        return points;
    }

    /**
     * Smooth ease-in-out function: slow start, fast middle, slow end.
     * Input t must be in [0, 1].
     */
    private double easeInOut(double t)
    {
        return t < 0.5
            ? 4 * t * t * t
            : 1 - Math.pow(-2 * t + 2, 3) / 2;
    }

    // ---- Event dispatch ----

    /**
     * Posts an AWT event to the system EventQueue, matching the flow of real
     * hardware input: EventQueue → EDT → canvas.processEvent().
     * This is critical because the OSRS client reads input from the queue,
     * not from direct dispatchEvent() calls.
     */
    private void postEvent(AWTEvent event)
    {
        try
        {
            if (canvas == null) return;
            canvas.getToolkit().getSystemEventQueue().postEvent(event);
        }
        catch (Throwable t)
        {
            System.err.println("[ClaudeBot] Failed to post event to EventQueue: " +
                t.getClass().getName() + ": " + t.getMessage());
        }
    }

    private void dispatchMovementPath(Point[] path)
    {
        for (Point p : path)
        {
            int clampedX = Math.max(0, Math.min(p.x, canvas.getWidth() - 1));
            int clampedY = Math.max(0, Math.min(p.y, canvas.getHeight() - 1));

            postEvent(new MouseEvent(
                canvas,
                MouseEvent.MOUSE_MOVED,
                System.currentTimeMillis(),
                0,
                clampedX,
                clampedY,
                0,
                false
            ));

            // Variable delay between movement events (1-6ms, so full move takes 20-300ms)
            int stepDelay = Math.max(1, baseSpeed / 5 + random.nextInt(Math.max(1, baseSpeed / 3)));
            sleep(stepDelay);
        }
    }

    private void dispatchClick(int button, int modifiers)
    {
        if (canvas == null) return;

        long now = System.currentTimeMillis();
        int x = Math.max(0, currentX);
        int y = Math.max(0, currentY);

        postEvent(new MouseEvent(
            canvas, MouseEvent.MOUSE_PRESSED, now, modifiers,
            x, y, 1, false, button
        ));

        // Human click hold duration: 50-150ms
        int holdDuration = 50 + random.nextInt(100);
        sleep(holdDuration);

        long releaseTime = System.currentTimeMillis();
        postEvent(new MouseEvent(
            canvas, MouseEvent.MOUSE_RELEASED, releaseTime, modifiers,
            x, y, 1, false, button
        ));

        postEvent(new MouseEvent(
            canvas, MouseEvent.MOUSE_CLICKED, releaseTime, modifiers,
            x, y, 1, false, button
        ));
    }

    private void sleep(int ms)
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
}
