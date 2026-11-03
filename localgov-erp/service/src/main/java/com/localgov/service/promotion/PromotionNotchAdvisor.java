package com.localgov.service.promotion;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Applies the 17th September 2018 Circular rule for promotion notch placement.
 *
 * <p>Rule: On promotion, the officer is placed on the entry notch (notch 1)
 * of the new scale, UNLESS that would reduce their basic pay. If it would,
 * they are placed on the first notch in the new scale that is strictly
 * higher than their previous basic pay.
 *
 * <p>Example: Officer at LGSS/07 notch 7 (K10,689) is promoted to LGSS/06.
 * LGSS/06 notch 1 pays less, so the system walks up the notches until it
 * finds one > K10,689 (LGSS/06 notch 5 = K10,917).
 */
@Service
public class PromotionNotchAdvisor {

    private final JdbcTemplate jdbcTemplate;

    public PromotionNotchAdvisor(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Returns all notches for a scale, with a flag indicating whether each
     * notch is the recommended one per the 2018 Circular.
     */
    public List<Map<String, Object>> adviseForPromotion(
            BigDecimal previousBasic,
            String newScale,
            LocalDate effectiveDate) {

        List<Map<String, Object>> notches = jdbcTemplate.queryForList(
            "SELECT notch_no, monthly_salary FROM erp_salary_notch_value " +
            "WHERE salary_scale = ? AND effective_from <= ? " +
            "ORDER BY effective_from DESC, notch_no ASC",
            newScale, effectiveDate);

        // Find the recommended notch
        Integer recommendedNotch = null;
        boolean foundHigher = false;

        for (Map<String, Object> row : notches) {
            Integer notchNo = (Integer) row.get("notch_no");
            BigDecimal monthly = (BigDecimal) row.get("monthly_salary");

            if (!foundHigher && monthly.compareTo(previousBasic) > 0) {
                recommendedNotch = notchNo;
                foundHigher = true;
            }
        }

        // If no notch was strictly higher, recommend the highest notch
        if (recommendedNotch == null && !notches.isEmpty()) {
            recommendedNotch = (Integer) notches.get(notches.size() - 1).get("notch_no");
        }

        // Annotate each row with the recommendation
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : notches) {
            Map<String, Object> annotated = new java.util.HashMap<>(row);
            annotated.put("recommended", row.get("notch_no").equals(recommendedNotch));
            result.add(annotated);
        }

        return result;
    }
}
