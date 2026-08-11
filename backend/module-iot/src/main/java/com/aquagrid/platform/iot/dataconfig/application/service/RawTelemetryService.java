package com.aquagrid.platform.iot.dataconfig.application.service;

import com.aquagrid.platform.common.error.BusinessException;
import com.aquagrid.platform.common.error.ErrorCode;
import com.aquagrid.platform.iot.dataconfig.domain.model.RawTelemetry;
import com.aquagrid.platform.iot.dataconfig.infrastructure.persistence.RawTelemetryRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Writes and reads the complete original payload of every packet.
 *
 * <p>This is the service the module's central rule reduces to: <b>accept and permanently preserve
 * everything the device sent</b>. Configured parameters get units, ranges, dashboards and alarms;
 * unconfigured ones get this table, and the day someone configures one, the history is already
 * there.
 *
 * <h2>Written like the packet log, for the same reasons</h2>
 *
 * <p>{@code REQUIRES_NEW}, so the row is independent of the outcome it describes — a rejected packet
 * that rolled back the evidence of its own rejection is one of the two failures the receiver module
 * exists to prevent. And failures are swallowed: preserving the payload matters, and it does not
 * matter more than the reading. If this insert fails the telemetry has already been committed by its
 * own transaction, and throwing here would report a successful ingestion as a failure and invite the
 * gateway to send it again.
 *
 * <h2>Never modified</h2>
 *
 * <p>No normalisation, no canonicalising of key names, no dropping of fields the platform had no use
 * for. The row is the answer to "what did the device actually send", and a row that had been tidied
 * could not answer it. Payloads that are not JSON are still stored losslessly, wrapped so the column
 * can stay JSONB and therefore stay queryable — see {@link #toDocument}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RawTelemetryService {

    private final RawTelemetryRepository repository;
    private final ObjectMapper objectMapper;

    /**
     * Stores one packet's payload, whatever became of the packet.
     *
     * @param command everything known about the reception. Most fields are nullable by
     *                construction — an unattributed packet has no device, and therefore no tenant
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void store(StoreCommand command) {
        try {
            // Idempotent on the message id. A retry inside the reception path, or a transport that
            // re-delivers before the first attempt's transaction is visible, must not produce two
            // records of one packet — the discovery counters read from this table and would
            // double-count.
            if (repository.existsById(command.messageId())) {
                return;
            }
            RawTelemetry row = new RawTelemetry();
            row.setId(command.messageId());
            row.setMessageId(command.messageId());
            row.setOrganizationId(command.organizationId());
            row.setDeviceId(command.deviceId());
            row.setDeviceCode(command.deviceCode());
            row.setAssetId(command.assetId());
            row.setAssetNumber(command.assetNumber());
            row.setDeviceTimestamp(command.deviceTimestamp());
            row.setReceivedAt(command.receivedAt() == null ? Instant.now() : command.receivedAt());
            row.setCommunicationType(command.communicationType());
            row.setConnectionMode(command.connectionMode() == null ? "UNKNOWN" : command.connectionMode());
            row.setCorrelationId(command.correlationId());
            row.setSourceIp(command.sourceIp());
            row.setPayloadSize(command.payload() == null ? 0 : command.payload().length);
            row.setProcessingStatus(command.processingStatus());
            row.setProcessingError(truncate(command.processingError(), 500));

            Document document = toDocument(command.payload());
            row.setPayload(document.content());
            row.setPayloadEncoding(document.encoding());

            repository.save(row);
        } catch (RuntimeException e) {
            log.error("Failed to store the raw payload for packet {} — the reading itself is "
                    + "unaffected, but this packet's unconfigured parameters are now unrecoverable",
                    command.messageId(), e);
        }
    }

    /**
     * Turns raw bytes into one JSONB document, losslessly.
     *
     * <p>Three cases, and the wrapping in the second two is what lets the column be JSONB rather
     * than BYTEA. That choice is the whole reason this table can answer "which payloads carry a
     * {@code powerFactor}" — a question the packet log, which stores the same bytes as BYTEA,
     * cannot be asked.
     *
     * <ul>
     *   <li>A JSON <b>object</b> is stored as itself, key for key.</li>
     *   <li>A JSON <b>array</b>, or any other valid-but-not-object JSON, is wrapped under a
     *       {@code data} key. Postgres's {@code jsonb} would hold an array quite happily, but the
     *       column is mapped as a {@code Map} in Java and every consumer reads it as one; a column
     *       that is sometimes a map and sometimes a list is a column every reader has to branch on.</li>
     *   <li>Anything that is not JSON at all — a LoRaWAN frame, a raw meter binary — is base64ed
     *       under {@code data} with the encoding recorded. The bytes are recoverable exactly; what
     *       is lost is only the ability to query inside them, which was never possible for a binary
     *       frame anyway.</li>
     * </ul>
     */
    private Document toDocument(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return new Document(Map.of(), RawTelemetry.Encodings.JSON);
        }
        try {
            JsonNode node = objectMapper.readTree(payload);
            if (node != null && node.isObject()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> content = objectMapper.convertValue(node, Map.class);
                return new Document(content, RawTelemetry.Encodings.JSON);
            }
            if (node != null && !node.isNull()) {
                Map<String, Object> wrapped = new LinkedHashMap<>();
                wrapped.put("encoding", RawTelemetry.Encodings.JSON);
                wrapped.put("data", objectMapper.convertValue(node, Object.class));
                return new Document(wrapped, RawTelemetry.Encodings.JSON);
            }
        } catch (Exception notJson) {
            // Expected for every binary transport. Not logged: a LoRaWAN fleet would fill the log
            // with a line per uplink saying that a binary frame is not JSON.
        }
        Map<String, Object> wrapped = new LinkedHashMap<>();
        boolean printable = isPrintable(payload);
        wrapped.put("encoding", printable ? RawTelemetry.Encodings.TEXT : RawTelemetry.Encodings.BASE64);
        wrapped.put("data", printable
                ? new String(payload, StandardCharsets.UTF_8)
                : Base64.getEncoder().encodeToString(payload));
        return new Document(wrapped, printable ? RawTelemetry.Encodings.TEXT : RawTelemetry.Encodings.BASE64);
    }

    /**
     * Whether the payload is human-readable text.
     *
     * <p>Worth the check: a CSV or a key=value frame stored as base64 is a payload an operator
     * cannot read on the screen that exists so they can read it. Anything with a control byte in it
     * is binary and goes to base64.
     */
    private static boolean isPrintable(byte[] payload) {
        for (byte b : payload) {
            int value = b & 0xFF;
            if (value < 0x09 || (value > 0x0D && value < 0x20)) {
                return false;
            }
        }
        return true;
    }

    private record Document(Map<String, Object> content, String encoding) {
    }

    // ---- Reads ---------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<RawTelemetry> search(
            UUID organizationId, UUID deviceId, String status, Instant from, Instant to,
            org.springframework.data.domain.Pageable pageable) {
        return repository.search(organizationId, deviceId,
                status == null || status.isBlank() ? null : status.trim().toUpperCase(),
                from, to, pageable);
    }

    @Transactional(readOnly = true)
    public RawTelemetry require(UUID id, UUID organizationId) {
        return repository.findByIdAndOrganizationId(id, organizationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "No stored payload " + id + " in this organisation."));
    }

    @Transactional(readOnly = true)
    public List<RawTelemetry> recentCarrying(UUID organizationId, UUID deviceId, String parameterName,
                                             int limit) {
        return repository.findRecentCarrying(organizationId, deviceId, parameterName,
                Math.clamp(limit, 1, 50));
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /**
     * Everything known about one reception, as the caller hands it over.
     *
     * <p>A record rather than a dozen parameters because {@code TelemetryEventStage} states the case
     * for the same thing: a call with a dozen positional arguments, most of them nullable and four of
     * them {@code UUID}, is a defect waiting for two adjacent ids to be transposed — which the
     * compiler cannot catch and no test would notice until a payload appeared under the wrong device.
     */
    public record StoreCommand(
            UUID messageId,
            UUID organizationId,
            UUID deviceId,
            String deviceCode,
            UUID assetId,
            String assetNumber,
            Instant deviceTimestamp,
            Instant receivedAt,
            String communicationType,
            String connectionMode,
            String correlationId,
            String sourceIp,
            byte[] payload,
            String processingStatus,
            String processingError
    ) {
    }
}
