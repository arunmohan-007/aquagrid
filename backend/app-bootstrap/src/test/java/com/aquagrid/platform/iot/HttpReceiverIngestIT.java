package com.aquagrid.platform.iot;

import com.aquagrid.platform.AbstractIntegrationTest;
import com.aquagrid.platform.iot.api.DeviceMessage;
import com.aquagrid.platform.iot.application.service.DeviceManagementService;
import com.aquagrid.platform.iot.dataconfig.domain.model.RawTelemetry;
import com.aquagrid.platform.iot.dataconfig.infrastructure.persistence.RawTelemetryRepository;
import com.aquagrid.platform.iot.domain.model.Device;
import com.aquagrid.platform.iot.domain.model.DeviceReading;
import com.aquagrid.platform.iot.infrastructure.persistence.DeviceReadingRepository;
import com.aquagrid.platform.iot.infrastructure.persistence.DeviceRepository;
import com.aquagrid.platform.iot.web.dto.DeviceDto;
import com.aquagrid.platform.identity.infrastructure.persistence.OrganizationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end verification of the production HTTP device ingress.
 *
 * <p>{@code POST /api/v1/receiver/http} is the canonical path a physical meter or modem uses. These
 * cases exercise that route through the servlet stack — Spring Security's public-endpoint permit,
 * the packet assembler, authentication, device resolution, ingestion and raw retention — with a
 * {@code LIVE} device and no simulator in the picture.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "aquagrid.iot.transports.simulator=false",
        "aquagrid.iot.legacy-http-ingest.enabled=false",
        "aquagrid.iot.receiver.security.require-authentication=true",
        "aquagrid.iot.receiver.security.gateways[0].principal=http-it-gateway",
        "aquagrid.iot.receiver.security.gateways[0].api-key-sha256="
                + "f315d667d343b4b802fa25fe570db37cbc33dcd1a8f5ab41d0983352992f9991",
        "aquagrid.iot.receiver.limits.max-packet-bytes=4096"
})
class HttpReceiverIngestIT extends AbstractIntegrationTest {

    /** Plaintext whose SHA-256 is the gateway hash above (same as the local-dev key). */
    private static final String GATEWAY_API_KEY = "aquagrid-local-dev-key";

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private DeviceManagementService deviceService;
    @Autowired
    private DeviceRepository deviceRepository;
    @Autowired
    private DeviceReadingRepository readingRepository;
    @Autowired
    private RawTelemetryRepository rawTelemetryRepository;
    @Autowired
    private OrganizationRepository organizationRepository;

    @Test
    @DisplayName("a LIVE water meter posts HTTPS-style telemetry through the receiver and is stored")
    void liveHttpWaterMeterEndToEnd() throws Exception {
        String imei = nextImei();
        Instant observedAt = Instant.now().minusSeconds(30);
        DeviceDto device = registerLive(imei);

        String body = """
                {
                  "imei": "%s",
                  "observedAt": "%s",
                  "volume": 128450.5,
                  "flowRate": 12.4,
                  "battery": 3.61,
                  "fCnt": 42
                }
                """.formatted(imei, observedAt);

        mockMvc.perform(post("/api/v1/receiver/http")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-API-Key", GATEWAY_API_KEY)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.deviceId").value(device.id().toString()))
                .andExpect(jsonPath("$.packetId").isNotEmpty());

        List<DeviceReading> volume = readings(device.id(), DeviceMessage.Metrics.VOLUME);
        assertThat(volume).isNotEmpty();
        assertThat(volume.getFirst().getValue()).isEqualTo(128450.5);
        // Stamped with the device's registered network — same rule as the simulator cutover claim.
        assertThat(volume.getFirst().getTransport()).isEqualTo("NB_IOT");

        List<DeviceReading> flow = readings(device.id(), DeviceMessage.Metrics.FLOW_RATE);
        assertThat(flow).isNotEmpty();
        assertThat(flow.getFirst().getValue()).isEqualTo(12.4);

        Device stored = deviceRepository.findById(device.id()).orElseThrow();
        assertThat(stored.getSource()).isEqualTo("LIVE");
        assertThat(stored.getOrganizationId()).isEqualTo(organizationId());
        assertThat(stored.getLastSeenAt()).isNotNull();
        assertThat(stored.getBatteryV()).isNotNull();

