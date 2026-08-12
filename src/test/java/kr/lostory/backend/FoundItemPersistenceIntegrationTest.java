package kr.lostory.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import kr.lostory.backend.founditem.domain.FoundItem;
import kr.lostory.backend.founditem.domain.FoundItemRepository;
import kr.lostory.backend.founditem.domain.FoundItemStatus;
import kr.lostory.backend.founditem.domain.StorageMethod;
import kr.lostory.backend.user.domain.User;
import kr.lostory.backend.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
@SpringBootTest
@Transactional
class FoundItemPersistenceIntegrationTest {

    private static final String HASH = "$2a$10$7EqJtq98hPqEX7fNZaFWoO5n33S5U9P4XQxG1VVDzI7kVxwZKXgOe";

    @Autowired
    private FoundItemRepository foundItemRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void foundItemPersistsAndReloadsAfterV3() {
        User finder = userRepository.saveAndFlush(new User(uniqueEmail(), HASH));

        FoundItem saved = foundItemRepository.saveAndFlush(new FoundItem(
                finder.getId(),
                "Black card wallet",
                "WALLET_CARD",
                "Black leather card wallet with several cards",
                Instant.parse("2026-08-04T09:30:00Z"),
                "Soongsil University student center 2F",
                StorageMethod.HANDED_TO_CENTER,
                null,
                "Student center information desk"
        ));

        FoundItem reloaded = foundItemRepository.findById(saved.getId()).orElseThrow();

        Boolean foundItemMigrationApplied = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM flyway_schema_history WHERE success AND version = '3')",
                Boolean.class
        );

        assertThat(foundItemMigrationApplied).isTrue();
        assertThat(reloaded.getFinderId()).isEqualTo(finder.getId());
        assertThat(reloaded.getName()).isEqualTo("Black card wallet");
        assertThat(reloaded.getCategory()).isEqualTo("WALLET_CARD");
        assertThat(reloaded.getDescription()).isEqualTo("Black leather card wallet with several cards");
        assertThat(reloaded.getFoundAt()).isEqualTo(Instant.parse("2026-08-04T09:30:00Z"));
        assertThat(reloaded.getFoundLocationText()).isEqualTo("Soongsil University student center 2F");
        assertThat(reloaded.getStorageMethod()).isEqualTo(StorageMethod.HANDED_TO_CENTER);
        assertThat(reloaded.getStorageDescription()).isNull();
        assertThat(reloaded.getHandoverPlaceName()).isEqualTo("Student center information desk");
        assertThat(reloaded.getStatus()).isEqualTo(FoundItemStatus.ACTIVE);
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.getUpdatedAt()).isNotNull();
        assertThat(reloaded.getClosedAt()).isNull();
    }

    private static String uniqueEmail() {
        return "found-item-user-" + UUID.randomUUID() + "@example.test";
    }
}