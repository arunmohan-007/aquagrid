package com.aquagrid.platform.iot.receiver.application.security;

import com.aquagrid.platform.common.web.IpSubnet;
import com.aquagrid.platform.iot.receiver.infrastructure.config.ReceiverProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Restricts which source addresses may deliver packets.
 *
 * <p>Reuses {@link IpSubnet} from the kernel rather than parsing CIDRs again — the platform already
 * has one CIDR matcher, tested, and a second one is a second set of edge cases to get wrong.
 *
 * <p><b>An empty list permits everything, and that is deliberate.</b> The alternative reading — an
 * unconfigured list denies everything — is defensible in the abstract and wrong here: it would mean
 * that upgrading a running deployment silently stops every gateway in the field the moment the new
 * property is absent, turning a defence-in-depth control into an outage. The control that must
 * always hold is authentication, which defaults to required. This narrows an already-authenticated
 * path; it does not carry it.
 *
 * <p>The address it judges is the one {@code ClientIpResolver} produced, which ignores forwarding
 * headers unless the peer is a configured trusted proxy. Judging a raw {@code X-Forwarded-For}
 * would make this bypassable with a header.
 */
@Slf4j
@Service
public class IpAllowListService {

    private final List<IpSubnet> allowed;

    public IpAllowListService(ReceiverProperties properties) {
        this.allowed = properties.security().ipAllowList().stream()
                .filter(cidr -> cidr != null && !cidr.isBlank())
                .map(IpSubnet::parse)
                .toList();
        if (allowed.isEmpty()) {
            log.info("Receiver IP allow-list is empty — packets are accepted from any source, "
                    + "subject to authentication");
        } else {
            log.info("Receiver IP allow-list: {}", allowed);
        }
    }

    public boolean isAllowed(String sourceIp) {
        if (allowed.isEmpty()) {
            return true;
        }
        if (sourceIp == null || sourceIp.isBlank()) {
            // A configured allow-list and an unknowable source is a refusal: the operator asked for
            // origin to be checked, and "we could not tell" is not a pass.
            return false;
        }
        return allowed.stream().anyMatch(subnet -> subnet.contains(sourceIp));
    }

    public boolean isEnforced() {
        return !allowed.isEmpty();
    }
}
