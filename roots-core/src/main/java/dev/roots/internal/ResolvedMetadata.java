package dev.roots.internal;

import dev.roots.HeadMetadata;
import dev.roots.Metadata;

import java.util.Objects;

record ResolvedMetadata(Metadata document, HeadMetadata head) {
    ResolvedMetadata {
        document = Objects.requireNonNull(document, "Document metadata");
        head = Objects.requireNonNull(head, "Head metadata");
    }
}
