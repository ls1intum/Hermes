package de.tum.cit.artemis.push.common;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.async.DeferredResult;

/**
 * A small, self-contained async dispatcher that bounds how much push-notification work can be in flight or
 * queued at any moment. It is the core of the relay's backpressure strategy.
 *
 * <p>Why this exists: the original relay ran the blocking provider call ({@code apnsClient.sendNotification().get()})
 * directly on the Tomcat request thread. When the provider was unreachable or misconfigured,
 * every request thread blocked and the health endpoint — also served by a Tomcat thread — could no longer be
 * answered, so the whole service appeared dead. This dispatcher fixes that by:
 * <ul>
 *     <li>running the blocking provider call on a <b>fixed</b> worker pool (never on the Tomcat thread), and</li>
 *     <li>bounding the backlog with an {@link ArrayBlockingQueue}: once {@code workers + queueCapacity} requests
 *         are outstanding, further submissions are rejected immediately with {@code 503}.</li>
 * </ul>
 *
 * <p>Because a worker never starts a second provider call before its first one returns, the number of concurrent
 * provider calls is bounded by the worker count. This is what keeps the provider library's own internal queues
 * (e.g. pushy's unbounded {@code pendingAcquisitionPromises}) bounded without any extra machinery — no circuit
 * breaker, no semaphores, no client rebuild are required for the stated goals.
 */
public class BoundedSendExecutor {

    private static final Logger log = LoggerFactory.getLogger(BoundedSendExecutor.class);

    private static final ResponseEntity<Void> SERVICE_UNAVAILABLE = ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();

    private static final ResponseEntity<Void> INTERNAL_SERVER_ERROR = ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();

    private final String name;

    private final long responseTimeoutMillis;

    private final ThreadPoolExecutor executor;

    // Counts requests answered with 503 because the queue was full / because the safety timeout fired. Both are no-ops
    // when no MeterRegistry was supplied.
    private final Runnable onRejected;

    private final Runnable onTimeout;

    /**
     * Builds a dispatcher without metrics. See {@link #BoundedSendExecutor(String, int, int, long, MeterRegistry)}.
     */
    public BoundedSendExecutor(String name, int workers, int queueCapacity, long responseTimeoutMillis) {
        this(name, workers, queueCapacity, responseTimeoutMillis, null);
    }

    /**
     * @param name                  a short name used for thread names and log messages (e.g. "apns")
     * @param workers               the fixed number of worker threads (== max concurrent provider calls)
     * @param queueCapacity         the maximum number of requests that may wait for a free worker before new
     *                              requests are rejected with {@code 503}
     * @param responseTimeoutMillis how long the HTTP connection is held open before the deferred result completes
     *                              with {@code 503}; the worker is not interrupted and simply finds the result
     *                              already completed when it eventually runs
     * @param meterRegistry         optional; when set, the pool's queue depth / active threads (standard
     *                              {@code executor.*} meters) and the {@code hermes.send.rejected} and
     *                              {@code hermes.send.timeouts} counters are published, tagged with the provider name
     */
    public BoundedSendExecutor(String name, int workers, int queueCapacity, long responseTimeoutMillis, MeterRegistry meterRegistry) {
        if (workers < 1) {
            throw new IllegalArgumentException("workers must be >= 1, was " + workers);
        }
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("queueCapacity must be >= 1, was " + queueCapacity);
        }
        this.name = name;
        this.responseTimeoutMillis = responseTimeoutMillis;
        // core == max so all threads are long-lived and the bounded queue (not extra threads) absorbs bursts;
        // when both the workers and the queue are saturated, AbortPolicy throws RejectedExecutionException.
        this.executor = new ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(queueCapacity), namedThreadFactory(name),
                new ThreadPoolExecutor.AbortPolicy());

        if (meterRegistry != null) {
            new ExecutorServiceMetrics(executor, name, List.of()).bindTo(meterRegistry);
            Counter rejected = Counter.builder("hermes.send.rejected").tag("provider", name)
                    .description("Requests rejected with 503 because the worker pool and its queue were saturated").register(meterRegistry);
            Counter timeouts = Counter.builder("hermes.send.timeouts").tag("provider", name)
                    .description("Requests answered with 503 because the response timeout fired before the send completed").register(meterRegistry);
            this.onRejected = rejected::increment;
            this.onTimeout = timeouts::increment;
        }
        else {
            this.onRejected = () -> {
            };
            this.onTimeout = () -> {
            };
        }
    }

    /**
     * @param response the response to complete with
     * @return an already-completed {@link DeferredResult}, for requests that are answered without any send
     */
    public static DeferredResult<ResponseEntity<Void>> completed(ResponseEntity<Void> response) {
        DeferredResult<ResponseEntity<Void>> deferred = new DeferredResult<>();
        deferred.setResult(response);
        return deferred;
    }

    /**
     * Submits a blocking send task to the worker pool and returns a {@link DeferredResult} that completes with
     * the task's response.
     *
     * <ul>
     *     <li>If the queue is full, the task is rejected and the result completes immediately with {@code 503}.
     *         The upstream caller (Artemis) retries non-2xx with backoff, so this is backpressure, not data loss.</li>
     *     <li>If the safety timeout fires before the worker runs, the result completes with {@code 503}. The worker
     *         then sees {@link DeferredResult#isSetOrExpired()} and skips the provider call, avoiding a send after
     *         the client already received a (timeout) response — which would otherwise risk a duplicate notification.</li>
     * </ul>
     *
     * @param task the blocking work to perform on a worker thread; must return the HTTP response to relay back
     * @return the deferred HTTP response
     */
    public DeferredResult<ResponseEntity<Void>> submit(Supplier<ResponseEntity<Void>> task) {
        DeferredResult<ResponseEntity<Void>> deferred = new DeferredResult<>(responseTimeoutMillis, () -> {
            onTimeout.run();
            return SERVICE_UNAVAILABLE;
        });
        try {
            executor.execute(() -> {
                // The HTTP response may already have been completed by the safety timeout while this task waited
                // in the queue. Do not contact the provider in that case: the client is gone and a late send could
                // duplicate a notification that the caller already retried. This guards the common case (the task
                // sat in the queue past the deadline). It does NOT cover the residual race where the timeout fires
                // after this check but during the provider send already in progress — in that rare case the caller
                // gets 503, may retry, and the in-flight send can still succeed, producing a duplicate notification.
                // That is an accepted trade-off for keeping this design simple (a duplicate push is low-harm).
                if (deferred.isSetOrExpired()) {
                    return;
                }
                try {
                    deferred.setResult(task.get());
                }
                catch (Exception e) {
                    log.error("[{}] send task failed unexpectedly", name, e);
                    deferred.setErrorResult(INTERNAL_SERVER_ERROR);
                }
            });
        }
        catch (RejectedExecutionException e) {
            log.warn("[{}] worker pool saturated (queue full); rejecting with 503", name);
            onRejected.run();
            deferred.setResult(SERVICE_UNAVAILABLE);
        }
        return deferred;
    }

    /** @return the number of tasks currently waiting in the queue (for diagnostics/metrics). */
    public int getQueueSize() {
        return executor.getQueue().size();
    }

    /** @return the number of tasks currently being executed (for diagnostics/metrics). */
    public int getActiveCount() {
        return executor.getActiveCount();
    }

    /**
     * Gracefully stops the worker pool: stops accepting new tasks, waits briefly for in-flight sends to finish,
     * then forces termination. Intended to be called from the owning service's {@code @PreDestroy}.
     */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        }
        catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static ThreadFactory namedThreadFactory(String name) {
        AtomicInteger counter = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable, name + "-sender-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }
}
