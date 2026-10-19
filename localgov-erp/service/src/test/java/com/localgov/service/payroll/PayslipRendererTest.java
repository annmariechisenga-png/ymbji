package com.localgov.service.payroll;

import com.localgov.service.payroll.model.PayslipData;
import com.localgov.service.payroll.storage.FilesystemObjectStorage;
import com.localgov.service.payroll.storage.ObjectStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PayslipRendererTest {

    @TempDir Path tmpDir;

    private ObjectStorage storage;
    private PayslipRenderer renderer;

    @BeforeEach
    void setUp() {
        storage = new FilesystemObjectStorage(tmpDir.toString());
        renderer = new PayslipRenderer(storage);
    }

    @Test
    void renderedPdfStartsWithMagicBytes() {
        byte[] pdf = renderer.render(sampleData());
        assertThat(pdf).isNotEmpty();
        String head = new String(pdf, 0, Math.min(8, pdf.length), StandardCharsets.US_ASCII);
        assertThat(head).startsWith("%PDF-");
    }

    @Test
    void renderProducesWellFormedPdf() {
        byte[] pdf = renderer.render(sampleData());
        // Contains the EOF marker
        String tail = new String(pdf, Math.max(0, pdf.length - 10), 
                                 Math.min(10, pdf.length), StandardCharsets.US_ASCII);
        assertThat(tail).contains("%%EOF");
    }

    private PayslipData sampleData() {
        return new PayslipData(
            "CHILANGA", "Chilanga Town Council", "Civic Centre",
            null, "1000001", "2000001", "3000001", "4000001",
            "2026-10", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31),
            "RUN-2026-10-TEST",
            "EMP-001", "Test Employee",
            "****10/1", "****1380", "Human Resource Officer",
            "II", "LGSS10", 3,
            "Standard Chartered", "****5200",
            List.of(new PayslipData.Line("BASIC", "Basic Salary", new BigDecimal("8480.00")),
                    new PayslipData.Line("P06", "Housing Allowance", new BigDecimal("1696.00"))),
            List.of(new PayslipData.Line("PAYE", "PAYE (Income Tax)", new BigDecimal("2018.64")),
                    new PayslipData.Line("NAPSA_EMPLOYEE", "NAPSA (Employee)", new BigDecimal("678.40"))),
            List.of(new PayslipData.Line("NAPSA_EMPLOYER", "NAPSA (Employer)", new BigDecimal("678.40"))),
            new BigDecimal("10176.00"),
            new BigDecimal("2697.04"),
            new BigDecimal("7478.96")
        );
    }
}
