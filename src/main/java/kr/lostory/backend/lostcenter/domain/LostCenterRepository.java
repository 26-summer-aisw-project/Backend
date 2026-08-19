package kr.lostory.backend.lostcenter.domain;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LostCenterRepository extends JpaRepository<LostCenter, Long> {

    List<LostCenter> findAllBySourceKeyIn(Collection<String> sourceKeys);

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
                          'official_local_verified'
                      )
                    ORDER BY lc.location <-> ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography
                    LIMIT :limit
                    """,
            nativeQuery = true
    )
    List<NearbyLostCenterProjection> findNearby(
            @Param("latitude") BigDecimal latitude,
            @Param("longitude") BigDecimal longitude,
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
