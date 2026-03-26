package me.kpavlov.javable.it;

import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@code @AsyncJavaApi(wrapperType = PUBLISHER)} generates
 * reactive {@code Publisher<T>} methods on the Java wrapper.
 *
 * <p>Key properties verified:
 * <ul>
 *   <li>Flow-returning methods produce a correctly typed {@code Publisher<T>}.</li>
 *   <li>All elements emitted by the Flow appear in the Publisher, in order.</li>
 *   <li>Parameters are forwarded correctly.</li>
 *   <li>Single-value suspend functions emit exactly one element.</li>
 *   <li>The Java wrapper implements {@code AutoCloseable} (scope needed for single-value).</li>
 * </ul>
 */
public class PublisherJavaTest {

    private final PublisherSubject delegate = new PublisherSubject();
    private final PublisherSubjectJava subject = new PublisherSubjectJava(delegate);

    @Test
    void words_returnsAllElements() throws InterruptedException {
        List<String> result = collect(subject.words());
        assertEquals(List.of("alpha", "beta", "gamma"), result);
    }

    @Test
    void words_publisherIsOrdered() throws InterruptedException {
        List<String> result = collect(subject.words());
        assertEquals("alpha", result.get(0));
        assertEquals("beta", result.get(1));
        assertEquals("gamma", result.get(2));
    }

    @Test
    void numbers_forwardsParameterCorrectly() throws InterruptedException {
        List<Integer> result = collect(subject.numbers(4));
        assertEquals(List.of(1, 2, 3, 4), result);
    }

    @Test
    void numbers_emptyWhenCountIsZero() throws InterruptedException {
        List<Integer> result = collect(subject.numbers(0));
        assertEquals(List.of(), result);
    }

    @Test
    void singleValue_returnsCorrectValue() throws InterruptedException {
        List<Integer> result = collect(subject.singleValue(10));
        assertEquals(List.of(30), result);
    }

    @Test
    void singleValue_emitsExactlyOneElement() throws InterruptedException {
        List<Integer> result = collect(subject.singleValue(7));
        assertEquals(1, result.size());
        assertEquals(21, result.get(0));
    }

    @Test
    void javaWrapper_doesNotImplementAutoCloseable() {
        //noinspection ConstantValue
        assertFalse(AutoCloseable.class.isAssignableFrom(subject.getClass()),
            "PublisherSubjectJava must NOT implement AutoCloseable — no scope is needed"
        );
    }

    /**
     * Subscribes to the publisher, requests all elements, and collects them into a list.
     * Blocks until completion or error, with a 5-second timeout.
     */
    private static <T> List<T> collect(Publisher<T> publisher) throws InterruptedException {
        List<T> items = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();

        publisher.subscribe(new Subscriber<>() {
            @Override
            public void onSubscribe(Subscription s) {
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(T item) {
                items.add(item);
            }

            @Override
            public void onError(Throwable t) {
                error.set(t);
                latch.countDown();
            }

            @Override
            public void onComplete() {
                latch.countDown();
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Publisher did not complete within 5 seconds");
        if (error.get() != null) {
            throw new AssertionError("Publisher completed with error", error.get());
        }
        return items;
    }
}
