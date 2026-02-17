package com.osrsbot.claude.brain;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

@Slf4j
@Singleton
public class ConversationManager
{
    private final LinkedList<Exchange> history = new LinkedList<>();
    private int maxExchanges = 10;

    public void setMaxExchanges(int max)
    {
        this.maxExchanges = max;
    }

    public void addExchange(String userMessage, String assistantMessage)
    {
        history.addLast(new Exchange(userMessage, assistantMessage));
        while (history.size() > maxExchanges)
        {
            history.removeFirst();
        }
    }

    public List<Exchange> getHistory()
    {
        return new ArrayList<>(history);
    }

    public void clear()
    {
        history.clear();
    }

    @Data
    public static class Exchange
    {
        private final String userMessage;
        private final String assistantMessage;
    }
}
