package com.localgov.service.payroll;

import com.localgov.service.payroll.model.PayslipData;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.localgov.service.payroll.storage.ObjectStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;

/**
 * Renders a {@link PayslipData} to PDF bytes.
 *
 * <p>Layout follows the design agreed for the platform:
 * <ul>
 *   <li>Header: council logo + name + address + PAYE/NAPSA reg + period + run</li>
 *   <li>Employee block: Emp No, Name, NRC (masked), TPIN (masked),
 *       Position, Division, Scale/Notch, Bank (masked)</li>
 *   <li>Two-column earnings | deductions table</li>
 *   <li>Totals: gross | deductions, then NET PAY</li>
 *   <li>Employer contributions (small)</li>
 *   <li>Footer: run code + pdf_hash hint</li>
 * </ul>
 *
 * <p>Determinism note: the pdf_hash is NOT the SHA-256 of the PDF
 * bytes. It is the SHA-256 of a canonical serialization of the
 * {@link PayslipData}. This decouples the hash chain from OpenPDF's
 * internal metadata (which includes a random document ID and cannot
 * be reliably pinned across OpenPDF versions) and makes the hash
 * stable when only the renderer internals change.
 */
@Component
public class PayslipRenderer {

    private static final Logger log = LoggerFactory.getLogger(PayslipRenderer.class);

