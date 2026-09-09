package com.distroq.reliability;

import com.distroq.config.DistroqProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs reconciliation on a timer.
 *
 * <p>The sweep asks for exactly what configuration allows, so a deployment with
 * {@code auto-repair: false} runs a pure preview forever and costs only queries. It never waits
 * for the advisory lock: if another instance is already reconciling, this tick has nothing useful
 * to add and skipping is cheaper than queueing.
 */
@Component
public class ReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationScheduler.class);

    private final ReconciliationService service;
    private final DistroqProperties.Reconciliation properties;

    public ReconciliationScheduler(ReconciliationService service, DistroqProperties properties) {
        this.service = service;
        this.properties = properties.reconciliation();
    }

    @Scheduled(fixedDelayString = "${distroq.reconciliation.poll-interval-ms:30000}",
            initialDelayString = "${distroq.reconciliation.poll-interval-ms:30000}")
    public void sweep() {
        if (!properties.enabled()) {
            return;
        }
        try {
            service.run(properties.autoRepair(), "Scheduled reconciliation sweep",
                    ReliabilityAuditService.SYSTEM_ACTOR, false);
        } catch (Exception e) {
            log.error("Reconciliation sweep failed; the next tick will retry", e);
        }
    }
}
