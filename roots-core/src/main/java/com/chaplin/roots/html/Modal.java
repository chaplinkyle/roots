package com.chaplin.roots.html;

import com.chaplin.roots.Action;
import com.chaplin.roots.Ref;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A Java-owned modal dialog rendered through a body-level portal.
 *
 * <p>The Roots runtime opens the native {@code dialog} modally, moves focus
 * inside it, routes Escape and optional backdrop dismissal to the declared
 * server action, and restores focus to the opener after authoritative removal.
 * The title is always rendered and connected with {@code aria-labelledby}.</p>
 */
public final class Modal implements Node {
    private static final Set<String> CONTROLLED_ATTRIBUTES = Set.of(
            "open", "aria-modal", "aria-labelledby", "data-roots-modal",
            "data-roots-modal-dismiss", "data-roots-modal-backdrop",
            "data-roots-modal-initial-ref", "data-roots-pending-scope",
            "data-roots-on-click", "tabindex"
    );

    private final String portalId;
    private final String title;
    private final String dismissActionName;
    private final Action dismissAction;
    private final Node children;
    private final Map<String, Object> attributes = new LinkedHashMap<>();
    private Ref initialFocus;
    private Ref dialogRef;
    private boolean dismissOnBackdrop = true;

    Modal(String portalId, String title, String dismissActionName, Action dismissAction, Object... children) {
        this.portalId = requirePortalId(portalId);
        this.title = requireTitle(title);
        this.dismissActionName = requireActionName(dismissActionName);
        this.dismissAction = Objects.requireNonNull(dismissAction, "dismissAction");
        this.children = Html.fragment(children);
    }

    /** Selects the preferred focus target after the dialog opens.
     * If absent or not rendered, Roots focuses the first autofocus/focusable
     * descendant and finally the dialog itself.
     * @param ref focus target rendered inside this modal
     * @return this modal */
    public Modal initialFocus(Ref ref) {
        initialFocus = Objects.requireNonNull(ref, "ref");
        return this;
    }

    /** Assigns a reference to the dialog element.
     * @param ref dialog reference
     * @return this modal */
    public Modal ref(Ref ref) {
        dialogRef = Objects.requireNonNull(ref, "ref");
        return this;
    }

    /** Controls whether a click on the native backdrop invokes dismissal.
     * Escape dismissal always remains enabled for accessible keyboard operation.
     * @param enabled backdrop dismissal flag
     * @return this modal */
    public Modal dismissOnBackdrop(boolean enabled) {
        dismissOnBackdrop = enabled;
        return this;
    }

    /** Sets the dialog element identifier.
     * @param id element identifier
     * @return this modal */
    public Modal id(String id) {
        return attr("id", id);
    }

    /** Sets CSS classes on the dialog.
     * @param className CSS class value
     * @return this modal */
    public Modal className(String className) {
        return attr("class", className);
    }

    /** Connects descriptive content to the dialog.
     * @param id identifier of descriptive content rendered in the modal
     * @return this modal */
    public Modal describedBy(String id) {
        return attr("aria-describedby", id);
    }

    /** Adds or removes a non-structural dialog attribute.
     * @param name attribute name
     * @param value attribute value; {@code null} or {@code false} removes it
     * @return this modal */
    public Modal attr(String name, Object value) {
        Objects.requireNonNull(name, "name");
        var normalized = name.toLowerCase(Locale.ROOT);
        if (!name.matches("[A-Za-z_:][A-Za-z0-9_.:-]*") || normalized.startsWith("on")) {
            throw new IllegalArgumentException("Invalid modal attribute: " + name);
        }
        if (CONTROLLED_ATTRIBUTES.contains(normalized) || normalized.equals("data-roots-ref")) {
            throw new IllegalArgumentException("Roots controls modal attribute: " + name);
        }
        if (value == null || Boolean.FALSE.equals(value)) {
            attributes.remove(name);
        } else {
            attributes.put(name, value);
        }
        return this;
    }

    Portal portal() {
        var titleId = "roots-modal-title-" + portalId;
        var dialog = Html.dialog(
                        Html.h2(title).id(titleId),
                        children
                )
                .attr("open", true)
                .attr("tabindex", -1)
                .aria("modal", "true")
                .aria("labelledby", titleId)
                .data("roots-modal", portalId)
                .data("roots-modal-dismiss", dismissActionName)
                .data("roots-modal-backdrop", Boolean.toString(dismissOnBackdrop))
                .pendingScope()
                .onClick(dismissActionName, dismissAction);
        if (initialFocus != null) {
            dialog.data("roots-modal-initial-ref", initialFocus.id());
        }
        if (dialogRef != null) {
            dialog.ref(dialogRef);
        }
        attributes.forEach(dialog::attr);
        return new Portal(portalId, dialog);
    }

    private static String requirePortalId(String value) {
        Objects.requireNonNull(value, "portalId");
        if (!value.matches("[A-Za-z][A-Za-z0-9_.:-]{0,127}")) {
            throw new IllegalArgumentException(
                    "Modal IDs must start with a letter and contain at most 128 letters, digits, dots, colons, underscores, or hyphens"
            );
        }
        return value;
    }

    private static String requireTitle(String value) {
        Objects.requireNonNull(value, "title");
        if (value.isBlank() || value.length() > 256 || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Modal titles must be printable, non-blank, and at most 256 characters");
        }
        return value;
    }

    private static String requireActionName(String value) {
        Objects.requireNonNull(value, "dismissActionName");
        if (!value.matches("[A-Za-z0-9_.:-]{1,256}")) {
            throw new IllegalArgumentException("Invalid modal dismiss action name: " + value);
        }
        return value;
    }
}
