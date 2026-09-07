package com.chaplin.roots.servlet;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ServletDisconnectTest {
    @Test void containerCompletionErrorAndTimeoutReleaseTheWaitingStreamWorker() throws Exception {
        for (var event : java.util.List.of("complete", "error", "timeout")) {
            var listener = new java.util.concurrent.atomic.AtomicReference<jakarta.servlet.AsyncListener>();
            var started = new java.util.concurrent.CountDownLatch(1);
            var finished = new java.util.concurrent.CountDownLatch(1);
            var interrupted = new java.util.concurrent.atomic.AtomicBoolean();
            var completions = new AtomicInteger();
            var request = (HttpServletRequest) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{HttpServletRequest.class},
                    (proxy, method, arguments) -> null);
            var response = (HttpServletResponse) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{HttpServletResponse.class},
                    (proxy, method, arguments) -> null);
            var async = (AsyncContext) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{AsyncContext.class},
                    (proxy, method, arguments) -> {
                        if (method.getName().equals("addListener")) listener.set((jakarta.servlet.AsyncListener) arguments[0]);
                        if (method.getName().equals("complete")) completions.incrementAndGet();
                        return null;
                    });
            var exchange = new ServletTransportExchange(request, response, async, Optional.empty());
            exchange.startAsyncWork(() -> {
                started.countDown();
                try { new java.util.concurrent.CountDownLatch(1).await(); }
                catch (InterruptedException expected) { interrupted.set(true); }
                finally { finished.countDown(); }
            });
            assertTrue(started.await(3, java.util.concurrent.TimeUnit.SECONDS));
            var signal = new jakarta.servlet.AsyncEvent(async);
            switch (event) {
                case "complete" -> listener.get().onComplete(signal);
                case "error" -> listener.get().onError(signal);
                default -> listener.get().onTimeout(signal);
            }
            assertTrue(finished.await(3, java.util.concurrent.TimeUnit.SECONDS), event);
            assertTrue(interrupted.get(), event);
            exchange.close();
            assertEquals(event.equals("complete") ? 0 : 1, completions.get());
        }
    }

    @Test void completesOnlyOnceEvenWhenTheContainerHasRecycledItsResponse() {
        var completions = new AtomicInteger();
        var request = (HttpServletRequest) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{HttpServletRequest.class},
                (proxy, method, arguments) -> null);
        var response = (HttpServletResponse) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{HttpServletResponse.class},
                (proxy, method, arguments) -> { throw new IllegalStateException("Response facade recycled"); });
        var async = (AsyncContext) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{AsyncContext.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("complete")) {
                        completions.incrementAndGet();
                        throw new IllegalStateException("Container already completed context");
                    }
                    return null;
                });
        var exchange = new ServletTransportExchange(request, response, async, Optional.empty());
        assertDoesNotThrow(exchange::close);
        assertDoesNotThrow(exchange::close);
        assertEquals(1, completions.get());
    }
}
