package me.kpavlov.javable.it;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Verifies that {@code @AsyncJavaApi(wrapperType = STREAM)} generates a blocking
 * {@code Stream<T>} method on the Kotlin wrapper as well.
 *
 * <p>The Kotlin wrapper uses {@code runBlocking { delegate.words().toList() }.stream()},
 * making it callable from Java without any coroutine plumbing.
 */
public class StreamKotlinTest {

    private final StreamSubject delegate = new StreamSubject();
    private final StreamSubjectKotlin subject = new StreamSubjectKotlin(delegate);

    @Test
    void words_returnsAllElements() throws InterruptedException {
        List<String> result = subject.words().collect(Collectors.toList());
        assertEquals(List.of("alpha", "beta", "gamma"), result);
    }

    @Test
    void words_streamIsOrdered() throws InterruptedException {
        List<String> result = subject.words().toList();
        assertEquals("alpha", result.get(0));
        assertEquals("beta", result.get(1));
        assertEquals("gamma", result.get(2));
    }

    @Test
    void numbers_forwardsParameterCorrectly() throws InterruptedException {
        List<Integer> result = subject.numbers(4).toList();
        assertEquals(List.of(1, 2, 3, 4), result);
    }

    @Test
    void numbers_emptyWhenCountIsZero() throws InterruptedException {
        List<Integer> result = subject.numbers(0).toList();
        assertEquals(List.of(), result);
    }

    @Test
    void numbers_streamCountMatchesParameter() throws InterruptedException {
        assertEquals(7, subject.numbers(7).count());
    }

    @Test
    void delayedWords_returnsElementAfterDelay() throws InterruptedException {
        List<String> result = subject.delayedWords().toList();
        assertEquals(List.of("delayed"), result);
    }

    @Test
    void delayedWords_streamHasExactlyOneElement() throws InterruptedException {
        assertEquals(1, subject.delayedWords().count());
    }

    @Test
    void kotlinWrapper_doesNotImplementAutoCloseable() {
        //noinspection ConstantValue
        assertFalse(AutoCloseable.class.isAssignableFrom(subject.getClass()),
            "StreamSubjectKotlin must NOT implement AutoCloseable — no scope is created for STREAM methods"
        );
    }
}
