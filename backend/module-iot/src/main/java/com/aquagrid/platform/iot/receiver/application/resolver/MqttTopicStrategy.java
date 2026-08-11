package com.aquagrid.platform.iot.receiver.application.resolver;

import com.aquagrid.platform.iot.domain.model.Device;
import com.aquagrid.platform.iot.infrastructure.persistence.DeviceRepository;
import com.aquagrid.platform.iot.receiver.domain.model.IdentifierType;
import com.aquagrid.platform.iot.receiver.domain.model.ReceptionContext;
import com.aquagrid.platform.iot.receiver.spi.DeviceResolutionStrategy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves a device from the MQTT topic a message was published on.
 *
 * <p>Needed because of how MQTT is actually deployed. The tidy arrangement — one broker connection
 * per meter, client id identifying the device — is rare outside a lab; the common one is a site
 * gateway or SCADA bridge holding a single connection and republishing hundreds of meters onto a
 * topic tree. In that shape the client id names the bridge, and the only thing naming the meter is
 * the topic.
 *
 * <p>Topic trees are a convention rather than a standard, so the extraction takes the pragmatic
 * route: pull out every segment that could plausibly be an identifier and try them, most specific
 * first. That is cheap because each attempt is an indexed lookup and topics have few segments, and
 * it avoids the alternative of a per-customer topic-pattern configuration that would have to be
 * maintained by whoever also maintains the broker.
 *
 * <p>Sorted after the direct identifier strategies: a topic segment is the weakest evidence the
 * receiver acts on, and anything that placed the packet already is more trustworthy.
 */
@Component
@RequiredArgsConstructor
public class MqttTopicStrategy implements DeviceResolutionStrategy {

    public static final int ORDER = 50;

    /**
     * Segment names that introduce a device identifier in the conventions seen in the field —
     * ChirpStack's {@code application/{id}/device/{devEui}/event/up} chief among them.
     */
    private static final Set<String> IDENTIFIER_MARKERS =
            Set.of("device", "devices", "meter", "meters", "node", "nodes", "dev");

    private final DeviceRepository deviceRepository;

    @Override
    public String name() {
        return "MQTT_TOPIC";
    }

    @Override
    public Set<IdentifierType> supportedIdentifiers() {
        return Set.of(IdentifierType.MQTT_TOPIC);
    }

    @Override
    public Optional<Device> resolve(ReceptionContext context) {
        String topic = context.identifier(IdentifierType.MQTT_TOPIC);
        if (topic == null || topic.isBlank()) {
            return Optional.empty();
        }
        for (String candidate : candidates(topic)) {
            Optional<Device> device = deviceRepository.findFirstByNetworkAddressIgnoreCase(candidate);
            if (device.isPresent()) {
                context.note("resolvedFromTopicSegment", candidate);
                return device;
            }
        }
        return Optional.empty();
    }

    /**
     * Segments worth trying, best first: the one following a marker like {@code device/}, then any
     * segment shaped like an identifier.
     *
     * <p>Bounded at four attempts. An unbounded walk would let a long crafted topic turn one packet
     * into dozens of database lookups, which is a cheap amplification for anyone who can publish.
     */
    static List<String> candidates(String topic) {
        String[] segments = topic.split("/");
        List<String> ordered = new java.util.ArrayList<>(4);

        for (int i = 0; i < segments.length - 1; i++) {
            if (IDENTIFIER_MARKERS.contains(segments[i].toLowerCase()) && plausible(segments[i + 1])) {
                ordered.add(segments[i + 1]);
            }
        }
        for (String segment : segments) {
            if (ordered.size() >= 4) {
                break;
            }
            if (plausible(segment) && !ordered.contains(segment)) {
                ordered.add(segment);
            }
        }
        return ordered.size() > 4 ? ordered.subList(0, 4) : ordered;
    }

    /**
     * Filters out the structural noise — {@code event}, {@code up}, wildcards — so those never
     * become lookups. Length-bounded to what {@code network_address} can hold.
     */
    private static boolean plausible(String segment) {
        if (segment == null || segment.length() < 4 || segment.length() > 32) {
            return false;
        }
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            boolean allowed = Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == ':';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
