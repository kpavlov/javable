package me.kpavlov.javable.it;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Verifies that a Kotlin wrapper whose annotated class has only {@code @BlockingJavaApi} methods
 * (no {@code @AsyncJavaApi}) generates no coroutine scope and does NOT implement
 * {@code AutoCloseable}.
 */
public class BlockingOnlyKotlinTest {

    private final BlockingOnlySubject delegate = new BlockingOnlySubject();
    private final BlockingOnlySubjectKotlin subject = new BlockingOnlySubjectKotlin(delegate);

    @Test
    void wrapper_doesNotImplementAutoCloseable() {
        //noinspection ConstantValue
        assertFalse(AutoCloseable.class.isAssignableFrom(BlockingOnlySubjectKotlin.class),
            "blocking-only Kotlin wrapper must not implement AutoCloseable — no scope to cancel");
    }

    @Test
    void blockingMethod_returnsCorrectValue() {
        assertEquals(10, subject.doubled(5));
    }

    @Test
    void blockingMethod_withZero_returnsZero() {
        assertEquals(0, subject.doubled(0));
    }

    @Test
    void blockingMethod_withNegativeValue_returnsNegative() {
        assertEquals(-8, subject.doubled(-4));
    }
}
