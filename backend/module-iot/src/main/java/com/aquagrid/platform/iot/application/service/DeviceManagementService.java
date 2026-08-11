package com.aquagrid.platform.iot.application.service;

import com.aquagrid.platform.common.crypto.CryptoService;
import com.aquagrid.platform.common.error.BusinessException;
import com.aquagrid.platform.common.error.ErrorCode;
import com.aquagrid.platform.common.error.ResourceNotFoundException;
import com.aquagrid.platform.iot.domain.model.CommunicationProfile;
import com.aquagrid.platform.iot.domain.model.Device;
import com.aquagrid.platform.iot.domain.model.DeviceProtocol;
import com.aquagrid.platform.iot.domain.model.DeviceSource;
import com.aquagrid.platform.iot.infrastructure.persistence.DeviceRepository;
import com.aquagrid.platform.iot.web.dto.DeviceDto;
import com.aquagrid.platform.iot.receiver.application.security.Sha256;
import lombok.RequiredArgsConstructor;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Device registration and catalogue administration.
 *
 * <p>Registration is communication-independent: the core fields are validated the same way for
 * every device, and the communication-specific block is validated against
 * {@link CommunicationProfile}, which is the single declaration of what each technology needs.
 * There is no {@code switch (transport)} here — adding a technology must not mean editing this
 * class.
 *
 * <p>Tenant-scoped and identity-independent: resolution of "who provisioned this" goes through the
 * actor UUID supplied by the controller, never through a join to {@code identity.users}. This keeps
 * the IoT module extractable (the planned first microservice) without dragging identity with it.
 */
@Service
@RequiredArgsConstructor
public class DeviceManagementService {

    private static final Set<String> STATUSES =
            Set.of("PROVISIONED", "ACTIVE", "INACTIVE", "DECOMMISSIONED", "FAULTY");

    private static final Set<String> DEVICE_TYPES = Set.of(
            "WATER_METER", "BULK_FLOW_METER", "PRESSURE_SENSOR", "LEVEL_SENSOR",
            "QUALITY_SENSOR", "VALVE_CONTROLLER", "PUMP_CONTROLLER", "ENERGY_METER",
            "GATEWAY", "OTHER");

    /** SRID 4326, matching the column and the rest of the platform's geometry. */
    private static final GeometryFactory GEOMETRY = new GeometryFactory(new PrecisionModel(), 4326);

    private final DeviceRepository deviceRepository;
    private final CryptoService cryptoService;

    @Transactional(readOnly = true)
    public Page<DeviceDto> listDevices(UUID organizationId, String status, String transport,
                                       String deviceType, String source, String protocol,
                                       String search, Pageable pageable) {
        String term = blankToNull(search);
        Page<Device> page = deviceRepository.findForTenant(
                organizationId, blankToNull(status), blankToNull(transport),
                blankToNull(deviceType), normaliseSourceFilter(source),
                normaliseProtocolFilter(protocol),
                term == null ? null : "%" + term.toLowerCase() + "%",
                pageable);
        return page.map(DeviceDto::from);
    }

    @Transactional(readOnly = true)
    public DeviceDto getDevice(UUID deviceId, UUID organizationId) {
        return DeviceDto.from(requireInTenant(deviceId, organizationId));
    }

    /** The form catalogue: every communication type and the fields it defines. */
    @Transactional(readOnly = true)
    public List<DeviceDto.CommunicationTypeDefinition> communicationTypes() {
        return java.util.Arrays.stream(CommunicationProfile.values())
                .map(DeviceDto.CommunicationTypeDefinition::from)
                .toList();
    }

    @Transactional
    public DeviceDto register(UUID organizationId, UUID actorId, DeviceDto.RegistrationRequest request) {
        String deviceCode = required(request.deviceCode(), "Device ID");
        if (deviceRepository.existsByOrganizationIdAndDeviceCodeIgnoreCase(organizationId, deviceCode)) {
            throw new BusinessException(ErrorCode.RESOURCE_CONFLICT,
                    "Device ID " + deviceCode + " is already registered in this organisation.");
        }

        Device device = new Device();
        device.setOrganizationId(organizationId);
        device.setDeviceCode(deviceCode);
        device.setStatus("PROVISIONED");
        // Explicit, though the entity initialises them: a device is real and arrives over HTTP
        // unless the request says otherwise. Protocol is required on the form; the default here
        // covers API callers that omit it (imports, older clients) rather than leaving the column
        // blank.
        device.setSource(DeviceSource.LIVE.name());
        device.setProtocol(DeviceProtocol.HTTP.name());
        apply(device, request, organizationId);
        deviceRepository.save(device);
        return DeviceDto.from(device);
    }

