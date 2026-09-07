package com.chaplin.roots;

import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;

/** Maps an application failure to an HTTP response, or declines it. */
@FunctionalInterface
public interface ExceptionMapper {
    /** Attempts to map an application failure.
     * @param request request being handled
     * @param failure failure to map
     * @return mapped response, or empty to decline */
    Optional<Response> map(Request request, Throwable failure);

    /** Creates a mapper limited to one failure type.
     * @param <E> failure type
     * @param type failure class
     * @param mapper mapping function
     * @return typed exception mapper */
    static <E extends Throwable> ExceptionMapper forType(
            Class<E> type,
            BiFunction<? super Request, ? super E, ? extends Response> mapper
    ) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(mapper, "mapper");
        return (request, failure) -> type.isInstance(failure)
                ? Optional.of(Objects.requireNonNull(
                        mapper.apply(request, type.cast(failure)),
                        "Exception mapper response"
                ))
                : Optional.empty();
    }
}
