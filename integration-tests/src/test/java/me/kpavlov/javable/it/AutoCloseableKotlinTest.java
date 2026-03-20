package me.kpavlov.javable.it;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies lifecycle behaviour of the Kotlin wrapper: AutoCloseable implementation,
 * try-with-resources, and cancellation of in-flight futures on close.
 * <p>
 * The Kotlin wrapper implements AutoCloseable whenever the annotated class has
 * at least one {@code @AsyncJavaApi} method.
 */
public class AutoCloseableKotlinTest {

    private final Calculator delegate = new Calculator();

    @Test
    void kotlinWrapper_implementsAutoCloseable() {
        assertInstanceOf(AutoCloseable.class, new CalculatorKotlin(delegate));
    }

    @Test
    void close_withNoPendingFutures_completesWithoutThrowing() {
        var wrapper = new CalculatorKotlin(delegate);
        wrapper.close(); // must not throw
    }

    @Test
    void tryWithResources_futureCompletesBeforeClose() throws Exception {
        int result;
        try (var wrapper = new CalculatorKotlin(delegate)) {
            result = wrapper.add(1, 2).get(5, TimeUnit.SECONDS);
        }
        assertEquals(3, result);
    }

    @Test
    void tryWithResources_withDefaultConstructor_futureCompletesBeforeClose() throws Exception {
        int result;
        try (var wrapper = new CalculatorKotlin(new Calculator())) {
            result = wrapper.add(4, 5).get(5, TimeUnit.SECONDS);
        }
        assertEquals(9, result);
    }

    @Test
    void close_withPendingFuture_futureIsDoneAfterClose() {
        var wrapper = new CalculatorKotlin(delegate);
        CompletableFuture<Integer> future = wrapper.add(1, 2);
        wrapper.close();
        assertTrue(future.isDone() || future.isCancelled(),
                "future must be done or cancelled after close()");
    }

    @Test
    void close_withPendingFuture_forSlowDelegate_futureIsDoneAfterClose() {
        UserRepository repo = new UserRepository(index -> new User("User" + index));
        UserRepositoryKotlin wrapper = new UserRepositoryKotlin(repo);
        CompletableFuture<?> future = wrapper.fetchAll();
        wrapper.close();
        assertTrue(future.isDone() || future.isCancelled(),
                "slow future must be done or cancelled after close()");
    }
}
