package com.aquagrid.platform.iot.receiver.infrastructure.persistence;

import com.aquagrid.platform.iot.receiver.domain.model.CommunicationSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CommunicationSessionRepository extends JpaRepository<CommunicationSession, UUID> {

    /** The open session for a connection key, if there is one. The lookup every packet performs. */
    Optional<CommunicationSession> findByTransportAndSessionKeyAndState(String transport,
                                                                       String sessionKey,
                                                                       String state);

    List<CommunicationSession> findByStateAndTransport(String state, String transport);

    long countByState(String state);

    long countByStateAndTransport(String state, String transport);

    @Query("""
            SELECT s FROM CommunicationSession s
            WHERE s.organizationId = :organizationId
              AND (cast(:transport as string) IS NULL OR s.transport = :transport)
              AND (cast(:state as string) IS NULL OR s.state = :state)
            ORDER BY s.lastActivityAt DESC
            """)
    Page<CommunicationSession> findForTenant(@Param("organizationId") UUID organizationId,
                                             @Param("transport") String transport,
                                             @Param("state") String state,
                                             Pageable pageable);

    /**
     * Closes sessions that stopped talking without saying goodbye.
     *
     * <p>Half-open TCP connections are the normal case, not the exception: a battery device that
     * loses power, or a NAT that drops an idle mapping, leaves a socket the server still believes
     * in. Without this sweep the connected-device count only ever rises, and the number an operator
     * is watching during an incident becomes the one number they cannot trust.
     */
    @Modifying
    @Query("""
            UPDATE CommunicationSession s
               SET s.state = 'CLOSED', s.closedAt = :now, s.closeReason = 'Idle timeout'
             WHERE s.state = 'OPEN' AND s.lastActivityAt < :idleBefore
            """)
    int closeIdleSessions(@Param("idleBefore") Instant idleBefore, @Param("now") Instant now);

    /**
     * Closes every open session for this instance at shutdown.
     *
     * <p>Sessions are process state written to a shared table. A replica that dies without closing
     * them leaves rows that no connection backs and no sweep will retire for as long as the idle
     * window, so the count is wrong for exactly as long as it matters.
     */
    @Modifying
    @Query("""
            UPDATE CommunicationSession s
               SET s.state = 'CLOSED', s.closedAt = :now, s.closeReason = :reason
             WHERE s.state = 'OPEN' AND s.transport = :transport
            """)
    int closeAllForTransport(@Param("transport") String transport,
                             @Param("now") Instant now,
                             @Param("reason") String reason);
}
