package com.supermarket.analytics;

import com.supermarket.domain.PopularItemRow;
import com.supermarket.persistence.PopularItemsDao;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Rewrites the persisted ranking.
 *
 * <p>A separate bean so the Spring proxy is really in the call path (week 1
 * called the equivalent method on itself, which made {@code @Transactional} a
 * silent no-op). That matters more this week: the caller is the pipeline's
 * Publish stage, a plain thread with no transaction of its own, and
 * {@code deleteAllInBatch} refuses to run outside one.
 */
@Component
class PopularItemsRankingWriter {

    private final PopularItemsDao popularItemsDao;

    PopularItemsRankingWriter(PopularItemsDao popularItemsDao) {
        this.popularItemsDao = popularItemsDao;
    }

    /**
     * Opens and commits its own transaction. In week 2 this joined the
     * customer's scan transaction; on the pipeline thread there is no such
     * transaction, so the delete-and-insert commits as one unit by itself.
     */
    @Transactional
    public void rewrite(List<PopularItemRow> rows) {
        popularItemsDao.replaceRanking(rows);
    }
}
