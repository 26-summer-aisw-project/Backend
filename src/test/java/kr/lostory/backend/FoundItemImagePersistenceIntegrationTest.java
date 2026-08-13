package kr.lostory.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import kr.lostory.backend.founditem.domain.FoundItem;
import kr.lostory.backend.founditem.domain.FoundItemImage;
import kr.lostory.backend.founditem.domain.FoundItemImageRepository;
import kr.lostory.backend.founditem.domain.FoundItemRepository;
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
class FoundItemImagePersistenceIntegrationTest {

    private static final String HASH = "$2a$10$7EqJtq98hPqEX7fNZaFWoO5n33S5U9P4XQxG1VVDzI7kVxwZKXgOe";

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private FoundItemRepository foundItemRepository;

    @Autowired
    private FoundItemImageRepository foundItemImageRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void foundItemImagePersistsAndReloadsAfterV4() {
        User finder = userRepository.saveAndFlush(new User(uniqueEmail(), HASH));

        FoundItem foundItem = foundItemRepository.saveAndFlush(new FoundItem(
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

        FoundItemImage firstImage = foundItemImageRepository.saveAndFlush(new FoundItemImage(
                foundItem.getId(),
                "wallet-front.png",
                UUID.randomUUID() + ".png",
                "uploads/found-items/" + foundItem.getId() + "/wallet-front.png",
                "image/png",
                12345L
        ));

        FoundItemImage secondImage = foundItemImageRepository.saveAndFlush(new FoundItemImage(
                foundItem.getId(),
                "wallet-back.jpg",
                UUID.randomUUID() + ".jpg",
                "uploads/found-items/" + foundItem.getId() + "/wallet-back.jpg",
                "image/jpeg",
                23456L
        ));

        List<FoundItemImage> images = foundItemImageRepository
                .findAllByFoundItemIdOrderByCreatedAtAsc(foundItem.getId());

        Boolean imageMigrationApplied = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM flyway_schema_history WHERE success AND version = '4')",
                Boolean.class
        );

        assertThat(imageMigrationApplied).isTrue();
        assertThat(foundItemImageRepository.countByFoundItemId(foundItem.getId())).isEqualTo(2);
        assertThat(images).hasSize(2);
        assertThat(images).extracting(FoundItemImage::getId)
                .containsExactly(firstImage.getId(), secondImage.getId());

        FoundItemImage reloaded = images.getFirst();

        assertThat(reloaded.getFoundItemId()).isEqualTo(foundItem.getId());
        assertThat(reloaded.getOriginalFilename()).isEqualTo("wallet-front.png");
        assertThat(reloaded.getStoredFilename()).endsWith(".png");
        assertThat(reloaded.getStoragePath()).isEqualTo("uploads/found-items/" + foundItem.getId() + "/wallet-front.png");
        assertThat(reloaded.getContentType()).isEqualTo("image/png");
        assertThat(reloaded.getSizeBytes()).isEqualTo(12345L);
        assertThat(reloaded.getCreatedAt()).isNotNull();
    }

    private static String uniqueEmail() {
        return "found-item-image-user-" + UUID.randomUUID() + "@example.test";
    }
}