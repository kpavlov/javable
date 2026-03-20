package me.kpavlov.javable.it;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Verifies that {@code @AsyncJavaApi(wrapperType = STREAM)} on non-suspend {@code Flow<T>}
 * functions generates a blocking {@code Stream<T>} method on the Java wrapper.
 *
 * <p>Key properties verified:
 * <ul>
 *   <li>The generated method returns a correctly typed {@code Stream<T>}.</li>
 *   <li>All elements emitted by the Flow appear in the Stream, in order.</li>
 *   <li>Parameters are forwarded correctly.</li>
 *   <li>The Java wrapper does NOT implement {@code AutoCloseable} (no scope needed).</li>
 * </ul>
 */
public class StreamJavaTest {

    private final StreamSubject delegate = new StreamSubject();
    private final StreamSubjectJava subject = new StreamSubjectJava(delegate);

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
        List<Integer> result = subject.numbers(4).collect(Collectors.toList());
        assertEquals(List.of(1, 2, 3, 4), result);
    }

    @Test
    void numbers_emptyWhenCountIsZero() throws InterruptedException {
        List<Integer> result = subject.numbers(0).collect(Collectors.toList());
        assertEquals(List.of(), result);
    }

    @Test
    void numbers_streamCountMatchesParameter() throws InterruptedException {
        assertEquals(7, subject.numbers(7).count());
    }

    @Test
    void delayedWords_returnsElementAfterDelay() throws InterruptedException {
        List<String> result = subject.delayedWords().collect(Collectors.toList());
        assertEquals(List.of("delayed"), result);
    }

    @Test
    void delayedWords_streamHasExactlyOneElement() throws InterruptedException {
        assertEquals(1, subject.delayedWords().count());
    }

    @Test
    void javaWrapper_doesNotImplementAutoCloseable() {
        //noinspection ConstantValue
        assertFalse(AutoCloseable.class.isAssignableFrom(subject.getClass()),
            "StreamSubjectJava must NOT implement AutoCloseable — no scope is created for STREAM methods"
        );
    }
}
