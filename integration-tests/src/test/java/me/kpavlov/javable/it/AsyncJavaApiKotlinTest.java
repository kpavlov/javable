package me.kpavlov.javable.it;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Verifies that {@code @AsyncJavaApi} on the Kotlin wrapper exposes suspend functions
 * as {@code CompletableFuture<T>} with default-scope and executor overloads.
 */
public class AsyncJavaApiKotlinTest {

    private final Calculator delegate = new Calculator();
    private final CalculatorKotlin subject = new CalculatorKotlin(delegate);

    @AfterEach
    void tearDown() {
        subject.close();
    }

    @Test
    void asyncMethod_returnsNonNullFuture() {
        CompletableFuture<Integer> future = subject.add(1, 2);
        assertNotNull(future);
    }

    @Test
    void asyncMethod_completesWithCorrectValue() throws Exception {
        assertEquals(3, subject.add(1, 2).get(5, TimeUnit.SECONDS));
    }

    @Test
    void asyncMethod_withExecutorOverload_completesWithCorrectValue() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            assertEquals(7, subject.add(3, 4, executor).get(5, TimeUnit.SECONDS));
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void asyncMethod_withGenericReturnType_completesWithCorrectSize() throws Exception {
        UserRepository repo = new UserRepository(index -> new User("User" + index));
        try (UserRepositoryKotlin wrapper = new UserRepositoryKotlin(repo)) {
            assertEquals(100, wrapper.fetchAll().get(5, TimeUnit.SECONDS).size());
        }
    }

    @Test
    void asyncMethod_withGenericReturnType_andExecutor_completesWithCorrectSize() throws Exception {
        UserRepository repo = new UserRepository(index -> new User("User" + index));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (UserRepositoryKotlin wrapper = new UserRepositoryKotlin(repo)) {
            assertEquals(100, wrapper.fetchAll(executor).get(5, TimeUnit.SECONDS).size());
        } finally {
            executor.shutdown();
        }
    }
}
