package com.supermarket.repository;

import com.supermarket.model.PopularItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PopularItemRepository extends JpaRepository<PopularItem, String> {

    /** The current ranking, best-ranked first. */
    List<PopularItem> findAllByOrderByRankAsc();
}
