package kr.lostory.backend.founditem.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FoundItemRepository extends JpaRepository<FoundItem, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select item from FoundItem item where item.id = :id")
    Optional<FoundItem> findByIdForUpdate(@Param("id") Long id);

    Page<FoundItem> findByFinderId(Long finderId, Pageable pageable);

    Page<FoundItem> findByFinderIdAndStatus(Long finderId, FoundItemStatus status, Pageable pageable);
}
