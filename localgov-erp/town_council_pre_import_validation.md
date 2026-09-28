# ymbji — Town Council Pre-Import Validation Report

## 1. Authority

This report was generated directly from the approved Town Council Final Staging Disposition Matrix.

The importer acts only as a transformer. It does not reclassify positions, infer attachment, invent salary scales, or invent authority codes.

## 2. Final Control Totals

| Control | Expected | Actual | Status |
|---|---:|---:|---|
| Source disposition rows | 546 | 546 | PASS |
| Import rows | 542 | 542 | PASS |
| Exclude rows | 4 | 4 | PASS |
| Rejections | 0 | 0 | PASS |

## 3. Validation Gates

| Gate | Purpose | Status |
|---|---|---|
| Gate 1 | Matrix row count | PASS |
| Gate 2 | Import / exclude control totals | PASS |
| Gate 3 | Rejection and duplicate control | PASS |
| Gate 4 | Structural/state/applicability validation | PASS |
| Gate 5 | Imported record state validation | PASS |

**FINAL MACHINE-STAGING STATUS: PASS**

## 4. Applicability Distribution

| Applicability | Count |
|---|---:|
| APPLICABLE | 100 |
| LOCATION-SPECIFIC TEMPLATE | 6 |
| NATIONAL REFERENCE | 424 |
| REGIONAL / AUTHORITY NOT SPECIFIED | 12 |

## 5. Source Node Types

| Node Type | Count |
|---|---:|
| LOCATION_SPECIFIC_POSITION | 118 |
| POSITION | 424 |

## 6. Attachment State

| Attached | Count |
|---|---:|
| Yes | 366 |
| No | 176 |

Attachment values are copied from the approved matrix. They are not inferred from salary scale.

## 7. Imported Positions by Department

| Department | Import Rows |
|---|---:|
| COMMUNITY SERVICES DEPARTMENT | 23 |
| ENGINEERING DEPARTMENT | 93 |
| FINANCE DEPARTMENT | 23 |
| FISHERIES, LIVESTOCK & VETERINARY SERVICES DEPARTMENT | 115 |
| HEALTH SERVICES DEPARTMENT | 219 |
| HUMAN RESOURCE & ADMINISTRATION DEPARTMENT | 28 |
| PLANNING DEPARTMENT | 20 |
| TOWN COUNCIL | 21 |

## 8. Salary Scale Distribution

| Salary Scale | Count |
|---|---:|
| (source scale) | 14 |
| A | 20 |
| A1 | 1 |
| A2 | 8 |
| B | 1 |
| C | 2 |
| D | 30 |
| D1 | 4 |
| D2 | 2 |
| D3 | 2 |
| E | 6 |
| F | 15 |
| G | 52 |
| G1 | 13 |
| G2 | 2 |
| G3 | 13 |
| H | 47 |
| H1 | 3 |
| I | 23 |
| J | 5 |
| K | 5 |
| L | 6 |
| LGSS | 30 |
| LGSS/03 | 1 |
| LGSS/04 | 1 |
| LGSS/05 | 8 |
| LGSS/06 | 24 |
| LGSS/07 | 38 |
| LGSS/08 | 38 |
| LGSS/09 | 26 |
| LGSS/10 | 53 |
| LGSS/11 | 6 |
| LGSS/12 | 11 |
| LGSS/13 | 5 |
| LGSS/14 | 16 |
| LGSS/15 | 1 |
| LGSS/16 | 2 |
| LGSS/17 | 3 |
| LGSS/18 | 1 |
| N | 4 |

## 9. Controlled Review Items

### Incomplete / Placeholder Source Scales

