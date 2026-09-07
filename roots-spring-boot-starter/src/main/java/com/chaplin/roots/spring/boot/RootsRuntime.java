package com.chaplin.roots.spring.boot;

import com.chaplin.roots.RuntimeSnapshot;

interface RootsRuntime {
    RuntimeSnapshot runtimeSnapshot();

    String nodeId();

    String transport();
}
