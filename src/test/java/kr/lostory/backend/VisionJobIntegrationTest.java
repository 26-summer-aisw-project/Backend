package kr.lostory.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.persistence.EntityManager;
import kr.lostory.backend.common.storage.ObjectStorage;
import kr.lostory.backend.common.storage.ObjectStorageException;
import kr.lostory.backend.founditem.application.VisionFeatureExtractor;
import kr.lostory.backend.founditem.application.VisionJobWorker;
import kr.lostory.backend.founditem.application.VisionProvider;
import kr.lostory.backend.founditem.application.VisionProviderException;
import kr.lostory.backend.founditem.domain.FoundItem;
import kr.lostory.backend.founditem.domain.FoundItemImage;
import kr.lostory.backend.founditem.domain.FoundItemImageRepository;
import kr.lostory.backend.founditem.domain.FoundItemRepository;
import kr.lostory.backend.founditem.domain.FoundItemVisionJob;
import kr.lostory.backend.founditem.domain.FoundItemVisionJobRepository;
import kr.lostory.backend.founditem.domain.ItemFeature;
import kr.lostory.backend.founditem.domain.ItemFeatureKind;
import kr.lostory.backend.founditem.domain.ItemFeatureRepository;
import kr.lostory.backend.founditem.domain.ItemFeatureSource;
import kr.lostory.backend.founditem.domain.ItemFeatureVisibility;
import kr.lostory.backend.founditem.domain.StorageMethod;
import kr.lostory.backend.user.domain.User;
import kr.lostory.backend.user.domain.UserRole;
import kr.lostory.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@Import({PostgresTestContainerConfig.class, VisionJobIntegrationTest.VisionTestConfig.class})
@SpringBootTest(properties = "vision.timeout=PT4S")
class VisionJobIntegrationTest {

    private static final byte[] IMAGE_BYTES = new byte[]{1, 2, 3, 4};
    private static final String HASH = "$2a$10$7EqJtq98hPqEX7fNZaFWoO5n33S5U9P4XQxG1VVDzI7kVxwZKXgOe";

    @Autowired VisionJobWorker worker;
    @Autowired RecordingVisionProvider provider;
    @Autowired VisionObjectStorage storage;
    @Autowired FoundItemRepository itemRepository;
    @Autowired FoundItemImageRepository imageRepository;
    @Autowired FoundItemVisionJobRepository jobRepository;
    @Autowired ItemFeatureRepository featureRepository;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;

    @BeforeEach
    void reset() {
        entityManager.clear();
        jdbc.update("DELETE FROM match_candidates");
        jdbc.update("DELETE FROM report_waypoints");
        jdbc.update("DELETE FROM lost_reports");
        jdbc.update("DELETE FROM found_item_vision_jobs");
        provider.reset();
        storage.reset();
    }

    @Test
    void readyResultMarksReportsStaleOnlyWhenSelectableAiFeaturesChange() {
        JobFixture changed = job();
        long changedReport = report(false);
        provider.enqueue(walletResult());

        assertThat(worker.processNext()).isTrue();

        assertThat(stale(changedReport)).isTrue();

        JobFixture finderOwned = job();
        featureRepository.saveAllAndFlush(List.of(
                new ItemFeature(finderOwned.itemId(), ItemFeatureKind.COLOR, "BLUE", (short) 1,
                        ItemFeatureSource.FINDER, ItemFeatureVisibility.CANDIDATE_VIEW, null),
                new ItemFeature(finderOwned.itemId(), ItemFeatureKind.PUBLIC_DESCRIPTION, "finder text", (short) 1,
                        ItemFeatureSource.FINDER, ItemFeatureVisibility.CANDIDATE_VIEW, null)));
        long finderReport = report(false);
        provider.enqueue(walletResult());

        assertThat(worker.processNext()).isTrue();

        assertThat(stale(finderReport)).isFalse();
    }