    /**
     * Updates a registered device. Null fields are left alone; a present {@code communication} map
     * replaces the whole block.
     *
     * <p>The device code is immutable. It is the identity field operators quote in the field and
     * print on work orders, and letting it change silently re-points every paper reference.
     */
    @Transactional
    public DeviceDto update(UUID deviceId, UUID organizationId, UUID actorId,
                            DeviceDto.RegistrationRequest request) {
        Device device = requireInTenant(deviceId, organizationId);
        if (request.deviceCode() != null && !request.deviceCode().equals(device.getDeviceCode())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Device ID cannot be changed after registration.");
        }
        apply(device, request, organizationId);
        deviceRepository.save(device);
        return DeviceDto.from(device);
    }

    // --- Field application -----------------------------------------------------------------

    /**
     * Writes the request onto the device: core fields first, then the communication block.
     *
     * <p>Shared by registration and update so the two can never validate differently — the only
     * distinction is that registration starts from a blank device, where "leave alone" and "not
     * set" are the same thing.
     */
    private void apply(Device device, DeviceDto.RegistrationRequest request, UUID organizationId) {
        if (request.name() != null) {
            device.setName(required(request.name(), "Device Name"));
        }
        if (request.deviceType() != null) {
            device.setDeviceType(requireOneOf(request.deviceType(), DEVICE_TYPES, "Device Type"));
        }
        if (request.status() != null) {
            device.setStatus(requireOneOf(request.status(), STATUSES, "Status"));
        }
        if (request.deviceSource() != null) {
            device.setSource(requireSource(request.deviceSource()).name());
        }
        if (request.protocol() != null) {
            device.setProtocol(requireProtocol(request.protocol()).name());
        }
        if (device.getProtocol() == null || device.getProtocol().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Protocol is required and must be one of "
                            + java.util.Arrays.toString(DeviceProtocol.values()) + ".");
        }
        if (request.assetNumber() != null) {
            device.setAssetNumber(blankToNull(request.assetNumber()));
        }
        if (request.assetId() != null) {
            device.setAssetId(request.assetId());
        }
        if (request.manufacturer() != null) {
            device.setManufacturer(blankToNull(request.manufacturer()));
        }
        if (request.model() != null) {
            device.setModel(blankToNull(request.model()));
        }
        if (request.serialNumber() != null) {
            device.setSerialNumber(blankToNull(request.serialNumber()));
        }
        if (request.firmwareVersion() != null) {
            device.setFirmwareVersion(blankToNull(request.firmwareVersion()));
        }
        if (request.installationDate() != null) {
            device.setInstallationDate(requireNotFuture(request.installationDate()));
        }
        if (request.coordinates() != null) {
            device.setLocation(toPoint(request.coordinates()));
        }

