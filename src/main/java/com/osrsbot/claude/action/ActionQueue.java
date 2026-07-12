package com.osrsbot.claude.action;

import lombok.extern.slf4j.Slf4j;

import javax.inject.Singleton;
import java.util.concurrent.ConcurrentLinkedDeque;

@Slf4j
@Singleton
public class ActionQueue
{
    private final ConcurrentLinkedDeque<BotAction> queue = new ConcurrentLinkedDeque<>();

    public void enqueue(BotAction action)
    {
        queue.addLast(action);
        log.debug("Enqueued action: {}", action.getType());
    }

    public void enqueueFirst(BotAction action)
    {
        queue.addFirst(action);
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
