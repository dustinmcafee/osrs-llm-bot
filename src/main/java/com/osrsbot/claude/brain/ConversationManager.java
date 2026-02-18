package com.osrsbot.claude.brain;

import com.google.gson.*;
import lombok.Data;

import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Singleton
public class ConversationManager
{
    private final LinkedList<Exchange> recentHistory = new LinkedList<>();
    private final StringBuilder sessionNotes = new StringBuilder();
    private final Object lock = new Object();

    private int recentCount = 3;
    private int maxSessionNotesChars = 32000;

    // Patterns for extracting key info from game state
    private static final Pattern POS_PATTERN = Pattern.compile("Pos:\\((\\d+,\\d+)");
    private static final Pattern STATUS_PATTERN = Pattern.compile("\\[STATUS\\] (\\S+)");
    private static final Pattern INV_PATTERN = Pattern.compile("\\[INVENTORY\\] \\((\\d+/28)\\)");

    public void setRecentCount(int count)
    {
        this.recentCount = Math.max(1, count);
    }

    public void setMaxSessionNotesChars(int maxChars)
    {
        this.maxSessionNotesChars = Math.max(1000, maxChars);
    }

    public void addExchange(String userMessage, String assistantMessage)
    {
        synchronized (lock)
        {
            recentHistory.addLast(new Exchange(userMessage, assistantMessage));

            // When recent history exceeds limit, compress oldest into session notes
            while (recentHistory.size() > recentCount)
            {
                Exchange old = recentHistory.removeFirst();
                String summary = compress(old);
                appendToSessionNotes(summary);
            }
        }
    }

    /**
     * Returns recent full exchanges for the API conversation history.
     */
    public List<Exchange> getHistory()
    {
        synchronized (lock)
        {
            return new ArrayList<>(recentHistory);
        }
    }

    /**
     * Returns accumulated session notes (compressed summaries of older exchanges).
     */
    public String getSessionNotes()
    {
        synchronized (lock)
        {
            return sessionNotes.toString();
        }
    }

    public void clear()
    {
        synchronized (lock)
        {
            recentHistory.clear();
            sessionNotes.setLength(0);
        }
    }

    /**
     * Compresses an old exchange into a one-line summary capturing:
     * - Player position
     * - Activity status
     * - Inventory fullness
     * - Actions chosen by Claude
     * - Whether previous actions had failures
     *
     * Called while holding lock — must not call back into public methods.
     */
    private String compress(Exchange exchange)
    {
        StringBuilder line = new StringBuilder("- ");

        String state = exchange.getUserMessage();

        // Extract position
        Matcher posMatcher = POS_PATTERN.matcher(state);
        if (posMatcher.find())
        {
            line.append("@(").append(posMatcher.group(1)).append(") ");
        }

        // Extract status
        Matcher statusMatcher = STATUS_PATTERN.matcher(state);
        if (statusMatcher.find())
        {
            line.append(statusMatcher.group(1)).append(" ");
        }

        // Extract inventory count
        Matcher invMatcher = INV_PATTERN.matcher(state);
        if (invMatcher.find())
        {
            line.append("inv:").append(invMatcher.group(1)).append(" ");
        }

        line.append("-> ");

        // Extract actions from Claude's response JSON
        String response = exchange.getAssistantMessage();
        try
        {
            int start = response.indexOf('[');
            int end = response.lastIndexOf(']');
            if (start >= 0 && end > start)
            {
                JsonArray arr = JsonParser.parseString(response.substring(start, end + 1)).getAsJsonArray();
                List<String> actionNames = new ArrayList<>();
                for (JsonElement el : arr)
                {
                    JsonObject obj = el.getAsJsonObject();
                    String action = obj.get("action").getAsString();
                    // Add the most relevant parameter for context
                    if (obj.has("name"))
                    {
                        action += "(" + obj.get("name").getAsString() + ")";
                    }
                    else if (obj.has("ticks"))
                    {
                        action += "(" + obj.get("ticks").getAsInt() + "t)";
                    }
                    else if (obj.has("x") && obj.has("y"))
                    {
                        action += "(" + obj.get("x").getAsInt() + "," + obj.get("y").getAsInt() + ")";
                    }
                    actionNames.add(action);
                }
                line.append(String.join(",", actionNames));
            }
            else
            {
                line.append("(no actions)");
            }
        }
        catch (Exception e)
        {
            line.append("(parse error)");
        }

        // Note if there were action failures in this cycle
        if (state.contains("[ACTION_RESULTS]"))
        {
            if (state.contains("FAILED"))
            {
                line.append(" [FAILURES]");
            }
        }

        return line.toString();
    }

    /**
     * Appends a summary line to session notes, trimming oldest lines if over budget.
     * Called while holding lock.
     */
    private void appendToSessionNotes(String summary)
    {
        sessionNotes.append(summary).append("\n");

        // Trim oldest lines if over budget
        while (sessionNotes.length() > maxSessionNotesChars)
        {
            int firstNewline = sessionNotes.indexOf("\n");
            if (firstNewline < 0) break;
            sessionNotes.delete(0, firstNewline + 1);
        }
    }

    @Data
    public static class Exchange
    {
        private final String userMessage;
        private final String assistantMessage;
    }
}
