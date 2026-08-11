package com.aquagrid.platform.iot.web.dto;

import com.aquagrid.platform.iot.domain.model.CommunicationProfile;
import com.aquagrid.platform.iot.domain.model.Device;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Outbound device representation.
 *
 * <p>The core fields are the same for every device on the network. {@code communication} carries
 * only what the device's own technology defines — SIM/IMEI/operator for NB-IoT and 4G, DevEUI and
 * JoinEUI for LoRaWAN — so a client renders the right form without knowing the rules.
 *
 * <p>Secret communication fields never appear here. AppKey is an AES-128 root key: it goes in
 * encrypted and comes back only as {@code communicationSecretsSet}, a list of which secrets have a
 * value. Returning it masked would still leak its length, and returning it at all would put a root
 * key in every browser cache and access log that touches a device list.
 */
@Schema(name = "Device")
@Builder
public record DeviceDto(
        UUID id,
        String deviceCode,
        String name,
        String deviceType,
        String assetNumber,
        UUID assetId,
        /** LIVE, SIMULATOR or API_TEST — where this device's telemetry originates. */
        String deviceSource,
        /**
         * HTTP or MQTT — the ingress protocol packets arrive on. Orthogonal to
         * {@code communicationType}, which names the network.
         */
        String protocol,
        String communicationType,
        String manufacturer,
        String model,
        String serialNumber,
        LocalDate installationDate,
        String status,
        /** [lon, lat] in EPSG:4326, or null when the device has no recorded position. */
        double[] coordinates,
        /**
         * The address the device is reached at on its network, derived from {@code communication}.
         * Read-only: it is never accepted on a request, only computed from the identity field of
         * the chosen communication type.
         */
        String networkAddress,
        /** Non-secret communication fields, keyed as {@link CommunicationProfile.Field#key()}. */
        Map<String, Object> communication,
        /** Keys of the secret communication fields that currently hold a value. */
        java.util.List<String> communicationSecretsSet,
        String firmwareVersion,
        BigDecimal batteryV,
        BigDecimal rssi,
        BigDecimal snr,
        Instant lastSeenAt,
        Map<String, Object> attributes
) {

    /** Prefix marking an encrypted entry inside the {@code provisioning} column. */
    public static final String SECRET_PREFIX = "secret:";

    public static DeviceDto from(Device device) {
        Map<String, Object> provisioning = device.getProvisioning() == null
                ? Map.of() : device.getProvisioning();

        Map<String, Object> publicFields = new LinkedHashMap<>();
        java.util.List<String> secretsSet = new java.util.ArrayList<>();
        for (Map.Entry<String, Object> entry : provisioning.entrySet()) {
            if (entry.getKey().startsWith(SECRET_PREFIX)) {
                secretsSet.add(entry.getKey().substring(SECRET_PREFIX.length()));
            } else {
                publicFields.put(entry.getKey(), entry.getValue());
            }
        }

        return DeviceDto.builder()
                .id(device.getId())
                .deviceCode(device.getDeviceCode())
                .name(device.getName())
                .deviceType(device.getDeviceType())
                .assetNumber(device.getAssetNumber())
                .assetId(device.getAssetId())
                .deviceSource(device.getSource())
                .protocol(device.getProtocol())
                .communicationType(device.getTransport())
                .manufacturer(device.getManufacturer())
                .model(device.getModel())
                .serialNumber(device.getSerialNumber())
                .installationDate(device.getInstallationDate())
                .status(device.getStatus())
                .coordinates(device.getLocation() == null ? null
                        : new double[]{device.getLocation().getX(), device.getLocation().getY()})
                .networkAddress(device.getNetworkAddress())
                .communication(publicFields)
                .communicationSecretsSet(secretsSet)
                .firmwareVersion(device.getFirmwareVersion())
                .batteryV(device.getBatteryV())
                .rssi(device.getRssi())
                .snr(device.getSnr())
                .lastSeenAt(device.getLastSeenAt())
                .attributes(device.getAttributes())
                .build();
    }

    /**
     * Registration and edit payload.
     *
     * <p>One record for both: the registration form and the edit form ask for the same things, and
     * a second near-identical record is how the two drift apart. On update, a null field means
     * "leave alone"; {@code communication} is replaced wholesale when present, because a partial
     * merge of radio credentials is how a device ends up with a JoinEUI from its previous life.
     *
     * <p>{@code networkAddress} is absent by design — it is derived from {@code communication}.
     */
    @Schema(name = "DeviceRegistrationRequest")
    public record RegistrationRequest(
            String deviceCode,
            String name,
            String deviceType,
            String assetNumber,
            UUID assetId,
            /**
             * LIVE, SIMULATOR or API_TEST. Absent on registration means LIVE: registering a device
             * is an act about a real one unless the operator says otherwise, and defaulting the
             * other way would let a mis-scripted import quietly file real meters as synthetic.
             */
            String deviceSource,
            /**
             * HTTP or MQTT. Absent on registration means HTTP — the bearer every HTTP-family
             * receiver already terminates, and the one Postman speaks.
             */
            String protocol,
            String communicationType,
            String manufacturer,
            String model,
            String serialNumber,
            LocalDate installationDate,
            String status,
            /** [lon, lat] in EPSG:4326. */
            double[] coordinates,
            Map<String, String> communication,
            String firmwareVersion
    ) {
    }

    /**
     * The field catalogue a client needs to render the conditional part of the form.
     *
     * <p>Served from the same enum the server validates against, so the form cannot ask for a
     * field the server will reject, or omit one it requires.
     */
    @Schema(name = "CommunicationTypeDefinition")
    public record CommunicationTypeDefinition(
            String id,
            String identityField,
            java.util.List<FieldDefinition> fields
    ) {
        @Schema(name = "CommunicationFieldDefinition")
        public record FieldDefinition(String key, String label, boolean required, String expectation,
                                      boolean secret) {
        }

        public static CommunicationTypeDefinition from(CommunicationProfile profile) {
            return new CommunicationTypeDefinition(
                    profile.name(),
                    profile.identityField(),
                    profile.fields().stream()
                            .map(f -> new FieldDefinition(f.key(), f.label(), f.required(),
                                    f.format().expectation(), f.secret()))
                            .toList());
        }
    }
}
