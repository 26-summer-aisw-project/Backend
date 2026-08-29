package kr.lostory.backend;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import kr.lostory.backend.partner.application.PartnerActivationDeliveryCipher;
import kr.lostory.backend.partner.domain.PartnerActivationDelivery;
import kr.lostory.backend.partner.domain.PartnerActivationDeliveryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@Transactional
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
@SpringBootTest
class PartnerActivationDeliveryCipherPersistenceTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired PartnerActivationDeliveryRepository deliveries;
    @Autowired PartnerActivationDeliveryCipher cipher;

    @Test
    void decryptsDeliveryAfterPostgresPersistenceRoundTrip() {
        // Given
        Instant createdAt = Instant.parse("2026-08-28T00:00:00Z");
        Instant expiresAt = Instant.parse("2026-08-29T00:00:00.123456789Z");
        Long partnershipId = partnership(createdAt);
        Long tokenId = jdbc.queryForObject("INSERT INTO center_activation_tokens "
                + "(partnership_id,token_hash,expires_at,issued_at) VALUES (?,?,?,?) RETURNING id",
                Long.class, partnershipId, new byte[32], Timestamp.from(expiresAt), Timestamp.from(createdAt));
        PartnerActivationDelivery encrypted = cipher.encrypt("https://example.test/activate", partnershipId,
                tokenId, expiresAt, createdAt);

        // When
        Long rowId = deliveries.saveAndFlush(encrypted).getId();
        entityManager.clear();
        PartnerActivationDelivery reloaded = deliveries.findById(rowId).orElseThrow();

        // Then
        assertThat(reloaded.getId()).isEqualTo(rowId);
        assertThat(reloaded.getPartnershipId()).isEqualTo(partnershipId);
        assertThat(reloaded.getActivationTokenId()).isEqualTo(tokenId);
        assertThat(reloaded.getKeyVersion()).isEqualTo(encrypted.getKeyVersion());
        assertThat(reloaded.getExpiresAt().getEpochSecond()).isEqualTo(expiresAt.getEpochSecond());
        System.out.printf("D7_AEAD_PERSISTENCE input_epoch=%d input_nanos=%d reloaded_epoch=%d "
                        + "reloaded_nanos=%d key_version=%s row_id=%d token_id=%d%n",
                expiresAt.getEpochSecond(), expiresAt.getNano(), reloaded.getExpiresAt().getEpochSecond(),
                reloaded.getExpiresAt().getNano(), reloaded.getKeyVersion(), rowId, tokenId);
        assertThat(cipher.decrypt(reloaded)).isEqualTo("https://example.test/activate");
    }

    private Long partnership(Instant createdAt) {
        Long centerId = jdbc.queryForObject("INSERT INTO lost_centers "
                + "(source_key,name,address,location,contact_phone,operating_hours,verification_status,is_active,"
                + "is_csv_managed,created_at,updated_at) VALUES (?,'D7 center','address',"
                + "ST_SetSRID(ST_MakePoint(127,37),4326)::geography,'02-0000-0000','always','admin_verified',"
                + "true,false,?,?) RETURNING id", Long.class, "d7:" + UUID.randomUUID(),
                Timestamp.from(createdAt), Timestamp.from(createdAt));
        return jdbc.queryForObject("INSERT INTO center_partnerships "
                + "(center_id,manager_email,manager_display_name,status,created_at,updated_at) "
                + "VALUES (?,'d7-persistence@example.test','D7 Manager','PENDING_ACTIVATION',?,?) RETURNING id",
                Long.class, centerId, Timestamp.from(createdAt), Timestamp.from(createdAt));
    }
}
