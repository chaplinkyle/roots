package dev.roots.spring.boot;

import dev.roots.RuntimeSnapshot;

interface RootsRuntime {
    RuntimeSnapshot runtimeSnapshot();

    String nodeId();

    String transport();
}
