package com.supermarket.persistence;

import com.supermarket.domain.PopularItemRow;

import java.util.List;

/** Reads and rewrites the persisted popular-items ranking. */
public interface PopularItemsDao {

    /** The current ranking, best-ranked first. */
    List<PopularItemRow> findRanking();

    /** Atomically replace the whole ranking. */
    void replaceRanking(List<PopularItemRow> rows);
}
