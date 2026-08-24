package kr.lostory.backend.founditem.application;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import kr.lostory.backend.common.storage.ObjectStorage;
import kr.lostory.backend.config.VisionProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class VisionJobWorker {

    static final String AMBIGUOUS_FINAL_ERROR = "VISION_AMBIGUOUS_FINAL_ATTEMPT";
    static final String MALFORMED_RESPONSE_ERROR = "VISION_MALFORMED_RESPONSE";
    private static final int MAX_ATTEMPTS = 3;
    private static final List<VisionProvider.FeatureType> REQUESTED_FEATURES = List.of(
            VisionProvider.FeatureType.LABEL_DETECTION,
            VisionProvider.FeatureType.IMAGE_PROPERTIES);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectStorage storage;
    private final VisionProvider provider;
    private final VisionFeatureExtractor extractor = new VisionFeatureExtractor();
    private final VisionProperties properties;

    public VisionJobWorker(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            ObjectStorage storage,
            VisionProvider provider,
            VisionProperties properties
    ) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.storage = storage;
        this.provider = provider;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "${vision.worker-interval:PT1S}",
            initialDelayString = "${vision.worker-initial-delay:PT1S}")
    public void scheduledProcess() {
        if (properties.enabled()) {
            processNext();
        }
    }

    public boolean processNext() {
        Claim claim = transactions.execute(status -> claim());
        if (claim == null) {
            return false;
        }
        if (claim.previousAttemptCount() >= MAX_ATTEMPTS) {
            boolean ambiguous = claim.previousStatus().equals("PROCESSING");
            String error = claim.previousError() == null ? "VISION_PROVIDER_FAILURE" : claim.previousError();
            transactions.executeWithoutResult(status -> failOrRetry(claim, ambiguous, error));
            return true;
        }
        CurrentImage current = transactions.execute(status -> verifyCurrent(claim));
        if (current == null) {
            return true;
        }
        byte[] imageBytes = storage.get(current.objectKey()).bytes();
        if (transactions.execute(status -> verifyCurrent(claim)) == null) {
            return true;
        }

        VisionProvider.VisionResult result;
        try {
            result = provider.analyze(imageBytes,
                    new VisionProvider.VisionRequest(REQUESTED_FEATURES, properties.timeout()));
        } catch (VisionProviderException exception) {
            String error = exception.isAmbiguous() ? "VISION_AMBIGUOUS_ATTEMPT" : "VISION_PROVIDER_FAILURE";
            transactions.executeWithoutResult(status -> failOrRetry(claim, exception.isAmbiguous(), error));
            return true;
        }

        VisionFeatureExtractor.Extraction extraction;
        try {
            extraction = extractor.extract(result);
        } catch (RuntimeException exception) {
            transactions.executeWithoutResult(status -> failOrRetry(claim, false, MALFORMED_RESPONSE_ERROR));
            return true;
        }
        try {
            transactions.executeWithoutResult(status -> persist(claim, extraction));
        } catch (RuntimeException exception) {
            transactions.executeWithoutResult(status -> failOrRetry(claim, true, "VISION_AMBIGUOUS_ATTEMPT"));
        }
        return true;
    }

    private Claim claim() {
        String leaseOwner = UUID.randomUUID().toString();
        List<Claim> claimed = jdbc.query("""
                WITH candidate AS (
                    SELECT id, status AS previous_status, attempt_count AS previous_attempt_count, last_error
                    FROM found_item_vision_jobs
                    WHERE (status = 'PENDING' AND next_attempt_at <= clock_timestamp())
                       OR (status = 'PROCESSING' AND lease_until <= clock_timestamp())
                    ORDER BY next_attempt_at, id
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                )
                UPDATE found_item_vision_jobs job
                SET status = 'PROCESSING',
                    attempt_count = LEAST(job.attempt_count + 1, 3),
                    lease_owner = ?,
                    lease_until = clock_timestamp() + INTERVAL '60 seconds',
                    updated_at = clock_timestamp()
                FROM candidate
                WHERE job.id = candidate.id
                RETURNING job.id, job.found_item_id, job.image_id,
                          job.analysis_generation, job.attempt_count, job.lease_owner,
                          candidate.previous_status, candidate.previous_attempt_count, candidate.last_error
                """, (resultSet, rowNumber) -> claim(resultSet), leaseOwner);
        return claimed.isEmpty() ? null : claimed.getFirst();
    }

    private Claim claim(ResultSet resultSet) throws SQLException {
        return new Claim(
                resultSet.getLong("id"),
                resultSet.getLong("found_item_id"),
                resultSet.getLong("image_id"),
                resultSet.getInt("analysis_generation"),
                resultSet.getInt("attempt_count"),
                resultSet.getString("lease_owner"),
                resultSet.getString("previous_status"),
                resultSet.getInt("previous_attempt_count"),
                resultSet.getString("last_error"));
    }

    private CurrentImage verifyCurrent(Claim claim) {
        List<CurrentImage> rows = jdbc.query("""
                SELECT image.object_key
                FROM found_item_vision_jobs job
                JOIN found_items item ON item.id = job.found_item_id
                JOIN found_item_images image ON image.id = job.image_id
                WHERE job.id = ?
                  AND job.status = 'PROCESSING'
                  AND job.lease_owner = ?
                  AND job.lease_until > clock_timestamp()
                  AND item.id = ?
                  AND item.analysis_generation = ?
                  AND image.found_item_id = item.id
                  AND image.analysis_generation = ?
                  AND image.is_current = TRUE
                FOR UPDATE OF job, item, image
                """, (resultSet, rowNumber) -> new CurrentImage(resultSet.getString("object_key")),
                claim.jobId(), claim.leaseOwner(), claim.foundItemId(),
                claim.generation(), claim.generation());
        if (!rows.isEmpty()) {
            return rows.getFirst();
        }
        supersedeIfOwned(claim);
        return null;
    }

    private void persist(Claim claim, VisionFeatureExtractor.Extraction extraction) {
        if (verifyCurrent(claim) == null) {
            return;
        }
        jdbc.update("""
                DELETE FROM item_features
                WHERE item_id = ? AND source = 'AI' AND visibility = 'MATCH_ONLY'
                """, claim.foundItemId());
        for (VisionFeatureExtractor.ExtractedFeature feature : extraction.features()) {
            jdbc.update("""
                    INSERT INTO item_features
                        (item_id, kind, feature_value, ordinal, source, visibility, confidence, created_at)
                    VALUES (?, ?, ?, ?, 'AI', 'MATCH_ONLY', ?, clock_timestamp())
                    """, claim.foundItemId(), feature.kind().name(), feature.value(),
                    feature.ordinal(), feature.confidence());
        }
        jdbc.update("""
                UPDATE found_items
                SET vision_status = 'READY', updated_at = clock_timestamp()
                WHERE id = ? AND analysis_generation = ?
                """, claim.foundItemId(), claim.generation());
        jdbc.update("""
                UPDATE found_item_vision_jobs
                SET status = 'READY', lease_owner = NULL, lease_until = NULL,
                    last_error = NULL, completed_at = clock_timestamp(), updated_at = clock_timestamp()
                WHERE id = ? AND status = 'PROCESSING' AND lease_owner = ?
                """, claim.jobId(), claim.leaseOwner());
    }

    private void failOrRetry(Claim claim, boolean ambiguous, String error) {
        if (verifyCurrent(claim) == null) {
            return;
        }
        if (claim.attemptCount() >= MAX_ATTEMPTS) {
            String finalError = ambiguous ? AMBIGUOUS_FINAL_ERROR : error;
            jdbc.update("""
                    UPDATE found_item_vision_jobs
                    SET status = 'FAILED', lease_owner = NULL, lease_until = NULL,
                        last_error = ?, completed_at = clock_timestamp(), updated_at = clock_timestamp()
                    WHERE id = ? AND status = 'PROCESSING' AND lease_owner = ?
                    """, finalError, claim.jobId(), claim.leaseOwner());
            jdbc.update("""
                    UPDATE found_items
                    SET vision_status = 'FAILED', updated_at = clock_timestamp()
                    WHERE id = ? AND analysis_generation = ?
                    """, claim.foundItemId(), claim.generation());
            return;
        }
        int retryDelaySeconds = claim.attemptCount() == 1 ? 10 : 60;
        jdbc.update("""
                UPDATE found_item_vision_jobs
                SET status = 'PENDING', lease_owner = NULL, lease_until = NULL,
                    last_error = ?, next_attempt_at = clock_timestamp() + (? * INTERVAL '1 second'),
                    updated_at = clock_timestamp()
                WHERE id = ? AND status = 'PROCESSING' AND lease_owner = ?
                """, error, retryDelaySeconds, claim.jobId(), claim.leaseOwner());
    }

    private void supersedeIfOwned(Claim claim) {
        jdbc.update("""
                UPDATE found_item_vision_jobs
                SET status = 'SUPERSEDED', lease_owner = NULL, lease_until = NULL,
                    completed_at = clock_timestamp(), updated_at = clock_timestamp()
                WHERE id = ? AND status = 'PROCESSING' AND lease_owner = ?
                """, claim.jobId(), claim.leaseOwner());
    }

    private record Claim(
            long jobId,
            long foundItemId,
            long imageId,
            int generation,
            int attemptCount,
            String leaseOwner,
            String previousStatus,
            int previousAttemptCount,
            String previousError
    ) {
    }

    private record CurrentImage(String objectKey) {
    }
}
