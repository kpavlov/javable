package me.kpavlov.javable.it;

import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class UserRepositoryKotlinTest {

    final UserRepository delegate = new UserRepository(
            (index) -> new User("User" + index)
    );
    final UserRepositoryKotlin subject = new UserRepositoryKotlin(delegate);

    @Test
    void testAsync() throws Exception {
        assertEquals(100, subject.fetchAll().get().size());
    }

    @Test
    void addWithExecutor() throws Exception {
        assertEquals(100, subject.fetchAll(Executors.newSingleThreadExecutor()).get().size());
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
        var wrapper = new UserRepositoryKotlin(delegate);
        wrapper.close(); // should not throw
    }
}
