package com.chaplin.roots.spring.boot;

import com.chaplin.roots.RequestObservation;
import com.chaplin.roots.RequestObserver;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Objects;

final class SpringRequestObserverBridge implements RequestObserver {
    private static final System.Logger LOGGER = System.getLogger(SpringRequestObserverBridge.class.getName());

    private final ObjectProvider<RequestObserver> observers;

    SpringRequestObserverBridge(ObjectProvider<RequestObserver> observers) {
        this.observers = Objects.requireNonNull(observers, "observers");
    }

    @Override
    public void onComplete(RequestObservation observation) {
        observers.orderedStream().forEach(observer -> notifyObserver(observer, observation));
    }

    private static void notifyObserver(RequestObserver observer, RequestObservation observation) {
        try {
            observer.onComplete(observation);
        } catch (Throwable failure) {
            LOGGER.log(System.Logger.Level.WARNING, "Spring-managed Roots request observer failed", failure);
        }
    }
}
