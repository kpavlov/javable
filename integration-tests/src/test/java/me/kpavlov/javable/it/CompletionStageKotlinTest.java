package me.kpavlov.javable.it;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Verifies that {@code @AsyncJavaApi(wrapperType = COMPLETION_STAGE)} on the Kotlin wrapper
 * exposes suspend functions as {@code CompletionStage<T>} rather than {@code CompletableFuture<T>}.
 */
public class CompletionStageKotlinTest {

    private final CompletionStageSubject delegate = new CompletionStageSubject();
    private final CompletionStageSubjectKotlin subject = new CompletionStageSubjectKotlin(delegate);

    @AfterEach
    void tearDown() {
        subject.close();
    }

    @Test
    void asyncMethod_returnsNonNullStage() {
        CompletionStage<Integer> stage = subject.compute(5);
        assertNotNull(stage);
    }

    @Test
    void asyncMethod_completesWithCorrectValue() throws Exception {
        assertEquals(10, subject.compute(5).toCompletableFuture().get(5, TimeUnit.SECONDS));
    }

    @Test
    void asyncMethod_withExecutorOverload_completesWithCorrectValue() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            assertEquals(14, subject.compute(7, executor).toCompletableFuture().get(5, TimeUnit.SECONDS));
        } finally {
            executor.shutdown();
        }
    }
}
