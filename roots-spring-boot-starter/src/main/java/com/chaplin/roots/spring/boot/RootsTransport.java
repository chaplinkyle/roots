package com.chaplin.roots.spring.boot;

/** HTTP transport selected for a Spring Boot-managed Roots application. */
public enum RootsTransport {
    /** Starts Roots' dependency-free JDK listener beside the Boot server. */
    JDK,
    /** Registers Roots in Boot's existing Jakarta Servlet web server. */
    SERVLET
}
