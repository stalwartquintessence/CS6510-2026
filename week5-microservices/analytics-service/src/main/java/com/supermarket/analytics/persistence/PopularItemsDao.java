package com.supermarket.analytics.persistence;

import com.supermarket.analytics.PopularItemRow;

import java.util.List;

/** Reads and rewrites the persisted popular-items ranking. Private to the analytics service. */
public interface PopularItemsDao {

    /** The current ranking, best-ranked first. */
    List<PopularItemRow> findRanking();

    /** Atomically replace the whole ranking. */
    void replaceRanking(List<PopularItemRow> rows);
}
