package de.tum.cit.artemis.push.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.async.DeferredResult;

class BoundedSendExecutorTest {

    private static final ResponseEntity<Void> OK = ResponseEntity.ok().build();

    @Test
    void completesDeferredResultWithTaskResult() throws InterruptedException {
        BoundedSendExecutor executor = new BoundedSendExecutor("test", 2, 10, 60_000);
        try {
            DeferredResult<ResponseEntity<Void>> deferred = executor.submit(() -> OK);
            awaitResult(deferred);
            assertThat(deferred.getResult()).isEqualTo(OK);
        }
        finally {
            executor.shutdown();
        }
    }

    @Test
    void rejectsWith503WhenWorkersAndQueueAreSaturated() throws InterruptedException {
        // One worker, queue capacity one: the worker takes task A, task B fills the queue, task C must be rejected.
        BoundedSendExecutor executor = new BoundedSendExecutor("test", 1, 1, 60_000);
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            executor.submit(() -> { // A: occupies the single worker
                workerStarted.countDown();
                awaitQuietly(release);
                return OK;
            });
            assertThat(workerStarted.await(5, TimeUnit.SECONDS)).isTrue();

            executor.submit(() -> OK); // B: fills the single queue slot

            DeferredResult<ResponseEntity<Void>> rejected = executor.submit(() -> OK); // C: must be rejected immediately
            assertThat(rejected.hasResult()).isTrue();
            assertThat(((ResponseEntity<?>) rejected.getResult()).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        }
        finally {
            release.countDown();
            executor.shutdown();
        }
    }

    @Test
    void skipsTaskWhenDeferredResultAlreadyCompleted() throws InterruptedException {
        // Simulates the safety timeout firing while the task waits in the queue: the worker must NOT run the task,
        // to avoid sending a notification after the client already received a (timeout) response.
        BoundedSendExecutor executor = new BoundedSendExecutor("test", 1, 10, 60_000);
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean queuedTaskRan = new AtomicBoolean(false);
        CountDownLatch followerDone = new CountDownLatch(1);
        try {
            executor.submit(() -> { // occupies the worker so the next submissions queue behind it
                workerStarted.countDown();
                awaitQuietly(release);
                return OK;
            });
            assertThat(workerStarted.await(5, TimeUnit.SECONDS)).isTrue();

            DeferredResult<ResponseEntity<Void>> queued = executor.submit(() -> {
                queuedTaskRan.set(true);
                return OK;
            });
            // Complete it as the timeout path would, while it is still waiting in the queue.
            queued.setResult(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build());

            // A follower task lets us deterministically wait until the worker has processed the queued task.
            executor.submit(() -> {
                followerDone.countDown();
                return OK;
            });

            release.countDown();
            assertThat(followerDone.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(queuedTaskRan).isFalse();
        }
        finally {
            release.countDown();
            executor.shutdown();
        }
    }

    @Test
    void completesWith500WhenTaskThrows() throws InterruptedException {
        BoundedSendExecutor executor = new BoundedSendExecutor("test", 1, 10, 60_000);
        try {
            DeferredResult<ResponseEntity<Void>> deferred = executor.submit(() -> {
                throw new RuntimeException("boom");
            });
            awaitResult(deferred);
            assertThat(((ResponseEntity<?>) deferred.getResult()).getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }
        finally {
            executor.shutdown();
        }
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new BoundedSendExecutor("test", 0, 10, 1000));
        assertThrows(IllegalArgumentException.class, () -> new BoundedSendExecutor("test", 1, 0, 1000));
    }

    private static void awaitResult(DeferredResult<?> deferred) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!deferred.hasResult() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(deferred.hasResult()).isTrue();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