    @Test
    void successRequestsExactlyTwoFeaturesAndStoresNormalizedMatchOnlySuggestion() {
        JobFixture fixture = job();
        featureRepository.save(new ItemFeature(
                fixture.itemId(), ItemFeatureKind.LABEL, "finder-value", (short) 1,
                ItemFeatureSource.FINDER, ItemFeatureVisibility.CANDIDATE_VIEW, null));
        provider.enqueue(new VisionProvider.VisionResult(
                List.of(
                        new VisionProvider.Label("  Ｗallet\t ", 0.811),
                        new VisionProvider.Label("WALLET", 0.9514),
                        new VisionProvider.Label(" zipper ", 0.8),
                        new VisionProvider.Label("accessory", 0.8),
                        new VisionProvider.Label("leather", 0.7),
                        new VisionProvider.Label("portable", 0.6),
                        new VisionProvider.Label("sixth", 0.5),
                        new VisionProvider.Label("　 ", 1.0)),
                List.of(
                        new VisionProvider.Color(200, 200, 200, 0.2, 0.9),
                        new VisionProvider.Color(10, 10, 10, 0.8, 0.7))));

        boolean processed = worker.processNext();

        assertThat(processed).isTrue();
        assertThat(provider.calls()).isOne();
        assertThat(provider.requests()).singleElement().satisfies(request -> {
            assertThat(request.features()).containsExactly(
                    VisionProvider.FeatureType.LABEL_DETECTION,
                    VisionProvider.FeatureType.IMAGE_PROPERTIES);
            assertThat(request.deadline()).isEqualTo(Duration.ofSeconds(4));
        });
        System.out.println("P0_VISION_TIMEOUT_OBSERVABLE configured=PT4S request-deadline=PT4S");
        List<String> aiFeatures = featureRows(fixture.itemId(), "AI");
        assertThat(aiFeatures).containsExactly(
                "LABEL|wallet|1|0.951|MATCH_ONLY",
                "LABEL|accessory|2|0.800|MATCH_ONLY",
                "LABEL|zipper|3|0.800|MATCH_ONLY",
                "LABEL|leather|4|0.700|MATCH_ONLY",
                "LABEL|portable|5|0.600|MATCH_ONLY",
                "COLOR|BLACK|1|null|MATCH_ONLY");
        assertThat(aiFeatures).allSatisfy(feature ->
                assertThat(feature.startsWith("LABEL|") || feature.startsWith("COLOR|")).isTrue());
        assertThat(suggestionBasis(aiFeatures)).isEqualTo("BLACK wallet");
        assertThat(featureRows(fixture.itemId(), "FINDER"))
                .containsExactly("LABEL|finder-value|1|null|CANDIDATE_VIEW");
        assertThat(jobState(fixture.jobId())).containsExactly("READY", "1", "null");
        assertThat(itemVisionStatus(fixture.itemId())).isEqualTo("READY");
    }

    @Test
    void colorMappingHonorsDominanceThresholdAndDeclarationOrderTie() {
        VisionFeatureExtractor extractor = new VisionFeatureExtractor();
        VisionProvider.VisionResult dominance = new VisionProvider.VisionResult(List.of(), List.of(
                new VisionProvider.Color(244, 67, 54, 0.4, 0.9),
                new VisionProvider.Color(255, 152, 0, 0.5, 0.1),
                new VisionProvider.Color(0, 0, 0, 0.5, 0.8)));

        assertThat(extractor.extract(dominance).features())
                .extracting(VisionFeatureExtractor.ExtractedFeature::value)
                .containsExactly("BLACK");
        assertThat(extractor.mapColor(96, 0, 0)).isEqualTo("BLACK");
        assertThat(extractor.mapColor(96.0001, 0, 0)).isEqualTo("OTHER");
        assertThat(extractor.mapColor(31, 54, 72)).isEqualTo("BLACK");
    }

    @Test
    void unexpiredDatabaseLeasePreventsCallAndExpiredLeaseRestartsOnce() {
        JobFixture fixture = job();
        provider.enqueue(walletResult());
        jdbc.update("""
                UPDATE found_item_vision_jobs
                SET status = 'PROCESSING', attempt_count = 1, lease_owner = 'other-worker',
                    lease_until = clock_timestamp() + INTERVAL '60 seconds'
                WHERE id = ?
                """, fixture.jobId());

        assertThat(worker.processNext()).isFalse();
        assertThat(provider.calls()).isZero();

        expireLease(fixture.jobId());
        assertThat(worker.processNext()).isTrue();
        assertThat(provider.calls()).isOne();
        assertThat(jobState(fixture.jobId())).containsExactly("READY", "2", "null");
    }