        List<RawTelemetry> raw = rawTelemetryRepository.search(
                organizationId(), device.id(), null, null, null, PageRequest.of(0, 10)).getContent();
        assertThat(raw).isNotEmpty();
        assertThat(raw.getFirst().getPayload().toString()).contains(imei);
        assertThat(raw.getFirst().getConnectionMode()).isEqualTo("HTTP");
    }

    @Test
    @DisplayName("missing and invalid gateway credentials are refused with 401")
    void rejectsMissingAndInvalidCredentials() throws Exception {
        String imei = nextImei();
        registerLive(imei);
        String body = """
                {"imei":"%s","volume":1.0,"observedAt":"%s"}
                """.formatted(imei, Instant.now().minusSeconds(10));

        mockMvc.perform(post("/api/v1/receiver/http")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("RECEIVER_AUTHENTICATION_FAILED"));

        mockMvc.perform(post("/api/v1/receiver/http")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-API-Key", "not-the-gateway-key")
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("RECEIVER_AUTHENTICATION_FAILED"));
    }

    @Test
    @DisplayName("an unknown IMEI is rejected and cannot invent a tenant")
    void rejectsUnknownDevice() throws Exception {
        mockMvc.perform(post("/api/v1/receiver/http")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-API-Key", GATEWAY_API_KEY)
                        .content("""
                                {"imei":"356938035649999","volume":9.0,"observedAt":"%s"}
                                """.formatted(Instant.now().minusSeconds(10))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECEIVER_UNKNOWN_DEVICE"));
    }

    @Test
    @DisplayName("organizationId in the body is ignored — tenant comes from the resolved device")
    void doesNotTrustOrganizationIdInBody() throws Exception {
        String imei = nextImei();
        DeviceDto device = registerLive(imei);
        UUID foreignOrg = UUID.fromString("00000000-0000-0000-0000-000000000099");

        mockMvc.perform(post("/api/v1/receiver/http")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-API-Key", GATEWAY_API_KEY)
                        .content("""
                                {
                                  "imei": "%s",
                                  "organizationId": "%s",
                                  "tenantId": "%s",
                                  "volume": 55.0,
                                  "observedAt": "%s",
                                  "fCnt": 7
                                }
                                """.formatted(imei, foreignOrg, foreignOrg,
                                        Instant.now().minusSeconds(15))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));

        List<DeviceReading> volume = readings(device.id(), DeviceMessage.Metrics.VOLUME);
        assertThat(volume).isNotEmpty();
        assertThat(volume.getFirst().getOrganizationId()).isEqualTo(organizationId());
        assertThat(volume.getFirst().getOrganizationId()).isNotEqualTo(foreignOrg);
    }

    @Test
    @DisplayName("a duplicate packet is acknowledged as DUPLICATE without a second reading")
    void duplicatePacketIsAcknowledged() throws Exception {
        String imei = nextImei();
        DeviceDto device = registerLive(imei);
        String body = """
                {
                  "imei": "%s",
                  "volume": 200.0,
                  "observedAt": "%s",
                  "fCnt": 99
                }
                """.formatted(imei, Instant.now().minusSeconds(20));

        mockMvc.perform(post("/api/v1/receiver/http")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-API-Key", GATEWAY_API_KEY)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));

        mockMvc.perform(post("/api/v1/receiver/http")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-API-Key", GATEWAY_API_KEY)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("DUPLICATE"));

        assertThat(readings(device.id(), DeviceMessage.Metrics.VOLUME)).hasSize(1);
    }

    @Test
    @DisplayName("an oversized payload is rejected with 413")
    void rejectsOversizedPayload() throws Exception {
        String imei = nextImei();
        registerLive(imei);
        // Limit is 4096 in this test class; pad well past it.
        String padding = "x".repeat(5000);
        String body = """
                {"imei":"%s","volume":1.0,"observedAt":"%s","note":"%s"}
                """.formatted(imei, Instant.now().minusSeconds(5), padding);

        mockMvc.perform(post("/api/v1/receiver/http")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-API-Key", GATEWAY_API_KEY)
                        .content(body.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("RECEIVER_PAYLOAD_TOO_LARGE"));
    }

    @Test
    @DisplayName("malformed JSON is rejected safely")
    void rejectsMalformedJson() throws Exception {
        mockMvc.perform(post("/api/v1/receiver/http")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-API-Key", GATEWAY_API_KEY)
                        .content("{not-json"))
                .andExpect(status().is(anyOf(is(400), is(404))));
    }

    @Test
    @DisplayName("legacy /api/v1/ingest/http cannot bypass receiver security")
    void legacyIngestHttpIsNotReachableWithoutAuth() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/ingest/http")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceEui":"356938035643809","metrics":{"volume":1.0}}
                                """))
                .andExpect(status().isUnauthorized())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("ACCEPTED");
    }

    private DeviceDto registerLive(String imei) {
        return deviceService.register(organizationId(), null, new DeviceDto.RegistrationRequest(
                code("LIVE-HTTP"),
                "Ward 12 HTTP meter",
                "WATER_METER",
                "AST-HTTP-1",
                null,
                "LIVE",
                "HTTP",
                "NB_IOT",
                "Kamstrup",
                "flowIQ 2200",
                null,
                LocalDate.of(2026, 3, 14),
                "ACTIVE",
                new double[]{76.9366, 8.5241},
                Map.of("imei", imei, "operator", "Jio"),
                "1.4.2"));
    }

    private UUID organizationId() {
        return organizationRepository.findByCodeIgnoreCase("SYSTEM").orElseThrow().getId();
    }

    private String code(String prefix) {
        return prefix + "-" + SEQ.incrementAndGet();
    }

    private static String nextImei() {
        // Distinct from DeviceDataConfigurationIT (…565…) and ReadingExportIT (…572…).
        return String.format("35693803581%04d", SEQ.incrementAndGet());
    }

    private List<DeviceReading> readings(UUID deviceId, String metric) {
        return readingRepository.findSeries(deviceId, metric,
                Instant.parse("2020-01-01T00:00:00Z"), Instant.parse("2030-01-01T00:00:00Z"));
    }
}
