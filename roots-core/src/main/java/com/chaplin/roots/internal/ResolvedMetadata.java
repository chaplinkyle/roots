package com.chaplin.roots.internal;

import com.chaplin.roots.HeadMetadata;
import com.chaplin.roots.Metadata;

import java.util.Objects;

record ResolvedMetadata(Metadata document, HeadMetadata head) {
    ResolvedMetadata {
        document = Objects.requireNonNull(document, "Document metadata");
        head = Objects.requireNonNull(head, "Head metadata");
    }
}
