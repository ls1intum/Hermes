package de.tum.cit.artemis.push.firebase;

import com.google.api.client.http.LowLevelHttpRequest;
import com.google.api.client.http.LowLevelHttpResponse;
import com.google.api.client.testing.http.MockHttpTransport;
import com.google.api.client.testing.http.MockLowLevelHttpRequest;
import com.google.api.client.testing.http.MockLowLevelHttpResponse;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import de.tum.cit.artemis.push.common.NotificationRequest;
import de.tum.cit.artemis.push.common.PushNotificationApiType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.async.DeferredResult;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the full Firebase send path of {@link FirebaseSendService}. A {@link MockHttpTransport}
 * stands in for the FCM gateway at the HTTP boundary, so the real firebase-admin SDK still serialises
 * each message; the test asserts what reaches the gateway and how Hermes maps responses onto HTTP
 * status codes and health state.
 */
class FirebaseSendServiceTest {

    private static final AtomicInteger APP_COUNTER = new AtomicInteger();
    private static final String FCM_SUCCESS_BODY = "{\"name\":\"projects/test-project/messages/fake-message-id\"}";

    private FirebaseApp app;

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.delete();
            app = null;
        }
    }

    @Test
    void sendsEachMessageToFcmWithEncryptedData() {
        RecordingTransport transport = RecordingTransport.respondingWith(200, FCM_SUCCESS_BODY);
        FirebaseSendService service = serviceWith(transport);

        List<NotificationRequest> requests = List.of(
                new NotificationRequest("iv-1", "cipher-1", "token-1", PushNotificationApiType.DEFAULT),
                new NotificationRequest("iv-2", "cipher-2", "token-2", PushNotificationApiType.IOS_V2));

        ResponseEntity<Void> response = service.doSend(requests);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(service.isHealthy()).isTrue();
        assertThat(transport.urls).hasSize(2);
        assertThat(transport.urls).allMatch(url -> url.contains("/v1/projects/test-project/messages:send"));
        String allBodies = String.join("\n", transport.bodies);
        assertThat(allBodies).contains("token-1").contains("token-2");
        assertThat(allBodies).contains("iv-1").contains("cipher-1");
    }

    @Test
    void rejectsBatchLargerThan500WithoutContactingFcm() {
        RecordingTransport transport = RecordingTransport.respondingWith(200, FCM_SUCCESS_BODY);
        FirebaseSendService service = serviceWith(transport);

        ResponseEntity<Void> response = body(service.send(requests(501)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(transport.urls).as("oversized batch must be rejected before any HTTP call").isEmpty();
    }

    @Test
    void returnsExpectationFailedWhenFcmRejectsTheBatch() {
        // FCM responds with an authentication error. firebase-admin surfaces this as a
        // FirebaseMessagingException, which Hermes must translate into 417 -- and crucially it must
        // NOT crash when getMessagingErrorCode() is null (regression guard for the previous
        // List.of(...).contains(null) NullPointerException in the error handler).
        String authError = "{\"error\":{\"code\":401,\"message\":\"invalid auth\",\"status\":\"UNAUTHENTICATED\","
                + "\"details\":[{\"@type\":\"type.googleapis.com/google.firebase.fcm.v1.FcmError\",\"errorCode\":\"THIRD_PARTY_AUTH_ERROR\"}]}}";
        RecordingTransport transport = RecordingTransport.respondingWith(401, authError);
        FirebaseSendService service = serviceWith(transport);

        ResponseEntity<Void> response = service.doSend(requests(2));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.EXPECTATION_FAILED);
        assertThat(transport.urls).as("FCM should have been contacted for the batch").isNotEmpty();
    }

    @Test
    void isANoOpWhenNoFirebaseCredentialsArePresent() {
        FirebaseSendService service = new FirebaseSendService(Optional.empty());

        ResponseEntity<Void> response = body(service.send(requests(3)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(service.isHealthy()).isFalse();
    }

    private FirebaseSendService serviceWith(MockHttpTransport transport) {
        GoogleCredentials credentials = GoogleCredentials.create(
                new AccessToken("fake-access-token", new Date(System.currentTimeMillis() + 3_600_000L)));
        FirebaseOptions options = FirebaseOptions.builder()
                .setCredentials(credentials)
                .setProjectId("test-project")
                .setHttpTransport(transport)
                .build();
        app = FirebaseApp.initializeApp(options, "fcm-test-" + APP_COUNTER.incrementAndGet());
        return new FirebaseSendService(Optional.of(app));
    }

    /** Extracts the response from a DeferredResult that the service completed synchronously (size/no-op paths). */
    @SuppressWarnings("unchecked")
    private static ResponseEntity<Void> body(DeferredResult<ResponseEntity<Void>> deferred) {
        assertThat(deferred.hasResult()).as("deferred result should be completed synchronously").isTrue();
        return (ResponseEntity<Void>) deferred.getResult();
    }

    private static List<NotificationRequest> requests(int count) {
        List<NotificationRequest> requests = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            requests.add(new NotificationRequest("iv-" + i, "cipher-" + i, "token-" + i, PushNotificationApiType.DEFAULT));
        }
        return requests;
    }

    /** A mock FCM gateway that records the requests it receives and returns a canned response. */
    private static final class RecordingTransport extends MockHttpTransport {
        final List<String> urls = new CopyOnWriteArrayList<>();
        final List<String> bodies = new CopyOnWriteArrayList<>();
        private final int statusCode;
        private final String responseBody;

        private RecordingTransport(int statusCode, String responseBody) {
            this.statusCode = statusCode;
            this.responseBody = responseBody;
        }

        static RecordingTransport respondingWith(int statusCode, String responseBody) {
            return new RecordingTransport(statusCode, responseBody);
        }

        @Override
        public LowLevelHttpRequest buildRequest(String method, String url) {
            urls.add(url);
            return new MockLowLevelHttpRequest(url) {
                @Override
                public LowLevelHttpResponse execute() throws IOException {
                    bodies.add(getContentAsString());
                    return new MockLowLevelHttpResponse()
                            .setStatusCode(statusCode)
                            .setContentType("application/json; charset=UTF-8")
                            .setContent(responseBody);
                }
            };
        }
    }
}
