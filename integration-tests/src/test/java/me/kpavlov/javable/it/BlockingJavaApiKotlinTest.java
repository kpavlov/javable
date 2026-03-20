package me.kpavlov.javable.it;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that {@code @BlockingJavaApi} on the Kotlin wrapper exposes suspend functions
 * as plain synchronous methods (no {@code CompletableFuture}, no executor overload).
 */
public class BlockingJavaApiKotlinTest {

    private final Calculator delegate = new Calculator();
    private final CalculatorKotlin subject = new CalculatorKotlin(delegate);

    @AfterEach
    void tearDown() {
        subject.close();
    }

    @Test
    void blockingMethod_returnsCorrectValue() {
        assertEquals(6, subject.multiply(2, 3));
    }

    @Test
    void blockingMethod_returnsOnCallingThread() {
        int result = subject.multiply(4, 5);
        assertEquals(20, result);
    }

    @Test
    void blockingMethod_withZeroOperands_returnsZero() {
        assertEquals(0, subject.multiply(0, 99));
    }

    @Test
    void blockingMethod_withNegativeOperand_returnsNegative() {
        assertEquals(-6, subject.multiply(-2, 3));
    }
}