- TC-0388: 'LGSS' preserved exactly; downstream salary/rate resolution required.
- TC-0389: 'LGSS' preserved exactly; downstream salary/rate resolution required.
- TC-0390: 'LGSS' preserved exactly; downstream salary/rate resolution required.
- TC-0391: 'LGSS' preserved exactly; downstream salary/rate resolution required.
- TC-0392: 'LGSS' preserved exactly; downstream salary/rate resolution required.
- TC-0393: 'LGSS' preserved exactly; downstream salary/rate resolution required.
- TC-0397: 'LGSS' preserved exactly; downstream salary/rate resolution required.
- TC-0398: 'LGSS' preserved exactly; downstream salary/rate resolution required.
- TC-0403: 'LGSS' preserved exactly; downstream salary/rate resolution required.
- TC-0414: 'LGSS' preserved exactly; downstream salary/rate resolution required.
- TC-0417: 'LGSS' preserved exactly; downstream salary/rate resolution required.
- TC-0420: 'LGSS' preserved exactly; downstream salary/rate resolution required.
- TC-0423: 'LGSS' preserved exactly; downstream salary/rate resolution required.
- TC-0426: 'LGSS' preserved exactly; downstream salary/rate resolution required.
- TC-0429: 'LGSS' preserved exactly; downstream salary/rate resolution required.
- TC-0432: 'LGSS' preserved exactly; downstream salary/rate resolution required.
- TC-0435: 'LGSS' preserved exactly; downstream salary/rate resolution required.
- TC-0438: 'LGSS' preserved exactly; downstream salary/rate resolution required.
- TC-0441: 'LGSS' preserved exactly; downstream salary/rate resolution required.
- TC-0444: 'LGSS' preserved exactly; downstream salary/rate resolution required.
- TC-0447: 'LGSS' preserved exactly; downstream salary/rate resolution required.
- TC-0450: 'LGSS' preserved exactly; downstream salary/rate resolution required.
- TC-0453: 'LGSS' preserved exactly; downstream salary/rate resolution required.
- TC-0456: 'LGSS' preserved exactly; downstream salary/rate resolution required.
- TC-0459: 'LGSS' preserved exactly; downstream salary/rate resolution required.
- TC-0462: 'LGSS' preserved exactly; downstream salary/rate resolution required.
- TC-0465: 'LGSS' preserved exactly; downstream salary/rate resolution required.
- TC-0468: 'LGSS' preserved exactly; downstream salary/rate resolution required.
- TC-0471: 'LGSS' preserved exactly; downstream salary/rate resolution required.
- TC-0474: 'LGSS' preserved exactly; downstream salary/rate resolution required.
- TC-0475: '(source scale)' preserved exactly; downstream salary/rate resolution required.
- TC-0476: '(source scale)' preserved exactly; downstream salary/rate resolution required.
- TC-0477: '(source scale)' preserved exactly; downstream salary/rate resolution required.
- TC-0478: '(source scale)' preserved exactly; downstream salary/rate resolution required.
- TC-0479: '(source scale)' preserved exactly; downstream salary/rate resolution required.
- TC-0480: '(source scale)' preserved exactly; downstream salary/rate resolution required.
- TC-0481: '(source scale)' preserved exactly; downstream salary/rate resolution required.
- TC-0482: '(source scale)' preserved exactly; downstream salary/rate resolution required.
- TC-0483: '(source scale)' preserved exactly; downstream salary/rate resolution required.
- TC-0484: '(source scale)' preserved exactly; downstream salary/rate resolution required.
- TC-0485: '(source scale)' preserved exactly; downstream salary/rate resolution required.
- TC-0486: '(source scale)' preserved exactly; downstream salary/rate resolution required.
- TC-0487: '(source scale)' preserved exactly; downstream salary/rate resolution required.
- TC-0488: '(source scale)' preserved exactly; downstream salary/rate resolution required.

These values are deliberately preserved. The importer does not invent the missing scale.

## 10. Authority Resolution

The source matrix contains authority names. The machine staging file therefore preserves these as `source_authority`.

`authority_code` is populated only where an actual authority code is explicitly present. No code is generated from an authority name.

Authority-name → authority-code resolution is a separate registry step and must occur before authority-specific canonical instantiation.

### Regional / Unspecified Authority Rows

- TC-0083: regional source row has no authority-specific instantiation
- TC-0084: regional source row has no authority-specific instantiation
- TC-0085: regional source row has no authority-specific instantiation
- TC-0086: regional source row has no authority-specific instantiation
- TC-0087: regional source row has no authority-specific instantiation
- TC-0088: regional source row has no authority-specific instantiation
- TC-0089: regional source row has no authority-specific instantiation
- TC-0090: regional source row has no authority-specific instantiation
- TC-0091: regional source row has no authority-specific instantiation
- TC-0092: regional source row has no authority-specific instantiation
- TC-0093: regional source row has no authority-specific instantiation
- TC-0094: regional source row has no authority-specific instantiation

## 11. Approved Exclusions

These rows remain in the disposition matrix and are not emitted as canonical import records.

| Disposition ID | Position | Establishment | Reason |
|---|---|---:|---|
| TC-0019 | Group Internal Auditor | 0 | Zero establishment — retain in staging; do not import into canonical structure. |
| TC-0496 | Assistant Human Resource Officer | 0 | Zero establishment — retain in staging; do not import into canonical structure. |
| TC-0517 | Clerical Officer | 0 | Zero establishment — retain in staging; do not import into canonical structure. |
| TC-0533 | Stores Officer | 0 | Zero establishment — retain in staging; do not import into canonical structure. |

## 12. Rejections / Errors

None. No machine rejection occurred.

## 13. Duplicate Source References

None detected.

## 14. Final Import Contract

A record may enter the machine import CSV only when:

1. `Import? = Yes`.
2. `Disposition = IMPORT`.
3. Establishment is a positive integer.
4. Required structural fields are present.
5. Attachment state is explicitly supplied by the matrix.
6. Applicability is a recognised controlled value.
7. No transformation rejection exists for the record.

The importer does not create employees, payroll records, salary ledgers, leave ledgers, reporting relationships, or authority-specific employee assignments.

**SOURCE TRUTH → DISPOSITION TRUTH → MACHINE STAGING → CANONICAL ORGANISATION**