        // Communication last: it depends on the type, which may itself be changing in this request.
        String transport = request.communicationType() != null
                ? request.communicationType() : device.getTransport();
        CommunicationProfile profile = CommunicationProfile.from(transport);
        if (profile == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Communication Type is required and must be one of "
                            + java.util.Arrays.toString(CommunicationProfile.values()) + ".");
        }
        boolean typeChanged = !profile.name().equals(device.getTransport());
        device.setTransport(profile.name());

        /*
         * Re-validate the block whenever the type changes, even if the request did not send one:
         * switching a device from LoRaWAN to NB-IoT while keeping its DevEUI would leave it
         * addressed by an identifier its new network cannot route.
         */
        if (request.communication() != null || typeChanged) {
            applyCommunication(device, profile,
                    request.communication() == null ? Map.of() : request.communication(),
                    organizationId);
        }

        if (device.getName() == null || device.getName().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Device Name is required.");
        }
        if (device.getDeviceType() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Device Type is required.");
        }
    }

    /**
     * Validates and stores the communication-specific fields for one profile.
     *
     * <p>Unknown keys are rejected rather than ignored: a form that posts {@code devEui} to an
     * NB-IoT device is a bug in the caller, and silently dropping it produces a device that looks
     * provisioned and can never be reached.
     */
    private void applyCommunication(Device device, CommunicationProfile profile,
                                    Map<String, String> submitted, UUID organizationId) {
        Set<String> known = profile.fields().stream()
                .map(CommunicationProfile.Field::key)
                .collect(java.util.stream.Collectors.toSet());
        for (String key : submitted.keySet()) {
            if (!known.contains(key)) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "'" + key + "' is not a field of " + profile.name() + " devices.");
            }
        }

        Map<String, Object> stored = new LinkedHashMap<>();
        Map<String, String> plain = new LinkedHashMap<>();
        for (CommunicationProfile.Field field : profile.fields()) {
            String value = blankToNull(submitted.get(field.key()));

            if (value == null) {
                if (field.required()) {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                            field.label() + " is required for " + profile.name() + " devices.");
                }
                /*
                 * A secret that was not sent is kept, not cleared. The API never returns AppKey, so
                 * an edit form cannot echo it back — treating its absence as "clear it" would wipe
                 * the join key every time an operator corrected the device's name.
                 */
                if (field.secret()) {
                    Object existing = device.getProvisioning()
                            .get(DeviceDto.SECRET_PREFIX + field.key());
                    if (existing != null) {
                        stored.put(DeviceDto.SECRET_PREFIX + field.key(), existing);
                        // The lookup digest is carried forward with the secret it describes. Keeping
                        // the ciphertext and dropping the hash would leave the device holding a
                        // working credential the receiver can no longer find it by — authentication
                        // failing on an edit that changed the device's name.
                        if (field.lookupHashed()) {
                            Object hash = device.getProvisioning().get(field.hashField());
                            if (hash != null) {
                                stored.put(field.hashField(), hash);
                            }
                        }
                    }
                }
                continue;
            }

            if (!field.format().matches(value)) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        field.label() + " must be " + field.format().expectation() + ".");
            }

            if (field.secret()) {
                stored.put(DeviceDto.SECRET_PREFIX + field.key(), cryptoService.encrypt(value));
                // A credential the receiver resolves a device by is stored twice: encrypted so it
                // stays confidential, and digested so it can be looked up. Without this the
                // DEVICE_TOKEN scheme matches nothing — it searches a field registration never
                // wrote, so the strongest identity-bearing authenticator the platform has was
                // unreachable for every registered device. Full-length SHA-256, not the replay
                // cache's truncated digest: see Sha256.
                if (field.lookupHashed()) {
                    stored.put(field.hashField(), Sha256.hex(value));
                }
            } else {
                stored.put(field.key(), value);
                plain.put(field.key(), value);
            }
        }

        String networkAddress = profile.networkAddressFrom(plain);
        if (networkAddress != null && !networkAddress.equalsIgnoreCase(device.getNetworkAddress())
                && deviceRepository.existsByOrganizationIdAndNetworkAddress(organizationId, networkAddress)) {
            throw new BusinessException(ErrorCode.RESOURCE_CONFLICT,
                    "Another device in this organisation is already registered at " + networkAddress + ".");
        }
        device.setNetworkAddress(networkAddress);
        device.setProvisioning(stored);
    }

    // --- Validation helpers ----------------------------------------------------------------

    private DeviceSource requireSource(String value) {
        DeviceSource source = DeviceSource.from(value);
        if (source == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Device Source must be one of "
                            + java.util.Arrays.toString(DeviceSource.values()) + ".");
        }
        return source;
    }

    private DeviceProtocol requireProtocol(String value) {
        DeviceProtocol protocol = DeviceProtocol.from(value);
        if (protocol == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Protocol must be one of "
                            + java.util.Arrays.toString(DeviceProtocol.values()) + ".");
        }
        return protocol;
    }

    /**
     * Validates the list filter rather than passing an unrecognised value through.
     *
     * <p>A typo'd filter that reaches the query matches nothing and renders as an empty registry —
     * indistinguishable from a tenant that owns no devices. Rejecting it says which of the two
     * happened.
     */
    private String normaliseSourceFilter(String source) {
        String trimmed = blankToNull(source);
        return trimmed == null ? null : requireSource(trimmed).name();
    }

    private String normaliseProtocolFilter(String protocol) {
        String trimmed = blankToNull(protocol);
        return trimmed == null ? null : requireProtocol(trimmed).name();
    }

    private Point toPoint(double[] coordinates) {
        if (coordinates.length != 2) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "GIS Location must be [longitude, latitude].");
        }
        double lon = coordinates[0];
        double lat = coordinates[1];
        if (lon < -180 || lon > 180 || lat < -90 || lat > 90) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "GIS Location is out of range: longitude must be -180..180 and latitude -90..90.");
        }
        return GEOMETRY.createPoint(new Coordinate(lon, lat));
    }

    private LocalDate requireNotFuture(LocalDate date) {
        if (date.isAfter(LocalDate.now())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Installation Date cannot be in the future.");
        }
        return date;
    }

    private String required(String value, String label) {
        String trimmed = blankToNull(value);
        if (trimmed == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, label + " is required.");
        }
        return trimmed;
    }

    private String requireOneOf(String value, Set<String> allowed, String label) {
        String trimmed = required(value, label).toUpperCase();
        if (!allowed.contains(trimmed)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    label + " must be one of " + allowed + ".");
        }
        return trimmed;
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private Device requireInTenant(UUID deviceId, UUID organizationId) {
        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new ResourceNotFoundException("Device", deviceId));
        if (!device.getOrganizationId().equals(organizationId)) {
            throw new ResourceNotFoundException("Device", deviceId);
        }
        return device;
    }
}
