package com.aquagrid.platform.iot;

import com.aquagrid.platform.AbstractIntegrationTest;
import com.aquagrid.platform.common.error.BusinessException;
import com.aquagrid.platform.iot.application.service.DeviceManagementService;
import com.aquagrid.platform.iot.domain.model.Device;
import com.aquagrid.platform.iot.infrastructure.persistence.DeviceRepository;
import com.aquagrid.platform.iot.web.dto.DeviceDto;
import com.aquagrid.platform.identity.infrastructure.persistence.OrganizationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verification of Module 6 device registration against real PostGIS.
 *
 * <p>The point under test is that registration is communication-independent: the same core fields
 * register a device on any network, and only the communication block changes. The cases that would
 * silently produce an unreachable device — a field belonging to the wrong technology, an identity
 * field that never reaches the indexed column ingestion resolves through, a root key echoed back to
 * a client — are asserted explicitly, because none of them is visible from the UI.
 */
class DeviceRegistrationIT extends AbstractIntegrationTest {

    /** Distinguishes rows across tests without a per-test cleanup; the code is only unique-per-org. */
    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private DeviceManagementService deviceService;
    @Autowired
    private DeviceRepository deviceRepository;
    @Autowired
    private OrganizationRepository organizationRepository;

    private UUID organizationId() {
        return organizationRepository.findByCodeIgnoreCase("SYSTEM").orElseThrow().getId();
    }

    private String code(String prefix) {
        return prefix + "-" + SEQ.incrementAndGet();
    }

    @Test
    @DisplayName("registers an NB-IoT device and addresses it by IMEI")
    void registersNbIotDevice() {
        DeviceDto device = deviceService.register(organizationId(), null, request(
                code("NBIOT"), "NB_IOT",
                Map.of("imei", "356938035643809", "sim", "8991101200003204510",
                        "operator", "Jio")));

        assertThat(device.communicationType()).isEqualTo("NB_IOT");
        // The IMEI is what an uplink will be resolved through.
        assertThat(device.networkAddress()).isEqualTo("356938035643809");
        assertThat(device.communication()).containsEntry("operator", "Jio");
        assertThat(device.communicationSecretsSet()).isEmpty();

        // The core fields are untouched by the choice of technology.
        assertThat(device.deviceType()).isEqualTo("WATER_METER");
        assertThat(device.manufacturer()).isEqualTo("Kamstrup");
        assertThat(device.installationDate()).isEqualTo(LocalDate.of(2026, 3, 14));
        assertThat(device.coordinates()).containsExactly(76.9366, 8.5241);
    }

    @Test
    @DisplayName("registers a LoRaWAN device, addresses it by DevEUI and never returns the AppKey")
    void registersLorawanDeviceAndHidesAppKey() {
        String appKey = "0123456789ABCDEF0123456789ABCDEF";
        DeviceDto device = deviceService.register(organizationId(), null, request(
                code("LORA"), "LORAWAN",
                Map.of("devEui", "a81758fffe03f2b1", "joinEui", "70B3D57ED0000000",
                        "appKey", appKey)));

        // Normalised to upper case so two casings of one EUI cannot register as two devices.
        assertThat(device.networkAddress()).isEqualTo("A81758FFFE03F2B1");
        assertThat(device.communication()).containsEntry("joinEui", "70B3D57ED0000000");

        // The root key is acknowledged as present, and never returned in any form.
        assertThat(device.communicationSecretsSet()).containsExactly("appKey");
        assertThat(device.communication()).doesNotContainKey("appKey");
        assertThat(device.toString()).doesNotContain(appKey);

        // Nor is it stored in the clear.
        Device stored = deviceRepository.findById(device.id()).orElseThrow();
        assertThat(stored.getProvisioning().get("secret:appKey"))
                .isNotNull()
                .isNotEqualTo(appKey);
    }

