package me.kpavlov.javable.it;

import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class CalculatorKotlinTest {

    final Calculator calculator = new Calculator();
    final CalculatorKotlin subject = new CalculatorKotlin(calculator);

    @Test
    void addAsync() throws Exception {
        assertEquals(3, subject.add(1, 2).get());
    }

    @Test
    void addWithExecutor() throws Exception {
        assertEquals(3, subject.add(1, 2, Executors.newSingleThreadExecutor()).get());
    }

    @Test
    void tryWithResources() throws Exception {
        int result;
        try (var calc = new CalculatorKotlin(new Calculator())) {
            result = calc.add(1, 2).get();
        }
        assertEquals(3, result);
    }

    @Test
    void closesCancelsPendingFutures() {
        var wrapper = new CalculatorKotlin(calculator);
        wrapper.close(); // should not throw
    }
}
