package com.supermarket.analytics;

import com.supermarket.domain.PopularItemRow;
import com.supermarket.persistence.PopularItemsDao;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Rewrites the persisted ranking.
 *
 * <p>This exists as a separate bean for a specific reason. In week 1 the same
 * logic was a {@code @Transactional} method on the analytics service that the
 * service called on itself — a self-invocation, which bypasses the Spring proxy
 * and makes the annotation a silent no-op. It happened to work only because the
 * scan that triggered it was already inside a transaction. Moving it to its own
 * bean means the proxy is really in the call path and {@code REQUIRED} means
 * what it says.
 */
@Component
class PopularItemsRankingWriter {

    private final PopularItemsDao popularItemsDao;

    PopularItemsRankingWriter(PopularItemsDao popularItemsDao) {
        this.popularItemsDao = popularItemsDao;
    }

    /** Joins the caller's transaction, so the ranking commits with the scan. */
    @Transactional(propagation = Propagation.REQUIRED)
    public void rewrite(List<PopularItemRow> rows) {
        popularItemsDao.replaceRanking(rows);
    }
}
