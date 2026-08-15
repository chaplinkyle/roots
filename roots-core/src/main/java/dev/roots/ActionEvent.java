package dev.roots;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ArrayList;

public final class ActionEvent {
    private final String type;
    private final Map<String, List<String>> values;
    private final Session session;
    private String redirect;
    private final List<ClientEffect> effects = new ArrayList<>();

    public ActionEvent(String type, Map<String, List<String>> values, Session session) {
        this.type = type;
        this.values = Map.copyOf(values);
        this.session = session;
    }

    public String type() {
        return type;
    }

    public Optional<String> value(String name) {
        return values.getOrDefault(name, List.of()).stream().findFirst();
    }

    public String required(String name) {
        return value(name)
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new IllegalArgumentException("Missing form value: " + name));
    }

    public List<String> values(String name) {
        return values.getOrDefault(name, List.of());
    }

    public Map<String, List<String>> allValues() {
        return values;
    }

    public Session session() {
        return session;
    }

    public void redirect(String path) {
        if (path == null || !path.startsWith("/") || path.startsWith("//")) {
            throw new IllegalArgumentException("Redirects must use an absolute application path");
        }
        redirect = path;
    }

    public Optional<String> redirect() {
        return Optional.ofNullable(redirect);
    }

    public void focus(Ref ref) {
        effects.add(ClientEffect.focus(ref));
    }

    public void scrollIntoView(Ref ref) {
        effects.add(ClientEffect.scrollIntoView(ref));
    }

    public void copyToClipboard(String value) {
        effects.add(ClientEffect.copyToClipboard(value));
    }

    public List<ClientEffect> effects() {
        return List.copyOf(effects);
    }
}
