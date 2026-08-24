package kr.lostory.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import kr.lostory.backend.auth.JwtTokenService;
import kr.lostory.backend.common.exception.LostoryException;
import kr.lostory.backend.common.storage.ObjectStorage;
import kr.lostory.backend.common.storage.ObjectStorageException;
import kr.lostory.backend.config.P0Configuration;
import kr.lostory.backend.config.ObjectStorageProperties;
import kr.lostory.backend.founditem.application.FoundItemImageService;
import kr.lostory.backend.founditem.application.ObjectDeletionWorker;
import kr.lostory.backend.founditem.application.ObjectStorageOrphanSweeper;
import kr.lostory.backend.founditem.domain.FoundItem;
import kr.lostory.backend.founditem.domain.FoundItemImage;
import kr.lostory.backend.founditem.domain.FoundItemImageRepository;
import kr.lostory.backend.founditem.domain.FoundItemRepository;
import kr.lostory.backend.founditem.domain.FoundItemVisionJobRepository;
import kr.lostory.backend.founditem.domain.ObjectDeletionOutboxRepository;
import kr.lostory.backend.founditem.domain.StorageMethod;
import kr.lostory.backend.user.domain.User;
import kr.lostory.backend.user.domain.UserRole;
import kr.lostory.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.EnableScheduling;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@ActiveProfiles("test")
@Import({PostgresTestContainerConfig.class, FoundItemObjectStorageIntegrationTest.StorageTestConfig.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FoundItemObjectStorageIntegrationTest {

    private static final String HASH = "$2a$10$7EqJtq98hPqEX7fNZaFWoO5n33S5U9P4XQxG1VVDzI7kVxwZKXgOe";
    private static final byte[] PNG = new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1};

    @LocalServerPort int port;
    @Autowired JwtTokenService tokenService;
    @Autowired UserRepository userRepository;
    @Autowired FoundItemRepository foundItemRepository;
    @Autowired FoundItemImageRepository imageRepository;
    @Autowired FoundItemVisionJobRepository visionJobRepository;
    @Autowired ObjectDeletionOutboxRepository outboxRepository;
    @Autowired FoundItemImageService imageService;
    @Autowired ObjectStorageOrphanSweeper sweeper;
    @Autowired ObjectDeletionWorker deletionWorker;
    @Autowired ObjectStorageProperties objectStorageProperties;
    @Autowired InMemoryObjectStorage storage;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void resetStorage() {
        storage.clear();
    }

    @Test
    void orphanSweeperHasAutomaticScheduledEntryPoint() throws Exception {
        Instant now = Instant.parse("2026-08-24T00:00:00Z");
        InMemoryObjectStorage scheduledStorage = new InMemoryObjectStorage();
        scheduledStorage.seed("found-items/scheduled-old", PNG, "image/png", UUID.randomUUID(),
                now.minus(Duration.ofHours(2)));
        ObjectStorageOrphanSweeper scheduledSweeper = new ObjectStorageOrphanSweeper(
                scheduledStorage, imageRepository, objectStorageProperties,
                Clock.fixed(now, java.time.ZoneOffset.UTC));
        var method = ObjectStorageOrphanSweeper.class.getMethod("scheduledSweep");

        scheduledSweeper.scheduledSweep();

        assertThat(method.isAnnotationPresent(Scheduled.class)).isTrue();
        assertThat(P0Configuration.class.isAnnotationPresent(EnableScheduling.class)).isTrue();
        assertThat(scheduledStorage.keys()).isEmpty();
    }

    @Test
    void authenticatedOwnerAndAdminStreamUploadedBytesWhileForeignUserSees404() throws Exception {
        User owner = user(UserRole.USER);
        User admin = user(UserRole.ADMIN);
        User foreign = user(UserRole.USER);
        FoundItem item = item(owner);

        imageService.upload(item.getId(), owner.getId(), image("wallet.png", PNG));
        HttpResponse<byte[]> ownerGet = get(owner, item);
        HttpResponse<byte[]> adminGet = get(admin, item);
        HttpResponse<byte[]> foreignGet = get(foreign, item);

        assertThat(storage.keys()).singleElement()
                .satisfies(key -> assertThat(key).matches("found-items/[0-9a-f-]{36}"));
        assertThat(storage.metadata().getFirst().uploadOperationId()).isNotNull();
        assertThat(ownerGet.statusCode()).isEqualTo(200);
        assertThat(ownerGet.body()).isEqualTo(PNG);
        assertThat(ownerGet.headers().firstValue("Content-Type")).contains("image/png");
        assertThat(ownerGet.headers().firstValue("Content-Disposition")).isEmpty();
        assertThat(adminGet.statusCode()).isEqualTo(200);
        assertThat(adminGet.body()).isEqualTo(PNG);
        assertThat(foreignGet.statusCode()).isEqualTo(404);
    }

    @Test
    void publicImageReplacementPostReturnsCommon404WithoutReplacementOutboxWork() throws Exception {
        User owner = user(UserRole.USER);
        FoundItem item = item(owner);
        outboxRepository.deleteAll();

        HttpResponse<byte[]> response = multipart(
                owner, item, List.of(part("image", "wallet.png", "image/png", PNG)));

        JsonNode body = new ObjectMapper().readTree(response.body());
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("code", "message");
        assertThat(body.get("code").asString()).isEqualTo("COMMON-004");
        assertThat(storage.keys()).isEmpty();
        assertThat(imageRepository.countByFoundItemId(item.getId())).isZero();
        assertThat(visionJobRepository.countByFoundItemId(item.getId())).isZero();
        assertThat(outboxRepository.count()).isZero();
    }

    @Test
    void directImageCapabilityRejectsMalformedFilesWithoutStorageWrites() {
        User owner = user(UserRole.USER);
        FoundItem item = item(owner);
        byte[] oversized = new byte[5 * 1024 * 1024 + 1];
        System.arraycopy(PNG, 0, oversized, 0, PNG.length);

        assertThatThrownBy(() -> imageService.upload(item.getId(), owner.getId(),
                new MockMultipartFile("image", "empty.png", "image/png", new byte[0])))
                .isInstanceOf(LostoryException.class);
        assertThatThrownBy(() -> imageService.upload(item.getId(), owner.getId(),
                new MockMultipartFile("image", "spoof.png", "image/png", "not png".getBytes())))
                .isInstanceOf(LostoryException.class);
        assertThatThrownBy(() -> imageService.upload(item.getId(), owner.getId(),
                new MockMultipartFile("image", "wrong.jpg", "image/jpeg", PNG)))
                .isInstanceOf(LostoryException.class);
        assertThatThrownBy(() -> imageService.upload(item.getId(), owner.getId(),
                new MockMultipartFile("image", "big.png", "image/png", oversized)))
                .isInstanceOf(LostoryException.class);

        assertThat(storage.keys()).isEmpty();
        assertThat(imageRepository.countByFoundItemId(item.getId())).isZero();
    }

    @Test
    void jpegPngWebpAndExactFiveMibBoundaryAreAccepted() {
        User owner = user(UserRole.USER);
        byte[] jpeg = new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1};
        byte[] webp = new byte[]{'R', 'I', 'F', 'F', 1, 0, 0, 0, 'W', 'E', 'B', 'P', 1};
        byte[] maximum = new byte[5 * 1024 * 1024];
        System.arraycopy(PNG, 0, maximum, 0, PNG.length);

        imageService.upload(item(owner).getId(), owner.getId(), image("valid.png", PNG));
        imageService.upload(item(owner).getId(), owner.getId(),
                new MockMultipartFile("image", "valid.jpg", "image/jpeg", jpeg));
        imageService.upload(item(owner).getId(), owner.getId(),
                new MockMultipartFile("image", "valid.webp", "image/webp", webp));
        imageService.upload(item(owner).getId(), owner.getId(), image("maximum.png", maximum));

        assertThat(storage.keys()).hasSize(4);
        assertThat(storage.metadata()).extracting(ObjectStorage.ObjectMetadata::contentType)
                .containsExactlyInAnyOrder("image/png", "image/jpeg", "image/webp", "image/png");
        assertThat(storage.metadata()).extracting(ObjectStorage.ObjectMetadata::sizeBytes)
                .contains(5L * 1024 * 1024);
    }

    @Test
    void storageFailureCreatesNoImageOrVisionJobRows() {
        User owner = user(UserRole.USER);
        FoundItem item = item(owner);
        storage.failNextPut();

        assertThatThrownBy(() -> imageService.upload(item.getId(), owner.getId(), image("failed.png", PNG)))
                .isInstanceOf(LostoryException.class);
        assertThat(imageRepository.countByFoundItemId(item.getId())).isZero();
        assertThat(visionJobRepository.countByFoundItemId(item.getId())).isZero();
    }

    @Test
    void databaseFailureAfterPutCompensatesObject() {
        User owner = user(UserRole.USER);
        FoundItem item = item(owner);
        jdbc.execute("CREATE FUNCTION fail_task3_image_insert() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''forced db failure''; END'");
        jdbc.execute("CREATE TRIGGER fail_task3_image_insert BEFORE INSERT ON found_item_images FOR EACH ROW EXECUTE FUNCTION fail_task3_image_insert() ");
        try {
            assertThatThrownBy(() -> imageService.upload(item.getId(), owner.getId(), image("db.png", PNG)))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            jdbc.execute("DROP TRIGGER fail_task3_image_insert ON found_item_images");
            jdbc.execute("DROP FUNCTION fail_task3_image_insert()");
        }

        assertThat(storage.keys()).isEmpty();
        assertThat(imageRepository.countByFoundItemId(item.getId())).isZero();
        assertThat(visionJobRepository.countByFoundItemId(item.getId())).isZero();
    }

    @Test
    void orphanSweepRequiresOperationTagMissingRowAndExpiredGrace() {
        Instant now = Instant.parse("2026-08-24T00:00:00Z");
        User owner = user(UserRole.USER);
        FoundItem item = item(owner);
        imageService.upload(item.getId(), owner.getId(), image("tracked.png", PNG));
        String tracked = storage.keys().getFirst();
        storage.age(tracked, now.minusSeconds(7200));
        storage.seed("found-items/old", PNG, "image/png", UUID.randomUUID(), now.minusSeconds(7200));
        storage.seed("found-items/fresh", PNG, "image/png", UUID.randomUUID(), now.minusSeconds(60));
        storage.seed("found-items/untagged", PNG, "image/png", null, now.minusSeconds(7200));

        int deleted = sweeper.sweep(now);

        assertThat(deleted).isOne();
        assertThat(storage.keys()).containsExactlyInAnyOrder(tracked, "found-items/fresh", "found-items/untagged");
    }

    @Test
    void missingTerminalMediaReturns410MediaNotAvailable() throws Exception {
        User owner = user(UserRole.USER);
        FoundItem item = item(owner);
        imageService.upload(item.getId(), owner.getId(), image("terminal.png", PNG));
        storage.clear();
        jdbc.update("UPDATE found_items SET status = 'RETURNED' WHERE id = ?", item.getId());

        HttpResponse<byte[]> response = get(owner, item);

        assertThat(response.statusCode()).isEqualTo(410);
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).contains("MEDIA_NOT_AVAILABLE");
    }

    @Test
    void deleteBeforeDatabaseAckRetriesAsDoneWhenObjectAlreadyMissing() {
        outboxRepository.deleteAll();
        User owner = user(UserRole.USER);
        FoundItem item = item(owner);
        imageService.upload(item.getId(), owner.getId(), image("one.png", PNG));
        String oldKey = storage.keys().getFirst();
        imageService.upload(item.getId(), owner.getId(), image("two.png", PNG));
        storage.delete(oldKey);

        boolean processed = deletionWorker.processNext();
        boolean repeated = deletionWorker.processNext();

        assertThat(processed).isTrue();
        assertThat(repeated).isFalse();
        assertThat(outboxRepository.countByStatus("DONE")).isOne();
    }

    @Test
    void concurrentReplacementsLeaveOneCurrentGenerationAndQueueLoser() throws Exception {
        outboxRepository.deleteAll();
        User owner = user(UserRole.USER);
        FoundItem item = item(owner);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(() -> uploadAfter(start, item, owner, "first.png"));
            Future<?> second = executor.submit(() -> uploadAfter(start, item, owner, "second.png"));
            start.countDown();
            first.get();
            second.get();
        }

        List<FoundItemImage> images = imageRepository.findAllByFoundItemIdOrderByCreatedAtAsc(item.getId());
        FoundItem currentItem = foundItemRepository.findById(item.getId()).orElseThrow();
        assertThat(images).filteredOn(FoundItemImage::isCurrent).hasSize(1);
        assertThat(currentItem.getAnalysisGeneration()).isEqualTo(2);
        assertThat(visionJobRepository.countByFoundItemId(item.getId())).isEqualTo(2);
        assertThat(outboxRepository.countByStatus("PENDING")).isOne();
    }

    private void uploadAfter(CountDownLatch start, FoundItem item, User owner, String filename) {
        try {
            start.await();
            imageService.upload(item.getId(), owner.getId(), image(filename, PNG));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private User user(UserRole role) {
        return userRepository.saveAndFlush(new User("object-" + UUID.randomUUID() + "@example.test", HASH, "User", role));
    }

    private FoundItem item(User owner) {
        return foundItemRepository.saveAndFlush(new FoundItem(
                owner.getId(), "Wallet", "WALLET_CARD", "Black wallet", Instant.now(),
                new BigDecimal("37.5"), new BigDecimal("127.0"), "Seoul", "Desk",
                StorageMethod.LEFT_IN_PLACE, null, null));
    }

    private MockMultipartFile image(String filename, byte[] bytes) {
        return new MockMultipartFile("image", filename, "image/png", bytes);
    }

    private HttpResponse<byte[]> get(User requester, FoundItem item) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(endpoint(item))
                .header("Authorization", "Bearer " + tokenService.issue(requester).value())
                .GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpResponse<byte[]> multipart(User requester, FoundItem item, List<Part> parts) throws Exception {
        String boundary = "boundary-" + UUID.randomUUID();
        List<byte[]> segments = new ArrayList<>();
        int size = 0;
        for (Part part : parts) {
            byte[] header = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + part.name()
                    + "\"; filename=\"" + part.filename() + "\"\r\nContent-Type: " + part.contentType()
                    + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
            byte[] tail = "\r\n".getBytes(StandardCharsets.UTF_8);
            segments.add(header);
            segments.add(part.bytes());
            segments.add(tail);
            size += header.length + part.bytes().length + tail.length;
        }
        byte[] end = ("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[size + end.length];
        int offset = 0;
        for (byte[] segment : segments) {
            System.arraycopy(segment, 0, body, offset, segment.length);
            offset += segment.length;
        }
        System.arraycopy(end, 0, body, offset, end.length);
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(endpoint(item))
                .header("Authorization", "Bearer " + tokenService.issue(requester).value())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private URI endpoint(FoundItem item) {
        return URI.create("http://localhost:" + port + "/api/v1/found-items/" + item.getId() + "/image");
    }

    private Part part(String name, String filename, String contentType, byte[] bytes) {
        return new Part(name, filename, contentType, bytes);
    }

    private record Part(String name, String filename, String contentType, byte[] bytes) {
    }

    @TestConfiguration
    static class StorageTestConfig {
        @Bean
        @Primary
        InMemoryObjectStorage inMemoryObjectStorage() {
            return new InMemoryObjectStorage();
        }
    }

    static class InMemoryObjectStorage implements ObjectStorage {
        private final Map<String, Entry> objects = new ConcurrentHashMap<>();
        private final AtomicBoolean failPut = new AtomicBoolean();

        @Override
        public void put(String key, byte[] bytes, String contentType, UUID uploadOperationId) {
            if (failPut.compareAndSet(true, false)) {
                throw new ObjectStorageException("forced put failure");
            }
            seed(key, bytes, contentType, uploadOperationId, Instant.now());
        }

        @Override
        public StoredObject get(String key) {
            Entry entry = objects.get(key);
            if (entry == null) {
                throw new ObjectStorageException("missing");
            }
            return new StoredObject(entry.bytes().clone(), entry.metadata().contentType());
        }

        @Override
        public Optional<ObjectMetadata> head(String key) {
            return Optional.ofNullable(objects.get(key)).map(Entry::metadata);
        }

        @Override
        public void delete(String key) {
            objects.remove(key);
        }

        @Override
        public List<ObjectMetadata> list(String prefix) {
            return objects.values().stream().map(Entry::metadata)
                    .filter(metadata -> metadata.key().startsWith(prefix)).toList();
        }

        void seed(String key, byte[] bytes, String contentType, UUID operationId, Instant createdAt) {
            objects.put(key, new Entry(bytes.clone(),
                    new ObjectMetadata(key, contentType, bytes.length, operationId, createdAt)));
        }

        void failNextPut() {
            failPut.set(true);
        }

        void age(String key, Instant createdAt) {
            objects.computeIfPresent(key, (ignored, entry) -> new Entry(entry.bytes(),
                    new ObjectMetadata(key, entry.metadata().contentType(), entry.metadata().sizeBytes(),
                            entry.metadata().uploadOperationId(), createdAt)));
        }

        List<String> keys() {
            return objects.keySet().stream().sorted().toList();
        }

        List<ObjectMetadata> metadata() {
            return objects.values().stream().map(Entry::metadata).toList();
        }

        void clear() {
            objects.clear();
            failPut.set(false);
        }

        private record Entry(byte[] bytes, ObjectMetadata metadata) {
        }
    }
}
