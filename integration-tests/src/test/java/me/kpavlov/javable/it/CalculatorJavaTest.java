package me.kpavlov.javable.it;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class CalculatorJavaTest {

    final Calculator calculator = new Calculator();
    final CalculatorJava subject = new CalculatorJava(calculator);

    @Test
    void addAsync() throws Exception {
        CompletableFuture<Integer> result = subject.add(1, 2);
        assertEquals(3, result.get());
    }

    @Test
    void addWithExecutor() throws Exception {
        CompletableFuture<Integer> result = subject.add(1, 2, Executors.newSingleThreadExecutor());
        assertEquals(3, result.get());
    }
}