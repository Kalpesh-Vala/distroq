package com.distroq.repository;

import com.distroq.model.ReliabilityAction;
import com.distroq.model.ReliabilityActionType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ReliabilityActionRepository extends JpaRepository<ReliabilityAction, UUID> {

    Page<ReliabilityAction> findByOrderByCreatedAtDesc(Pageable pageable);

    Page<ReliabilityAction> findByActionTypeOrderByCreatedAtDesc(ReliabilityActionType actionType,
                                                                 Pageable pageable);

    Page<ReliabilityAction> findByTargetIdOrderByCreatedAtDesc(UUID targetId, Pageable pageable);

    Page<ReliabilityAction> findByActionTypeAndTargetIdOrderByCreatedAtDesc(
            ReliabilityActionType actionType, UUID targetId, Pageable pageable);

    long countByActionType(ReliabilityActionType actionType);

    long countByActionTypeNot(ReliabilityActionType actionType);
}
