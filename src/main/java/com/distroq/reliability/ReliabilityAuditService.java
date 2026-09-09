package com.distroq.reliability;

import com.distroq.model.ReliabilityAction;
import com.distroq.model.ReliabilityActionType;
import com.distroq.repository.ReliabilityActionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * The only way a v0.8 repair is recorded.
 *
 * <p>{@link #record} is {@code MANDATORY}: it refuses to run outside a transaction, so an audit
 * row can never be written next to a state change that later rolls back, and a repair can never
 * be committed with no audit row beside it. That is the entire point of the class, and making it
 * a runtime failure rather than a convention is what keeps it true.
 */
@Service
public class ReliabilityAuditService {

    /** Repairs the scheduled reconciliation sweep performs on its own initiative. */
    public static final String SYSTEM_ACTOR = "reconciliation";

    private final ReliabilityActionRepository repository;

    public ReliabilityAuditService(ReliabilityActionRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public ReliabilityAction record(ReliabilityActionType actionType, String targetType,
                                    UUID targetId, String reason, String actor,
                                    String beforeState, String afterState) {
        return repository.save(ReliabilityAction.of(actionType, targetType, targetId, reason,
                actor, beforeState, afterState));
    }
}
