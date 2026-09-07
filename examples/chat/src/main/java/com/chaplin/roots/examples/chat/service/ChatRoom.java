package com.chaplin.roots.examples.chat.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ChatRoom {
    private static final System.Logger LOG = System.getLogger(ChatRoom.class.getName());
    private static final int MAX_MESSAGES = 200;
    private static final ChatRoom GENERAL = new ChatRoom();

    private final ArrayDeque<ChatMessage> messages = new ArrayDeque<>();
    private final Map<String, Runnable> subscribers = new LinkedHashMap<>();
    private long nextId;

    private ChatRoom() {
        seed("Maya Chen", "Release train is green. The final smoke suite finished without retries.",
                Instant.now().minus(7, ChronoUnit.MINUTES));
        seed("Theo Grant", "Watching the deploy from Chicago. API latency is holding at 41 ms.",
                Instant.now().minus(4, ChronoUnit.MINUTES));
    }

    public static ChatRoom general() {
        return GENERAL;
    }

    public synchronized List<ChatMessage> messages() {
        return List.copyOf(messages);
    }

    public Subscription subscribe(String subscriberId, Runnable listener) {
        Objects.requireNonNull(subscriberId, "subscriberId");
        Objects.requireNonNull(listener, "listener");
        synchronized (this) {
            subscribers.put(subscriberId, listener);
        }
        return () -> {
            synchronized (ChatRoom.this) {
                subscribers.remove(subscriberId, listener);
            }
        };
    }

    public ChatMessage post(String sourceId, String author, String body) {
        var safeAuthor = required(author, "Display name", 32);
        var safeBody = required(body, "Message", 500);
        ChatMessage message;
        List<Runnable> recipients;

        synchronized (this) {
            message = new ChatMessage(++nextId, safeAuthor, safeBody, Instant.now());
            messages.addLast(message);
            while (messages.size() > MAX_MESSAGES) {
                messages.removeFirst();
            }
            recipients = subscribers.entrySet().stream()
                    .filter(entry -> !entry.getKey().equals(sourceId))
                    .map(Map.Entry::getValue)
                    .toList();
        }

        for (var recipient : recipients) {
            Thread.startVirtualThread(() -> {
                try {
                    recipient.run();
                } catch (RuntimeException exception) {
                    LOG.log(System.Logger.Level.WARNING, "Could not notify a chat subscriber", exception);
                }
            });
        }
        return message;
    }

    private synchronized void seed(String author, String body, Instant sentAt) {
        messages.addLast(new ChatMessage(++nextId, author, body, sentAt));
    }

    private static String required(String value, String label, int maximumLength) {
        var normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(label + " is required");
        }
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException(label + " must be " + maximumLength + " characters or fewer");
        }
        return normalized;
    }

    public record ChatMessage(long id, String author, String body, Instant sentAt) {
    }

    @FunctionalInterface
    public interface Subscription extends AutoCloseable {
        @Override
        void close();
    }
}
