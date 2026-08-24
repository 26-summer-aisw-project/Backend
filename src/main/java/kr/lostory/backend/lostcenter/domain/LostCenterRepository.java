package kr.lostory.backend.lostcenter.domain;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LostCenterRepository extends JpaRepository<LostCenter, Long> {

    @Query(value = """
            SELECT EXISTS (
                SELECT 1 FROM lost_centers center
                WHERE center.id = :centerId
                  AND center.is_active = true
                  AND center.verification_status IN (
                      'official_verified', 'official_board_verified', 'official_local_verified')
                  AND ST_DWithin(
                      center.location,
                      ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography,
                      1000)
            )
            """, nativeQuery = true)
    boolean isEligibleForHandover(
            @Param("centerId") Long centerId,
            @Param("latitude") BigDecimal latitude,
            @Param("longitude") BigDecimal longitude);

    List<LostCenter> findAllBySourceKeyIn(Collection<String> sourceKeys);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update LostCenter center set center.active = false "
            + "where center.csvManaged = true and center.sourceKey not in :sourceKeys")
    int deactivateCsvManagedNotIn(@Param("sourceKeys") Collection<String> sourceKeys);

    @Query("""
            select center from LostCenter center
            where center.active = true
              and center.verificationStatus in :statuses
              and (lower(center.name) like lower(concat('%', :query, '%'))
                or lower(center.address) like lower(concat('%', :query, '%')))
            order by center.name asc, center.id asc
            """)
    Page<LostCenter> findDirectory(
            @Param("statuses") Collection<String> statuses,
            @Param("query") String query,
            Pageable pageable
    );

    @Query(
            value = """
                    SELECT
                        lc.id AS id,
                        lc.source_key AS "centerKey",
                        lc.name AS name,
                        lc.parent_place AS "parentPlace",
                        lc.address AS address,
                        lc.detail_location AS "detailLocation",
                        lc.contact_phone AS "phoneNumber",
                        lc.operating_hours AS "operatingHours",
                        lc.verification_status AS "verificationStatus",
                        ST_Y(lc.location::geometry) AS latitude,
                        ST_X(lc.location::geometry) AS longitude,
                        ST_Distance(
                            lc.location,
                            ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography
                        ) AS "distanceMeters"
                    FROM lost_centers lc
                    WHERE lc.is_active = true
                      AND lc.verification_status IN (
                          'official_verified',
                          'official_board_verified',
                          'official_local_verified',
                          'admin_verified'
                      )
                      AND ST_DWithin(
                          lc.location,
                          ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography,
                          :radius
                      )
                    ORDER BY "distanceMeters" ASC, lc.id ASC
                    LIMIT :limit
                    """,
            nativeQuery = true
    )
    List<NearbyLostCenterProjection> findNearby(
            @Param("latitude") BigDecimal latitude,
            @Param("longitude") BigDecimal longitude,
            @Param("radius") int radius,
            @Param("limit") int limit
    );

    interface NearbyLostCenterProjection {
        Long getId();

        String getCenterKey();

        String getName();

        String getParentPlace();

        String getAddress();

        String getDetailLocation();

        String getPhoneNumber();

        String getOperatingHours();

        String getVerificationStatus();

        BigDecimal getLatitude();

        BigDecimal getLongitude();

        Double getDistanceMeters();
    }
}
