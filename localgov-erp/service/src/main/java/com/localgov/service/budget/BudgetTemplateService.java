package com.localgov.service.budget;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BudgetTemplateService {

    private final JdbcTemplate jdbc;

    public BudgetTemplateService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Copies the national template programmes into per-council instances.
     * Idempotent — if the council already has rows for these codes, they are skipped.
     *
     * <p>Template rows have authority_code IS NULL. After instantiation, the
     * council has its own rows that can be customized (department assignments,
     * etc.) without affecting the template.
     */
    @Transactional
    public int instantiateCouncilProgrammes(String authorityCode) {
        int inserted = jdbc.update("""
            INSERT INTO mofnp_programme (
                authority_code, department_code, unit_code,
                programme_code, programme_name, sub_programme_code, sub_programme_name,
                mofnp_function, output_description, is_active, effective_from
            )
            SELECT ?, t.department_code, t.unit_code,
                   t.programme_code, t.programme_name, t.sub_programme_code, t.sub_programme_name,
                   t.mofnp_function, t.output_description, t.is_active, t.effective_from
            FROM mofnp_programme t
            WHERE t.authority_code IS NULL
              AND NOT EXISTS (
                  SELECT 1 FROM mofnp_programme c
                  WHERE c.authority_code = ?
                    AND c.programme_code = t.programme_code
                    AND c.sub_programme_code = t.sub_programme_code
              )
            """, authorityCode, authorityCode);
        return inserted;
    }
}
