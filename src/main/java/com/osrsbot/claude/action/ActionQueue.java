package com.osrsbot.claude.action;

import lombok.extern.slf4j.Slf4j;

import javax.inject.Singleton;
import java.util.concurrent.ConcurrentLinkedQueue;

@Slf4j
@Singleton
public class ActionQueue
{
    private final ConcurrentLinkedQueue<BotAction> queue = new ConcurrentLinkedQueue<>();

    public void enqueue(BotAction action)
    {
        queue.add(action);
        log.debug("Enqueued action: {}", action.getType());
    }

    public BotAction dequeue()
    {
        return queue.poll();
    }

    public BotAction peek()
    {
        return queue.peek();
    }

    public boolean isEmpty()
    {
        return queue.isEmpty();
    }

    public int size()
    {
        return queue.size();
    }

    public void clear()
    {
        queue.clear();
    }
}
