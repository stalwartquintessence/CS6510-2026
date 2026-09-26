package com.supermarket.api;

import com.supermarket.analytics.AnalyticsService;
import com.supermarket.api.dto.AnalyticsDtos.PopularItemsResponse;
import com.supermarket.api.mapper.AnalyticsDtoMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/analytics")
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    public AnalyticsController(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @GetMapping("/popular-items")
    public PopularItemsResponse popularItems(
            @RequestParam(required = false, defaultValue = "10") int limit) {
        return AnalyticsDtoMapper.toResponse(analyticsService.getPopular(limit));
    }
}