    private static final Font FONT_TITLE =
        FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13);
    private static final Font FONT_HEADER =
        FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10);
    private static final Font FONT_LABEL =
        FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8);
    private static final Font FONT_BODY =
        FontFactory.getFont(FontFactory.HELVETICA, 8);
    private static final Font FONT_SMALL =
        FontFactory.getFont(FontFactory.HELVETICA, 6.5f);

    /** "01 Oct 2026" — used for period start / end dates. */
    private static final java.time.format.DateTimeFormatter DATE_FMT =
        java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy", java.util.Locale.ENGLISH);

    /** "October 2026" — used for the period header. */
    private static final java.time.format.DateTimeFormatter MONTH_YEAR_FMT =
        java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy", java.util.Locale.ENGLISH);

    private static final String DASH = "\u2014";  // em dash for null fields

    private final ObjectStorage storage;

    public PayslipRenderer(ObjectStorage storage) {
        this.storage = storage;
    }

    public byte[] render(PayslipData data) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 30, 30, 30, 30);
        try {
            PdfWriter.getInstance(doc, baos);
            doc.open();

            addHeader(doc, data);
            addEmployeeBlock(doc, data);
            addEarningsDeductions(doc, data);
            addTotals(doc, data);
            addEmployerContributions(doc, data);
            addFooter(doc, data);

            doc.close();
        } catch (Exception e) {
            throw new RuntimeException(
                "Failed to render payslip PDF for " + data.employeeId(), e);
        }
        return baos.toByteArray();
    }

    // ------------------------------------------------------------------
    // Header
    // ------------------------------------------------------------------

    private void addHeader(Document doc, PayslipData data) throws Exception {
        PdfPTable header = new PdfPTable(2);
        header.setWidthPercentage(100);
        header.setWidths(new float[] { 1, 3 });

        // Left: logo (if present)
        PdfPCell logoCell = new PdfPCell();
        logoCell.setBorder(0);
        logoCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        if (data.authorityLogoKey() != null) {
            try {
                byte[] logoBytes = storage.get(data.authorityLogoKey());
                if (logoBytes != null && logoBytes.length > 0) {
                    Image img = Image.getInstance(logoBytes);
                    img.scaleToFit(70, 50);
                    logoCell.addElement(img);
                }
            } catch (Exception e) {
                log.warn("Could not load council logo '{}': {}",
                    data.authorityLogoKey(), e.getMessage());
                // fall through — header renders without logo
            }
        }
        header.addCell(logoCell);

        // Right: council name + address + registration numbers
        PdfPCell infoCell = new PdfPCell();
        infoCell.setBorder(0);
        Paragraph name = new Paragraph(nvl(data.authorityName()), FONT_TITLE);
        name.setAlignment(Element.ALIGN_CENTER);
        infoCell.addElement(name);

        if (data.authorityAddress() != null) {
            Paragraph addr = new Paragraph(data.authorityAddress(), FONT_SMALL);
            addr.setAlignment(Element.ALIGN_CENTER);
            infoCell.addElement(addr);
        }

        String periodLabel = data.periodStart() == null
            ? nvl(data.periodCode())
            : data.periodStart().format(MONTH_YEAR_FMT);
        String periodRange = (data.periodStart() != null && data.periodEnd() != null)
            ? "  (" + data.periodStart().format(DATE_FMT)
                   + " \u2013 " + data.periodEnd().format(DATE_FMT) + ")"
            : "";
        Paragraph subtitle = new Paragraph(
            "PAY STATEMENT  \u2014  " + periodLabel + periodRange,
            FONT_HEADER);
        subtitle.setAlignment(Element.ALIGN_CENTER);
        infoCell.addElement(subtitle);

        StringBuilder regs = new StringBuilder();
        if (data.payeRegistrationNo() != null)
            regs.append("PAYE: ").append(data.payeRegistrationNo()).append("   ");
        if (data.napsaRegistrationNo() != null)
            regs.append("NAPSA: ").append(data.napsaRegistrationNo());
        if (regs.length() > 0) {
            Paragraph regLine = new Paragraph(regs.toString(), FONT_SMALL);
            regLine.setAlignment(Element.ALIGN_CENTER);
            infoCell.addElement(regLine);
        }
        header.addCell(infoCell);

        doc.add(header);
        doc.add(spacer());
    }

    // ------------------------------------------------------------------
    // Employee block
    // ------------------------------------------------------------------

    private void addEmployeeBlock(Document doc, PayslipData data) throws Exception {
        PdfPTable t = new PdfPTable(4);
        t.setWidthPercentage(100);
        t.setWidths(new float[] { 1, 2, 1, 2 });
        addLabelValue(t, "EMP NO:", nvl(data.employeeId()));
        addLabelValue(t, "NAME:",   nvl(data.employeeName()));
        addLabelValue(t, "NRC:",    nvlOrDash(data.nrcMasked()));
        addLabelValue(t, "TPIN:",   nvlOrDash(data.tpinMasked()));
        addLabelValue(t, "POSITION:", nvlOrDash(data.position()));
        addLabelValue(t, "DIVISION:", nvlOrDash(data.division()));
        addLabelValue(t, "SCALE / NOTCH:",
            nvl(data.salaryScale()) + " / " + data.notchNo());
        addLabelValue(t, "BANK:",
            bankLine(data.bankName(), data.bankAccountMasked()));
        doc.add(t);
        doc.add(spacer());
    }

    private String bankLine(String bankName, String accountMasked) {
        if (bankName == null && accountMasked == null) return DASH;
        if (accountMasked == null) return bankName;
        if (bankName == null)      return accountMasked;
        return bankName + "  " + accountMasked;
    }

    // ------------------------------------------------------------------
    // Earnings | Deductions
    // ------------------------------------------------------------------

    private void addEarningsDeductions(Document doc, PayslipData data) throws Exception {
        PdfPTable outer = new PdfPTable(2);
        outer.setWidthPercentage(100);
        outer.setWidths(new float[] { 1, 1 });

        PdfPCell left = new PdfPCell(linesTable("EARNINGS", data.earnings()));
        left.setBorder(0);
        left.setPaddingRight(6);
        outer.addCell(left);

        PdfPCell right = new PdfPCell(linesTable("DEDUCTIONS", data.deductions()));
        right.setBorder(0);
        right.setPaddingLeft(6);
        outer.addCell(right);

        doc.add(outer);
        doc.add(spacer());
    }

    private PdfPTable linesTable(String heading, List<PayslipData.Line> lines) {
        PdfPTable t = new PdfPTable(2);
        t.setWidthPercentage(100);
        t.setWidths(new float[] { 3, 1 });

        PdfPCell h = new PdfPCell(new Phrase(heading, FONT_HEADER));
        h.setColspan(2);
        h.setBorder(0);
        h.setPaddingBottom(4);
        t.addCell(h);

        for (PayslipData.Line l : lines) {
            t.addCell(cellNoBorder(l.name(), FONT_BODY, Element.ALIGN_LEFT));
            t.addCell(cellNoBorder(formatMoney(l.amount()), FONT_BODY, Element.ALIGN_RIGHT));
        }
        return t;
    }

    // ------------------------------------------------------------------
    // Totals
    // ------------------------------------------------------------------

    private void addTotals(Document doc, PayslipData data) throws Exception {
        PdfPTable outer = new PdfPTable(2);
        outer.setWidthPercentage(100);
        outer.setWidths(new float[] { 1, 1 });

        PdfPCell leftGross = new PdfPCell(totalsCell(
            "GROSS PAY", data.grossPay()));
        leftGross.setBorder(0);
        leftGross.setPaddingRight(6);
        outer.addCell(leftGross);

        PdfPCell rightDed = new PdfPCell(totalsCell(
            "TOTAL DEDUCTIONS", data.totalDeductions()));
        rightDed.setBorder(0);
        rightDed.setPaddingLeft(6);
        outer.addCell(rightDed);

        doc.add(outer);

        PdfPTable net = new PdfPTable(2);
        net.setWidthPercentage(100);
        net.setWidths(new float[] { 3, 1 });
        net.addCell(cellNoBorder("NET PAY (bank transfer)", FONT_HEADER, Element.ALIGN_LEFT));
        net.addCell(cellNoBorder(formatMoney(data.netPay()), FONT_HEADER, Element.ALIGN_RIGHT));
        doc.add(net);
        doc.add(spacer());
    }

    private PdfPTable totalsCell(String label, BigDecimal amount) {
        PdfPTable t = new PdfPTable(2);
        t.setWidthPercentage(100);
        t.setWidths(new float[] { 3, 1 });
        t.addCell(cellNoBorder(label, FONT_HEADER, Element.ALIGN_LEFT));
        t.addCell(cellNoBorder(formatMoney(amount), FONT_HEADER, Element.ALIGN_RIGHT));
        return t;
    }

    // ------------------------------------------------------------------
    // Employer contributions
    // ------------------------------------------------------------------

    private void addEmployerContributions(Document doc, PayslipData data) throws Exception {
        if (data.employerContribs().isEmpty()) return;

        PdfPTable t = new PdfPTable(2);
        t.setWidthPercentage(100);
        t.setWidths(new float[] { 3, 1 });

        PdfPCell h = new PdfPCell(new Phrase("EMPLOYER CONTRIBUTIONS", FONT_HEADER));
        h.setColspan(2);
        h.setBorder(0);
        h.setPaddingBottom(3);
        t.addCell(h);

        for (PayslipData.Line l : data.employerContribs()) {
            t.addCell(cellNoBorder(l.name(), FONT_SMALL, Element.ALIGN_LEFT));
            t.addCell(cellNoBorder(formatMoney(l.amount()), FONT_SMALL, Element.ALIGN_RIGHT));
        }
        doc.add(t);
    }

    // ------------------------------------------------------------------
    // Footer
    // ------------------------------------------------------------------

    private void addFooter(Document doc, PayslipData data) throws Exception {
        doc.add(spacer());
        Paragraph p = new Paragraph(
            "Run: " + nvl(data.runCode())
                + "  \u2022  Hash-chained. Verify against the payroll run record.",
            FONT_SMALL);
        p.setAlignment(Element.ALIGN_CENTER);
        doc.add(p);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static void addLabelValue(PdfPTable t, String label, String value) {
        PdfPCell labelCell = new PdfPCell(new Phrase(label, FONT_LABEL));
        labelCell.setBorder(0);
        labelCell.setPaddingTop(1);
        labelCell.setPaddingBottom(1);
        t.addCell(labelCell);
        t.addCell(cellNoBorder(value, FONT_BODY, Element.ALIGN_LEFT));
    }

    private static PdfPCell cellNoBorder(String text, Font font, int align) {
        PdfPCell c = new PdfPCell(new Phrase(text, font));
        c.setBorder(0);
        c.setHorizontalAlignment(align);
        c.setPaddingTop(1);
        c.setPaddingBottom(1);
        return c;
    }

    private static Paragraph spacer() {
        return new Paragraph(" ", FONT_SMALL);
    }

    private static String nvl(String s) { return s == null ? "" : s; }
    private static String nvlOrDash(String s) { return s == null || s.isBlank() ? DASH : s; }

    private static String formatMoney(BigDecimal amount) {
        return String.format("%,.2f", amount);
    }
}
