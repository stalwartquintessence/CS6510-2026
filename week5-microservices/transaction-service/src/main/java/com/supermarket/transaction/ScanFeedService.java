package com.supermarket.transaction;

import com.supermarket.transaction.domain.ScanEvent;
import com.supermarket.transaction.persistence.ScanEventDao;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * The read side of the {@code scan_events} outbox, for the analytics service.
 *
 * <p>In week 4 analytics queried {@code scan_log} in the shared database itself. Here
 * the table is private, so this service publishes it as a feed that the consumer pages
 * through with its own cursor, like a log-based broker's consumer offset. The consumer
 * is still the one that decides what has settled (see analytics' {@code ScanFeedSource}).
 */
@Service
class ScanFeedService {

    static final int MAX_BATCH = 5_000;

    private final ScanEventDao scanEvents;

    ScanFeedService(ScanEventDao scanEvents) {
        this.scanEvents = scanEvents;
    }

    @Transactional(readOnly = true)
    List<ScanEvent> after(long afterId, int limit) {
        return scanEvents.findAfter(afterId, limit <= 0 ? MAX_BATCH : Math.min(limit, MAX_BATCH));
    }

    @Transactional(readOnly = true)
    long latestId() {
        return scanEvents.maxId();
    }
}