    @Test
    @DisplayName("rejects a communication field belonging to another technology")
    void rejectsForeignCommunicationField() {
        assertThatThrownBy(() -> deviceService.register(organizationId(), null, request(
                code("MIX"), "NB_IOT", Map.of("imei", "356938035643801", "devEui", "a81758fffe03f2b2"))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("devEui")
                .hasMessageContaining("NB_IOT");
    }

    @Test
    @DisplayName("rejects a malformed identity field with the rule the operator can act on")
    void rejectsMalformedImei() {
        assertThatThrownBy(() -> deviceService.register(organizationId(), null, request(
                code("BADIMEI"), "NB_IOT", Map.of("imei", "12345"))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("IMEI must be 15 digits");
    }

    @Test
    @DisplayName("requires the identity field, because without it no uplink can be resolved")
    void requiresIdentityField() {
        assertThatThrownBy(() -> deviceService.register(organizationId(), null, request(
                code("NOIMEI"), "NB_IOT", Map.of("operator", "Airtel"))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("IMEI is required");
    }

    @Test
    @DisplayName("registers a device on a technology that has no provisioning fields at all")
    void registersDeviceWithoutCommunicationFields() {
        // ETHERNET is the only profile that genuinely addresses a device by nothing at all. HTTP
        // used to live here as a "network" with a required deviceId; it is now DeviceProtocol, and
        // a push device that needs an address picks a network that has one (or is resolved by
        // device code on the ingress).
        DeviceDto device = deviceService.register(organizationId(), null, request(
                code("PUSH"), "ETHERNET", Map.of()));

        assertThat(device.networkAddress()).isNull();
        assertThat(device.communication()).isEmpty();
        assertThat(device.protocol()).isEqualTo("HTTP");
    }

    @Test
    @DisplayName("HTTP is no longer a network, and saying so is a validation error")
    void httpIsNotACommunicationType() {
        assertThatThrownBy(() -> deviceService.register(organizationId(), null,
                request(code("PUSH-HTTP"), "HTTP", Map.of())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Communication Type");
    }

    @Test
    @DisplayName("a device arrives over HTTP unless the request says otherwise")
    void defaultsToHttpProtocol() {
        DeviceDto device = deviceService.register(organizationId(), null,
                request(code("PROTO"), "NB_IOT", Map.of("imei", "356938035643900")));

        assertThat(device.protocol()).isEqualTo("HTTP");
    }

    @Test
    @DisplayName("protocol can be set independently of the network")
    void protocolIsIndependentOfNetwork() {
        DeviceDto device = deviceService.register(organizationId(), null,
                request(code("MQTT-PROTO"), "NB_IOT", Map.of("imei", "356938035643899"),
                        null, "MQTT"));

        assertThat(device.communicationType()).isEqualTo("NB_IOT");
        assertThat(device.protocol()).isEqualTo("MQTT");
    }

    @Test
    @DisplayName("an unknown protocol is rejected rather than stored")
    void rejectsUnknownProtocol() {
        assertThatThrownBy(() -> deviceService.register(organizationId(), null,
                request(code("BADPROTO"), "ETHERNET", Map.of(), null, "COAP")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Protocol");
    }

    @Test
    @DisplayName("a device is LIVE unless the request says otherwise")
    void defaultsToLiveSource() {
        DeviceDto device = deviceService.register(organizationId(), null,
                request(code("REAL"), "NB_IOT", Map.of("imei", "356938035643901")));

        assertThat(device.deviceSource()).isEqualTo("LIVE");
    }

    @Test
    @DisplayName("Postman/API test is a source, not a network")
    void apiTestIsASource() {
        DeviceDto device = deviceService.register(organizationId(), null, request(
                code("POSTMAN"), "ETHERNET", Map.of(), "API_TEST"));

        assertThat(device.deviceSource()).isEqualTo("API_TEST");
        assertThat(device.communicationType()).isEqualTo("ETHERNET");
        assertThat(device.protocol()).isEqualTo("HTTP");
    }

    /*
     * The point of the whole separation: "simulated" is not a network. A simulated meter emulates a
     * real one — same technology, same identity field, same indexed address an uplink resolves
     * through — and differs only in where its readings come from. Under the old model, where
     * SIMULATOR was a CommunicationProfile with no identity field, this device could not have had an
     * address at all.
     */
    @Test
    @DisplayName("a simulated device emulates a real network and is addressed on it")
    void simulatedDeviceKeepsItsNetworkAndAddress() {
        DeviceDto device = deviceService.register(organizationId(), null, request(
                code("SIM"), "LORAWAN", Map.of("devEui", "a81758fffe03f2e1"), "SIMULATOR"));

        assertThat(device.deviceSource()).isEqualTo("SIMULATOR");
        assertThat(device.communicationType()).isEqualTo("LORAWAN");
        assertThat(device.networkAddress()).isEqualTo("A81758FFFE03F2E1");
    }

    @Test
    @DisplayName("simulator is no longer a communication type, and saying so is a validation error")
    void simulatorIsNotACommunicationType() {
        // Every constant of CommunicationProfile, and every one of them names a *network*. The
        // socket and broker transports arrived with the receiver module; SIMULATOR is absent
        // because "is this data real" is a different question, answered by DeviceSource.
        assertThat(deviceService.communicationTypes()).extracting(t -> t.id())
                .containsExactlyInAnyOrder("LORAWAN", "NB_IOT", "CELLULAR", "ETHERNET",
                        "MQTT", "TCP", "UDP", "WEBSOCKET")
                .doesNotContain("SIMULATOR")
                .doesNotContain("HTTP");

        assertThatThrownBy(() -> deviceService.register(organizationId(), null,
                request(code("OLDSIM"), "SIMULATOR", Map.of())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Communication Type");
    }

    @Test
    @DisplayName("an unknown device source is rejected rather than stored")
    void rejectsUnknownSource() {
        assertThatThrownBy(() -> deviceService.register(organizationId(), null,
                request(code("BADSRC"), "ETHERNET", Map.of(), "MOCK")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Device Source");
    }

    @Test
    @DisplayName("the registry can be filtered down to real devices only")
    void filtersBySource() {
        deviceService.register(organizationId(), null,
                request(code("SRC-LIVE"), "NB_IOT", Map.of("imei", "356938035643902")));
        deviceService.register(organizationId(), null,
                request(code("SRC-SIM"), "NB_IOT", Map.of("imei", "356938035643903"), "SIMULATOR"));

        assertThat(deviceService.listDevices(organizationId(), null, null, null, "SIMULATOR", null, null,
                PageRequest.of(0, 100)).getContent())
                .isNotEmpty()
                .allSatisfy(d -> assertThat(d.deviceSource()).isEqualTo("SIMULATOR"));

        assertThat(deviceService.listDevices(organizationId(), null, null, null, "LIVE", null, null,
                PageRequest.of(0, 100)).getContent())
                .isNotEmpty()
                .allSatisfy(d -> assertThat(d.deviceSource()).isEqualTo("LIVE"));
    }

    @Test
    @DisplayName("rejects a second device claiming the same network address")
    void rejectsDuplicateNetworkAddress() {
        String imei = "356938035643888";
        deviceService.register(organizationId(), null, request(code("DUP-A"), "NB_IOT", Map.of("imei", imei)));

        assertThatThrownBy(() -> deviceService.register(organizationId(), null,
                request(code("DUP-B"), "NB_IOT", Map.of("imei", imei))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(imei);
    }

    @Test
    @DisplayName("rejects a second device claiming the same Device ID")
    void rejectsDuplicateDeviceCode() {
        String deviceCode = code("SAME");
        deviceService.register(organizationId(), null,
                request(deviceCode, "NB_IOT", Map.of("imei", "356938035643877")));

        assertThatThrownBy(() -> deviceService.register(organizationId(), null,
                request(deviceCode, "NB_IOT", Map.of("imei", "356938035643866"))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already registered");
    }

    /*
     * The DEVICE_TOKEN scheme was unreachable for every registered device: the authenticator looks
     * a device up by a `deviceTokenHash` provisioning field, and registration only ever wrote
     * `secret:deviceToken` — ciphertext, which AES-GCM's random IV makes useless as a lookup key.
     * Nothing failed; the strongest identity-bearing authenticator the platform has simply matched
     * nothing, forever. These two tests are what makes that visible.
     */
    @Test
    @DisplayName("a device token is stored both encrypted and as a searchable digest")
    void deviceTokenIsStoredWithALookupDigest() {
        String token = "wss-device-token-93f1";
        DeviceDto device = deviceService.register(organizationId(), null, request(
                code("WS"), "WEBSOCKET",
                Map.of("clientId", "ws-meter-01", "deviceToken", token)));

        assertThat(device.communicationSecretsSet()).containsExactly("deviceToken");
        assertThat(device.communication()).doesNotContainKey("deviceToken");

        Map<String, Object> provisioning =
                deviceRepository.findById(device.id()).orElseThrow().getProvisioning();

        // The digest is what the receiver searches, and it must be the full-length SHA-256 the rest
        // of the world computes — the replay cache's truncated variant would match nothing.
        // Hard-coded rather than recomputed with the same helper the code uses: an assertion that
        // called Sha256.hex would agree with a truncated or salted implementation just as happily.
        // This is `printf 'wss-device-token-93f1' | sha256sum`.
        assertThat(provisioning.get("deviceTokenHash"))
                .isEqualTo("e936970ae5b15bfbb560feef291fbf3bee6072b1865c58298a44986a435f2d6a");
        // And the token itself is never stored in the clear.
        assertThat(String.valueOf(provisioning.get("secret:deviceToken"))).doesNotContain(token);
    }

    @Test
    @DisplayName("an edit that omits the device token keeps its digest, not just its ciphertext")
    void editKeepsDeviceTokenDigest() {
        DeviceDto device = deviceService.register(organizationId(), null, request(
                code("WS-EDIT"), "WEBSOCKET",
                Map.of("clientId", "ws-meter-02", "deviceToken", "wss-device-token-aa02")));
        Object hash = deviceRepository.findById(device.id()).orElseThrow()
                .getProvisioning().get("deviceTokenHash");

        // The edit form cannot echo a secret it was never given. Carrying the ciphertext forward but
        // dropping the digest would leave the device holding a working token the receiver can no
        // longer find it by — authentication breaking on an edit that changed the device's name.
        deviceService.update(device.id(), organizationId(), null, request(
                null, "WEBSOCKET", Map.of("clientId", "ws-meter-02")));

        assertThat(deviceRepository.findById(device.id()).orElseThrow()
                .getProvisioning().get("deviceTokenHash")).isEqualTo(hash);
    }

    @Test
    @DisplayName("an edit that omits the AppKey keeps the stored one")
    void editKeepsUnsentSecret() {
        DeviceDto device = deviceService.register(organizationId(), null, request(
                code("KEEP"), "LORAWAN",
                Map.of("devEui", "a81758fffe03f2c1", "appKey", "0123456789ABCDEF0123456789ABCDEF")));
        Object stored = deviceRepository.findById(device.id()).orElseThrow()
                .getProvisioning().get("secret:appKey");

        // The edit form cannot echo a secret it was never given, so it posts the block without one.
        DeviceDto edited = deviceService.update(device.id(), organizationId(), null, request(
                null, "LORAWAN", Map.of("devEui", "a81758fffe03f2c1")));

        assertThat(edited.communicationSecretsSet()).containsExactly("appKey");
        assertThat(deviceRepository.findById(device.id()).orElseThrow()
                .getProvisioning().get("secret:appKey")).isEqualTo(stored);
    }

    @Test
    @DisplayName("switching technology re-addresses the device and drops the old credentials")
    void switchingTechnologyReplacesCommunicationBlock() {
        DeviceDto device = deviceService.register(organizationId(), null, request(
                code("SWITCH"), "LORAWAN", Map.of("devEui", "a81758fffe03f2d1")));
        assertThat(device.networkAddress()).isEqualTo("A81758FFFE03F2D1");

        DeviceDto moved = deviceService.update(device.id(), organizationId(), null, request(
                null, "NB_IOT", Map.of("imei", "356938035643855")));

        assertThat(moved.communicationType()).isEqualTo("NB_IOT");
        assertThat(moved.networkAddress()).isEqualTo("356938035643855");
        assertThat(moved.communication()).doesNotContainKey("devEui");
    }

    @Test
    @DisplayName("the Device ID is immutable once registered")
    void deviceCodeIsImmutable() {
        DeviceDto device = deviceService.register(organizationId(), null,
                request(code("FIXED"), "NB_IOT", Map.of("imei", "356938035643844")));

        assertThatThrownBy(() -> deviceService.update(device.id(), organizationId(), null,
                request("RENAMED", "NB_IOT", Map.of("imei", "356938035643844"))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("cannot be changed");
    }

    @Test
    @DisplayName("the communication catalogue matches what the server actually enforces")
    void catalogueDescribesEachTechnology() {
        var types = deviceService.communicationTypes();

        var lorawan = types.stream().filter(t -> t.id().equals("LORAWAN")).findFirst().orElseThrow();
        assertThat(lorawan.identityField()).isEqualTo("devEui");
        assertThat(lorawan.fields()).extracting(f -> f.key())
                .containsExactly("devEui", "joinEui", "appKey");
        assertThat(lorawan.fields()).filteredOn(f -> f.secret()).extracting(f -> f.key())
                .containsExactly("appKey");

        // NB-IoT and 4G share a modem family, and therefore a field set.
        var nbIot = types.stream().filter(t -> t.id().equals("NB_IOT")).findFirst().orElseThrow();
        var cellular = types.stream().filter(t -> t.id().equals("CELLULAR")).findFirst().orElseThrow();
        assertThat(nbIot.fields()).extracting(f -> f.key()).containsExactly("imei", "sim", "operator");
        assertThat(cellular.fields()).isEqualTo(nbIot.fields());
    }

    @Test
    @DisplayName("a device registered before its SIM is allocated has no network address")
    void allowsRegistrationWithoutNetworkAddress() {
        DeviceDto first = deviceService.register(organizationId(), null,
                request(code("PENDING-A"), "ETHERNET", Map.of()));
        DeviceDto second = deviceService.register(organizationId(), null,
                request(code("PENDING-B"), "ETHERNET", Map.of()));

        // Both are NULL. The unique index is partial, so they must not collide.
        assertThat(first.networkAddress()).isNull();
        assertThat(second.networkAddress()).isNull();
        assertThat(deviceRepository.findForTenant(organizationId(), null, "ETHERNET", null, null,
                null, null, PageRequest.of(0, 25)).getTotalElements()).isGreaterThanOrEqualTo(2);
    }

    private DeviceDto.RegistrationRequest request(String deviceCode, String communicationType,
                                                  Map<String, String> communication) {
        return request(deviceCode, communicationType, communication, null, null);
    }

    private DeviceDto.RegistrationRequest request(String deviceCode, String communicationType,
                                                  Map<String, String> communication,
                                                  String deviceSource) {
        return request(deviceCode, communicationType, communication, deviceSource, null);
    }

    private DeviceDto.RegistrationRequest request(String deviceCode, String communicationType,
                                                  Map<String, String> communication,
                                                  String deviceSource, String protocol) {
        return new DeviceDto.RegistrationRequest(
                deviceCode,
                "Ward 7 bulk meter",
                "WATER_METER",
                "AST-0091",
                null,
                deviceSource,
                protocol,
                communicationType,
                "Kamstrup",
                "flowIQ 2200",
                "SN-40021",
                LocalDate.of(2026, 3, 14),
                "PROVISIONED",
                new double[]{76.9366, 8.5241},
                communication,
                "1.4.2");
    }
}
