package com.distroq.repository;

import com.distroq.model.DeadLetter;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DeadLetterRepository extends JpaRepository<DeadLetter, UUID> {

    List<DeadLetter> findTop50ByOrderByMovedAtDesc();

    List<DeadLetter> findTop50ByReplayedOrderByMovedAtDesc(boolean replayed);

    long countByReplayed(boolean replayed);
}
