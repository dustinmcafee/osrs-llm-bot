package com.osrsbot.claude.util;

import net.runelite.client.callback.ClientThread;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Utility for running code on the RuneLite client thread from a background thread.
 * Uses CompletableFuture to block the calling thread until the client thread completes
 * the task. This is necessary because many OSRS client API methods (NPC.getName(),
 * client.getObjectDefinition(), etc.) assert they are called on the client thread.
 */
public class ClientThreadRunner
{
    /**
     * Runs a Callable on the client thread and blocks the calling thread until
     * the result is available (or timeout).
     *
     * @param clientThread RuneLite's ClientThread
     * @param task         The task to run on the client thread
     * @param <T>          Return type
     * @return The result from the task
     * @throws RuntimeException if the task fails or times out
     */
    public static <T> T runOnClientThread(ClientThread clientThread, Callable<T> task)
    {
        CompletableFuture<T> future = new CompletableFuture<>();

        clientThread.invokeLater(() -> {
            try
            {
                future.complete(task.call());
            }
            catch (Throwable t)
            {
                future.completeExceptionally(t);
            }
        });

        try
        {
            return future.get(10, TimeUnit.SECONDS);
        }
        catch (Throwable t)
        {
            System.err.println("[ClaudeBot] ClientThreadRunner failed: " +
                t.getClass().getName() + ": " + t.getMessage());
            t.printStackTrace(System.err);
            throw new RuntimeException("Client thread execution failed", t);
        }
    }
}
