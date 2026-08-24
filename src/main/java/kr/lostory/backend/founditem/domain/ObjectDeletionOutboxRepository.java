package kr.lostory.backend.founditem.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ObjectDeletionOutboxRepository extends JpaRepository<ObjectDeletionOutbox, Long> {

    Optional<ObjectDeletionOutbox> findFirstByStatusOrderByIdAsc(String status);

    long countByStatus(String status);
}
