package com.aquagrid.platform.iot.spi;

import com.aquagrid.platform.iot.api.DeviceMessage;

/**
 * The single ingestion entry point business code may call.
 *
 * <p>Every adapter, after decoding its transport-specific bytes into a {@link DeviceMessage}, hands
 * it here. The implementation resolves the device, persists telemetry, updates last-known device
 * state, raises domain events and feeds alarms/analytics. None of that is the adapter's concern,
 * and none of it knows the transport.
 *
 * <p>This is the boundary that lets the IoT ingestion tier be the first microservice extraction: if
 * it moves behind HTTP, only this interface's implementation changes from in-process to a client.
 */
public interface TelemetryIngestPort {

    /**
     * Ingests one canonical message.
     *
     * @return the ingest outcome — accepted, rejected (unknown device / malformed), or duplicate.
     *         Adapters use this to acknowledge the wire correctly: an NB-IoT QoS-1 message must be
     *         acked only once it is durably ingested.
     */
    IngestResult ingest(DeviceMessage message);
}
