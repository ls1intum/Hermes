package de.tum.cit.artemis.push.common;

import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.async.DeferredResult;

/**
 * A service that relays push notifications to an external provider (APNs, Firebase, ...).
 * <p>
 * Sending is asynchronous: the implementation hands the work to a bounded worker pool and immediately
 * returns a {@link DeferredResult}. This frees the Tomcat request thread right away, so that a slow or
 * unreachable provider can never exhaust the servlet thread pool and starve the health endpoint.
 */
public interface SendService<T> {

    /**
     * Accepts a send request and returns a {@link DeferredResult} that completes once the notification has
     * been relayed (or rejected). The HTTP connection is held open until the deferred result is set or its
     * safety timeout fires (then it completes with {@code 503 Service Unavailable}).
     *
     * @param request the notification(s) to send
     * @return a deferred HTTP response (2xx on success, 4xx on a provider-side reject, 503 on saturation/timeout)
     */
    DeferredResult<ResponseEntity<Void>> send(T request);

    /**
     * @return the last known health of the connection to the external provider. Read from a cached flag only;
     *         never performs network I/O, so it cannot be starved by a provider outage.
     */
    boolean isHealthy();
}
