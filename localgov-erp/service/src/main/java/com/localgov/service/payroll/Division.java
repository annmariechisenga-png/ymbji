package com.localgov.service.payroll;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HR division, derived from {@code employees.salary_scale}.
 *
 * <p>Same rule across all 116 Zambian local authorities (they share the
 * same Conditions of Service and salary scales):
 *
 * <pre>
 *   LGSS/01 .. LGSS/07   ->  I
 *   LGSS/08 .. LGSS/12   ->  II
 *   LGSS/13 .. LGSS/18   ->  III
 *   G1      .. G3        ->  IV
 * </pre>
 *
 * <p>Both "LGSS/05" and "LGSS05" spellings are accepted, since the
 * baseline seed uses one and the current data-entry UI uses the other.
 *
 * <p>{@link #fromSalaryScale(String)} returns {@code null} for unknown
 * scales (e.g. a new scale introduced before this enum is updated).
 * Payslips then print "Division: —" rather than breaking the render.
 */
public enum Division {
    I, II, III, IV;

    private static final Pattern DIGITS = Pattern.compile("(\\d+)");

    public static Division fromSalaryScale(String salaryScale) {
        if (salaryScale == null) return null;
        String s = salaryScale.trim().toUpperCase().replace(" ", "");

        // LGSS scales (with or without slash)
        String lgss = s.startsWith("LGSS/") ? s.substring(5)
                    : s.startsWith("LGSS")  ? s.substring(4)
                    : null;
        if (lgss != null) {
            int n = extractNumber(lgss);
            if (n >= 1  && n <= 7)  return I;
            if (n >= 8  && n <= 12) return II;
            if (n >= 13 && n <= 18) return III;
            return null;
        }

        // G scales (G1..G3)
        if (s.startsWith("G")) {
            int n = extractNumber(s.substring(1));
            if (n >= 1 && n <= 3) return IV;
            return null;
        }

        return null;
    }

    private static int extractNumber(String s) {
        Matcher m = DIGITS.matcher(s);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }
}
