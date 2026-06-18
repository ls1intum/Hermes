package de.tum.cit.artemis.push;

import de.tum.cit.artemis.push.apns.ApnsSendService;
import de.tum.cit.artemis.push.common.NotificationRequest;
import de.tum.cit.artemis.push.common.PushNotificationApiType;
import de.tum.cit.artemis.push.firebase.FirebaseSendService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-context smoke test: boots the whole Spring Boot application and drives every HTTP endpoint
 * through MockMvc. The two gateway services and {@link BuildProperties} are replaced with mocks so
 * this test verifies wiring, request/response mapping and health aggregation without needing real
 * credentials. The gateway-level behaviour itself is covered by the dedicated service tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RelayApplicationSmokeTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ApnsSendService apnsSendService;

    @MockitoBean
    private FirebaseSendService firebaseSendService;

    @MockitoBean
    private BuildProperties buildProperties;

    @BeforeEach
    void setUp() {
        when(buildProperties.getVersion()).thenReturn("9.9.9-test");
    }

    @Test
    void healthEndpointAggregatesServiceHealthAndVersion() throws Exception {
        when(apnsSendService.isHealthy()).thenReturn(true);
        when(firebaseSendService.isHealthy()).thenReturn(false);

        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isApnsConnected").value(true))
                .andExpect(jsonPath("$.isFirebaseConnected").value(false))
                .andExpect(jsonPath("$.versionNumber").value("9.9.9-test"));
    }

    @Test
    void aliveEndpointReturnsOk() throws Exception {
        mockMvc.perform(get("/api/push_notification/alive"))
                .andExpect(status().isOk());
    }

    @Test
    void sendApnsDeserializesRequestAndDelegatesToApnsService() throws Exception {
        when(apnsSendService.send(any())).thenReturn(ResponseEntity.ok().build());
        String body = """
                {"initializationVector":"iv","payloadCipherText":"cipher","token":"token-1","apiType":"IOS_V2"}""";

        mockMvc.perform(post("/api/push_notification/send_apns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        ArgumentCaptor<NotificationRequest> captor = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(apnsSendService).send(captor.capture());
        assertThat(captor.getValue().token()).isEqualTo("token-1");
        assertThat(captor.getValue().apiType()).isEqualTo(PushNotificationApiType.IOS_V2);
        assertThat(captor.getValue().initializationVector()).isEqualTo("iv");
        assertThat(captor.getValue().payloadCipherText()).isEqualTo("cipher");
    }

    @Test
    void sendApnsPropagatesServiceErrorStatus() throws Exception {
        when(apnsSendService.send(any())).thenReturn(ResponseEntity.status(HttpStatus.EXPECTATION_FAILED).build());
        String body = """
                {"initializationVector":"iv","payloadCipherText":"cipher","token":"token","apiType":"DEFAULT"}""";

        mockMvc.perform(post("/api/push_notification/send_apns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isExpectationFailed());
    }

    @Test
    void sendFirebaseDeserializesBatchAndDelegatesToFirebaseService() throws Exception {
        when(firebaseSendService.send(anyList())).thenReturn(ResponseEntity.ok().build());
        String body = """
                {"notificationRequest":[
                  {"initializationVector":"iv1","payloadCipherText":"c1","token":"t1","apiType":"DEFAULT"},
                  {"initializationVector":"iv2","payloadCipherText":"c2","token":"t2","apiType":"IOS_V2"}
                ]}""";

        mockMvc.perform(post("/api/push_notification/send_firebase")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<NotificationRequest>> captor = ArgumentCaptor.forClass(List.class);
        verify(firebaseSendService).send(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
        assertThat(captor.getValue().get(0).token()).isEqualTo("t1");
        assertThat(captor.getValue().get(1).apiType()).isEqualTo(PushNotificationApiType.IOS_V2);
    }
}