    @Test
    void concurrentWorkerCannotCallSameGenerationWhileLeaseIsHeld() throws Exception {
        JobFixture fixture = job();
        provider.enqueue(walletResult());
        provider.blockNextCall();
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<Boolean> first = executor.submit(worker::processNext);
            provider.awaitCall();

            assertThat(worker.processNext()).isFalse();
            assertThat(provider.calls()).isOne();

            provider.releaseCall();
            assertThat(first.get()).isTrue();
        }
        assertThat(jobState(fixture.jobId())).containsExactly("READY", "1", "null");
    }

    @Test
    void staleGenerationIsSupersededWithoutOverwritingNewImage() throws Exception {
        JobFixture old = job();
        provider.enqueue(walletResult());
        provider.blockNextCall();
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<Boolean> processing = executor.submit(worker::processNext);
            provider.awaitCall();

            FoundItem item = itemRepository.findById(old.itemId()).orElseThrow();
            FoundItemImage oldImage = imageRepository.findById(old.imageId()).orElseThrow();
            oldImage.replace();
            imageRepository.saveAndFlush(oldImage);
            int generation = item.beginImageAnalysis();
            itemRepository.saveAndFlush(item);
            FoundItemImage replacement = imageRepository.saveAndFlush(new FoundItemImage(
                    item.getId(), "new.png", "found-items/new-" + UUID.randomUUID(), "image/png",
                    IMAGE_BYTES.length, generation, UUID.randomUUID()));
            storage.put(replacement.getObjectKey(), IMAGE_BYTES, "image/png", replacement.getUploadOperationId());

            provider.releaseCall();
            assertThat(processing.get()).isTrue();
        }

        assertThat(jobState(old.jobId())).containsExactly("SUPERSEDED", "1", "null");
        assertThat(featureRows(old.itemId(), "AI")).isEmpty();
        assertThat(itemVisionStatus(old.itemId())).isEqualTo("PENDING");
    }

    @Test
    void knownFailuresRetryAtTenAndSixtySecondsThenFailAtThreeCalls() {
        JobFixture fixture = job();
        provider.failKnown(3);

        worker.processNext();
        assertRetry(fixture.jobId(), 1, 8, 12);
        makeDue(fixture.jobId());
        worker.processNext();
        assertRetry(fixture.jobId(), 2, 58, 62);
        makeDue(fixture.jobId());
        worker.processNext();

        assertThat(provider.calls()).isEqualTo(3);
        assertThat(jobState(fixture.jobId())).containsExactly("FAILED", "3", "VISION_PROVIDER_FAILURE");
        assertThat(itemVisionStatus(fixture.itemId())).isEqualTo("FAILED");
        assertThat(featureRows(fixture.itemId(), "AI")).isEmpty();
    }

    @Test
    void crashBeforeCallLeavesChargedLeaseAndRestartsWithoutDuplicateConcurrentCall() {
        JobFixture fixture = job();
        provider.enqueue(walletResult());
        storage.failNextGet();

        assertThatThrownBy(worker::processNext).isInstanceOf(ObjectStorageException.class);
        assertThat(provider.calls()).isZero();
        assertThat(jobState(fixture.jobId())).containsExactly("PROCESSING", "1", "null");

        expireLease(fixture.jobId());
        worker.processNext();
        assertThat(provider.calls()).isOne();
        assertThat(jobState(fixture.jobId())).containsExactly("READY", "2", "null");
    }

    @Test
    void crashAfterProviderReturnRetriesBelowCapAndPersistsOnlyReceivedGeneration() {
        JobFixture fixture = job();
        provider.enqueue(walletResult());
        provider.enqueue(walletResult());
        installFeatureInsertFailure();
        try {
            worker.processNext();
        } finally {
            removeFeatureInsertFailure();
        }
        assertThat(jobState(fixture.jobId())).containsExactly("PENDING", "1", "VISION_AMBIGUOUS_ATTEMPT");
        assertThat(featureRows(fixture.itemId(), "AI")).isEmpty();

        makeDue(fixture.jobId());
        worker.processNext();

        assertThat(provider.calls()).isEqualTo(2);
        assertThat(jobState(fixture.jobId())).containsExactly("READY", "2", "null");
        assertThat(featureRows(fixture.itemId(), "AI"))
                .extracting(row -> row.split("\\|")[1])
                .containsExactly("wallet", "BLACK");
    }

    @Test
    void thirdAmbiguousTimeoutFailsWithExactCodeAndZeroFeatures() {
        JobFixture fixture = job();
        provider.failAmbiguous(3);

        worker.processNext();
        makeDue(fixture.jobId());
        worker.processNext();
        makeDue(fixture.jobId());
        worker.processNext();

        assertThat(provider.calls()).isEqualTo(3);
        assertThat(jobState(fixture.jobId()))
                .containsExactly("FAILED", "3", "VISION_AMBIGUOUS_FINAL_ATTEMPT");
        assertThat(featureRows(fixture.itemId(), "AI")).isEmpty();
        assertThat(itemVisionStatus(fixture.itemId())).isEqualTo("FAILED");
    }

    @Test
    void malformedNumericResponseRetriesBoundedlyAndNeverMakesFourthCall() {
        JobFixture fixture = job();
        provider.enqueue(malformedLabelResult());
        provider.enqueue(malformedColorResult());
        provider.enqueue(malformedLabelResult());

        worker.processNext();
        makeDue(fixture.jobId());
        worker.processNext();
        makeDue(fixture.jobId());
        worker.processNext();

        assertThat(worker.processNext()).isFalse();
        assertThat(provider.calls()).isEqualTo(3);
        assertThat(jobState(fixture.jobId()))
                .containsExactly("FAILED", "3", "VISION_MALFORMED_RESPONSE");
        assertThat(featureRows(fixture.itemId(), "AI")).isEmpty();
        assertThat(itemVisionStatus(fixture.itemId())).isEqualTo("FAILED");
    }

    @Test
    void expiredThirdAttemptFailsAmbiguouslyWithoutCallingProviderAgain() {
        JobFixture fixture = job();
        provider.enqueue(walletResult());
        jdbc.update("""
                UPDATE found_item_vision_jobs
                SET status = 'PROCESSING', attempt_count = 3, lease_owner = 'crashed-third-worker',
                    lease_until = clock_timestamp() - INTERVAL '1 second',
                    last_error = 'VISION_AMBIGUOUS_ATTEMPT'
                WHERE id = ?
                """, fixture.jobId());

        assertThat(worker.processNext()).isTrue();

        assertThat(provider.calls()).isZero();
        assertThat(jobState(fixture.jobId()))
                .containsExactly("FAILED", "3", "VISION_AMBIGUOUS_FINAL_ATTEMPT");
        assertThat(featureRows(fixture.itemId(), "AI")).isEmpty();
        assertThat(itemVisionStatus(fixture.itemId())).isEqualTo("FAILED");
    }

    @Test
    void successfulEmptyExtractionIsReadyWithZeroFeatures() {
        JobFixture fixture = job();
        provider.enqueue(new VisionProvider.VisionResult(List.of(), List.of()));

        worker.processNext();

        assertThat(jobState(fixture.jobId())).containsExactly("READY", "1", "null");
        assertThat(featureRows(fixture.itemId(), "AI")).isEmpty();
    }

    private JobFixture job() {
        User user = userRepository.saveAndFlush(new User(
                "vision-" + UUID.randomUUID() + "@example.test", HASH, "Vision", UserRole.USER));
        FoundItem item = itemRepository.saveAndFlush(new FoundItem(
                user.getId(), "Wallet", "WALLET_CARD", "Black wallet", Instant.now(),
                new BigDecimal("37.5"), new BigDecimal("127.0"), "Seoul", "Desk",
                StorageMethod.LEFT_IN_PLACE, null, null));
        int generation = item.beginImageAnalysis();
        itemRepository.saveAndFlush(item);
        String key = "found-items/" + UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        FoundItemImage image = imageRepository.saveAndFlush(new FoundItemImage(
                item.getId(), "wallet.png", key, "image/png", IMAGE_BYTES.length, generation, operationId));
        FoundItemVisionJob job = jobRepository.saveAndFlush(
                new FoundItemVisionJob(item.getId(), image.getId(), generation));
        storage.put(key, IMAGE_BYTES, "image/png", operationId);
        makeDue(job.getId());
        return new JobFixture(item.getId(), image.getId(), job.getId());
    }

    private long report(boolean stale) {
        User reporter = userRepository.saveAndFlush(new User(
                "report-" + UUID.randomUUID() + "@example.test", HASH, "Reporter", UserRole.USER));
        return jdbc.queryForObject("""
                INSERT INTO lost_reports
                    (reporter_id, category, lost_at_from, lost_at_to, description, search_radius,
                     effective_search_radius_meters, radius_policy_version, center_guidance,
                     candidates_stale, matching_policy_version, status, expired_at, created_at, updated_at)
                VALUES (?, 'WALLET', clock_timestamp() - INTERVAL '1 day', clock_timestamp(), 'wallet',
                        1000, 1000, 'p0-radius-v1', '[]', ?, 'matching-v1', 'OPEN',
                        clock_timestamp() + INTERVAL '14 days', clock_timestamp(), clock_timestamp())
                RETURNING id
                """, Long.class, reporter.getId(), stale);
    }

    private boolean stale(long reportId) {
        return jdbc.queryForObject("SELECT candidates_stale FROM lost_reports WHERE id = ?",
                Boolean.class, reportId);
    }

    private VisionProvider.VisionResult walletResult() {
        return new VisionProvider.VisionResult(
                List.of(new VisionProvider.Label("Wallet", 0.91)),
                List.of(new VisionProvider.Color(8, 8, 8, 0.9, 0.8)));
    }

    private VisionProvider.VisionResult malformedLabelResult() {
        return new VisionProvider.VisionResult(
                List.of(new VisionProvider.Label("Wallet", Double.NaN)),
                List.of(new VisionProvider.Color(8, 8, 8, 0.9, 0.8)));
    }

    private VisionProvider.VisionResult malformedColorResult() {
        return new VisionProvider.VisionResult(
                List.of(new VisionProvider.Label("Wallet", 0.9)),
                List.of(new VisionProvider.Color(Double.POSITIVE_INFINITY, 8, 8, 0.9, 0.8)));
    }

    private String suggestionBasis(List<String> featureRows) {
        String label = featureRows.stream().filter(row -> row.startsWith("LABEL|")).findFirst().orElseThrow()
                .split("\\|")[1];
        String color = featureRows.stream().filter(row -> row.startsWith("COLOR|")).findFirst().orElseThrow()
                .split("\\|")[1];
        return color + " " + label;
    }

    private List<String> featureRows(long itemId, String source) {
        return jdbc.query("""
                SELECT kind, feature_value, ordinal, confidence, visibility
                FROM item_features
                WHERE item_id = ? AND source = ?
                ORDER BY CASE kind WHEN 'LABEL' THEN 1 WHEN 'COLOR' THEN 2 ELSE 3 END, ordinal
                """, (resultSet, rowNumber) -> String.join("|",
                resultSet.getString("kind"), resultSet.getString("feature_value"),
                resultSet.getString("ordinal"), resultSet.getString("confidence"),
                resultSet.getString("visibility")), itemId, source);
    }

    private List<String> jobState(long jobId) {
        return jdbc.queryForObject("""
                SELECT ARRAY[status, attempt_count::text, COALESCE(last_error, 'null')]
                FROM found_item_vision_jobs WHERE id = ?
                """, (resultSet, rowNumber) -> List.of((String[]) resultSet.getArray(1).getArray()), jobId);
    }

    private String itemVisionStatus(long itemId) {
        return jdbc.queryForObject("SELECT vision_status FROM found_items WHERE id = ?", String.class, itemId);
    }

    private void makeDue(long jobId) {
        jdbc.update("UPDATE found_item_vision_jobs SET next_attempt_at = clock_timestamp() WHERE id = ?", jobId);
    }

    private void expireLease(long jobId) {
        jdbc.update("""
                UPDATE found_item_vision_jobs
                SET lease_until = clock_timestamp() - INTERVAL '1 second'
                WHERE id = ?
                """, jobId);
    }

    private void assertRetry(long jobId, int attempt, int minimumDelay, int maximumDelay) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT status, attempt_count,
                       EXTRACT(EPOCH FROM (next_attempt_at - clock_timestamp())) AS delay
                FROM found_item_vision_jobs WHERE id = ?
                """, jobId);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("attempt_count")).isEqualTo(attempt);
        assertThat(((BigDecimal) row.get("delay")).doubleValue()).isBetween(
                (double) minimumDelay, (double) maximumDelay);
    }

    private void installFeatureInsertFailure() {
        jdbc.execute("""
                CREATE FUNCTION fail_task4_feature_insert() RETURNS trigger LANGUAGE plpgsql
                AS 'BEGIN RAISE EXCEPTION ''simulated crash after provider return''; END'
                """);
        jdbc.execute("""
                CREATE TRIGGER fail_task4_feature_insert BEFORE INSERT ON item_features
                FOR EACH ROW EXECUTE FUNCTION fail_task4_feature_insert()
                """);
    }

    private void removeFeatureInsertFailure() {
        jdbc.execute("DROP TRIGGER fail_task4_feature_insert ON item_features");
        jdbc.execute("DROP FUNCTION fail_task4_feature_insert()");
    }

    private record JobFixture(long itemId, long imageId, long jobId) {
    }

    @TestConfiguration
    static class VisionTestConfig {
        @Bean
        @Primary
        RecordingVisionProvider recordingVisionProvider() {
            return new RecordingVisionProvider();
        }

        @Bean
        @Primary
        VisionObjectStorage visionObjectStorage() {
            return new VisionObjectStorage();
        }
    }

    static class RecordingVisionProvider implements VisionProvider {
        private final ConcurrentLinkedQueue<Object> outcomes = new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<VisionRequest> requests = new ConcurrentLinkedQueue<>();
        private final AtomicInteger calls = new AtomicInteger();
        private volatile CountDownLatch entered;
        private volatile CountDownLatch release;

        @Override
        public VisionResult analyze(byte[] imageBytes, VisionRequest request) {
            calls.incrementAndGet();
            requests.add(request);
            CountDownLatch enteredLatch = entered;
            CountDownLatch releaseLatch = release;
            if (enteredLatch != null) {
                enteredLatch.countDown();
                await(releaseLatch);
            }
            Object outcome = outcomes.remove();
            if (outcome instanceof VisionProviderException exception) {
                throw exception;
            }
            return (VisionResult) outcome;
        }

        void enqueue(VisionResult result) {
            outcomes.add(result);
        }

        void failKnown(int count) {
            for (int index = 0; index < count; index++) {
                outcomes.add(new VisionProviderException(false));
            }
        }

        void failAmbiguous(int count) {
            for (int index = 0; index < count; index++) {
                outcomes.add(new VisionProviderException(true));
            }
        }

        void blockNextCall() {
            entered = new CountDownLatch(1);
            release = new CountDownLatch(1);
        }

        void awaitCall() {
            await(entered);
        }

        void releaseCall() {
            release.countDown();
        }

        int calls() {
            return calls.get();
        }

        List<VisionRequest> requests() {
            return List.copyOf(requests);
        }

        void reset() {
            outcomes.clear();
            requests.clear();
            calls.set(0);
            entered = null;
            release = null;
        }

        private void await(CountDownLatch latch) {
            try {
                if (!latch.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting for fake provider latch.");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
        }
    }

    static class VisionObjectStorage implements ObjectStorage {
        private final Map<String, StoredObject> objects = new ConcurrentHashMap<>();
        private final AtomicBoolean failGet = new AtomicBoolean();

        @Override
        public void put(String key, byte[] bytes, String contentType, UUID uploadOperationId) {
            objects.put(key, new StoredObject(bytes.clone(), contentType));
        }

        @Override
        public StoredObject get(String key) {
            if (failGet.compareAndSet(true, false)) {
                throw new ObjectStorageException("simulated crash before provider call");
            }
            StoredObject object = objects.get(key);
            if (object == null) {
                throw new ObjectStorageException("missing test object");
            }
            return new StoredObject(object.bytes().clone(), object.contentType());
        }

        @Override
        public PresignedGet presignGet(String key, Instant expiresAt) {
            return new PresignedGet(java.net.URI.create("https://signed.example.test/private-image"), expiresAt);
        }

        @Override
        public Optional<ObjectMetadata> head(String key) {
            StoredObject object = objects.get(key);
            return object == null ? Optional.empty() : Optional.of(new ObjectMetadata(
                    key, object.contentType(), object.bytes().length, UUID.randomUUID(), Instant.now()));
        }

        @Override
        public void delete(String key) {
            objects.remove(key);
        }

        @Override
        public List<ObjectMetadata> list(String prefix) {
            return List.of();
        }

        void failNextGet() {
            failGet.set(true);
        }

        void reset() {
            objects.clear();
            failGet.set(false);
        }
    }
}
