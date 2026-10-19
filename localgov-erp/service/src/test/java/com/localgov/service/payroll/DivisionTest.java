package com.localgov.service.payroll;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DivisionTest {

    @Test
    void lgssScalesMapToDivisions() {
        // Both spellings
        assertThat(Division.fromSalaryScale("LGSS/01")).isEqualTo(Division.I);
        assertThat(Division.fromSalaryScale("LGSS01")).isEqualTo(Division.I);
        assertThat(Division.fromSalaryScale("LGSS/07")).isEqualTo(Division.I);
        assertThat(Division.fromSalaryScale("LGSS/08")).isEqualTo(Division.II);
        assertThat(Division.fromSalaryScale("LGSS/12")).isEqualTo(Division.II);
        assertThat(Division.fromSalaryScale("LGSS/13")).isEqualTo(Division.III);
        assertThat(Division.fromSalaryScale("LGSS/18")).isEqualTo(Division.III);
    }

    @Test
    void gScalesMapToDivisionIV() {
        assertThat(Division.fromSalaryScale("G1")).isEqualTo(Division.IV);
        assertThat(Division.fromSalaryScale("G3")).isEqualTo(Division.IV);
    }

    @Test
    void unknownScaleReturnsNull() {
        assertThat(Division.fromSalaryScale("LGSS/99")).isNull();
        assertThat(Division.fromSalaryScale("ZZZ")).isNull();
        assertThat(Division.fromSalaryScale(null)).isNull();
    }
}
