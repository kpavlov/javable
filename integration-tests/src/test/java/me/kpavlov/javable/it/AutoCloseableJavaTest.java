package me.kpavlov.javable.it;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies lifecycle behaviour of a Java wrapper generated with {@code autoCloseable = true}:
 * scope setup, try-with-resources, and cancellation of in-flight futures on close.
 */
public class AutoCloseableJavaTest {

    private final Calculator delegate = new Calculator();

    @Test
    void javaWrapper_implementsAutoCloseable() {
        assertInstanceOf(AutoCloseable.class, new CalculatorJava(delegate));
    }

    @Test
    void close_withNoPendingFutures_completesWithoutThrowing() {
        new CalculatorJava(delegate).close();
    }

    @Test
    void tryWithResources_futureCompletesBeforeClose() throws Exception {
        int result;
        try (var wrapper = new CalculatorJava(delegate)) {
            result = wrapper.add(1, 2).get(5, TimeUnit.SECONDS);
        }
        assertEquals(3, result);
    }

    @Test
    void tryWithResources_withExecutorConstructor_futureCompletesBeforeClose() throws Exception {
        int result;
        try (var wrapper = new CalculatorJava(delegate, java.util.concurrent.Executors.newSingleThreadExecutor())) {
            result = wrapper.add(4, 5).get(5, TimeUnit.SECONDS);
        }
        assertEquals(9, result);
    }

    @Test
    void close_withPendingFuture_futureIsDoneAfterClose() {
        var wrapper = new CalculatorJava(delegate);
        CompletableFuture<Integer> future = wrapper.add(1, 2);
        wrapper.close();
        assertTrue(future.isDone() || future.isCancelled(),
                "future must be done or cancelled after close()");
    }
}
