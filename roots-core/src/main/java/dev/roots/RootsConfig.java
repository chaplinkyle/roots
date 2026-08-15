package dev.roots;

import java.time.Duration;
import java.util.Objects;

public record RootsConfig(
        Class<?> applicationClass,
        String pagesPackage,
        String apiPackage,
        String host,
        int port,
        boolean development,
        Duration viewTimeout
) {
    public RootsConfig {
        Objects.requireNonNull(applicationClass, "applicationClass");
        Objects.requireNonNull(pagesPackage, "pagesPackage");
        Objects.requireNonNull(apiPackage, "apiPackage");
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(viewTimeout, "viewTimeout");
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException("Port must be between 0 and 65535");
        }
    }

    public static Builder forApplication(Class<?> applicationClass) {
        return new Builder(applicationClass);
    }

    public static final class Builder {
        private final Class<?> applicationClass;
        private String pagesPackage;
        private String apiPackage;
        private String host = "127.0.0.1";
        private int port = 8080;
        private boolean development = true;
        private Duration viewTimeout = Duration.ofMinutes(30);

        private Builder(Class<?> applicationClass) {
            this.applicationClass = Objects.requireNonNull(applicationClass);
            var basePackage = applicationClass.getPackageName();
            this.pagesPackage = basePackage + ".pages";
            this.apiPackage = basePackage + ".api";
        }

        public Builder pagesPackage(String pagesPackage) {
            this.pagesPackage = pagesPackage;
            return this;
        }

        public Builder apiPackage(String apiPackage) {
            this.apiPackage = apiPackage;
            return this;
        }

        public Builder host(String host) {
            this.host = host;
            return this;
        }

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public Builder development(boolean development) {
            this.development = development;
            return this;
        }

        public Builder viewTimeout(Duration viewTimeout) {
            this.viewTimeout = viewTimeout;
            return this;
        }

        public Builder arguments(String... arguments) {
            for (var argument : arguments) {
                if (argument.startsWith("--port=")) {
                    port(Integer.parseInt(argument.substring("--port=".length())));
                } else if (argument.startsWith("--host=")) {
                    host(argument.substring("--host=".length()));
                } else if (argument.equals("--production")) {
                    development(false);
                } else if (!argument.isBlank()) {
                    throw new IllegalArgumentException("Unknown Roots option: " + argument);
                }
            }
            return this;
        }

        public RootsConfig build() {
            return new RootsConfig(
                    applicationClass,
                    pagesPackage,
                    apiPackage,
                    host,
                    port,
                    development,
                    viewTimeout
            );
        }
    }
}
