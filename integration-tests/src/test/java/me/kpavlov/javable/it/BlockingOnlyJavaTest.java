package me.kpavlov.javable.it;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Verifies that a Java wrapper whose annotated class has only {@code @BlockingJavaApi} methods
 * (no {@code @AsyncJavaApi}) generates no coroutine scope and does NOT implement
 * {@code AutoCloseable}.
 */
public class BlockingOnlyJavaTest {

    private final BlockingOnlySubject delegate = new BlockingOnlySubject();
    private final BlockingOnlySubjectJava subject = new BlockingOnlySubjectJava(delegate);

    @Test
    void wrapper_doesNotImplementAutoCloseable() {
        //noinspection ConstantValue
        assertFalse(AutoCloseable.class.isAssignableFrom(BlockingOnlySubjectJava.class),
                "blocking-only wrapper must not implement AutoCloseable — no scope to cancel");
    }

    @Test
    void blockingMethod_returnsCorrectValue() throws InterruptedException {
        assertEquals(10, subject.doubled(5));
    }

    @Test
    void blockingMethod_withZero_returnsZero() throws InterruptedException {
        assertEquals(0, subject.doubled(0));
    }

    @Test
    void blockingMethod_withNegativeValue_returnsNegative() throws InterruptedException {
        assertEquals(-8, subject.doubled(-4));
    }
}
