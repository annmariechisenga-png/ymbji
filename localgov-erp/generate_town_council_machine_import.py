#!/usr/bin/env python3

"""
ymbji — Town Council Final Machine Import Generator

SOURCE:
    ymbji_town_council_final_staging_disposition_matrix.xlsx

AUTHORITY:
    Final Town Council Staging Disposition Matrix

CONTRACT:
    546 source disposition rows
    542 IMPORT
      4 EXCLUDE
      0 REJECT

IMPORTANT:
    This script is a transformer, NOT a classifier.

    It must:
      - preserve the approved disposition
      - preserve attachment decisions
      - preserve G1/G2/G3 distinctions
      - normalize only approved formatting variants
      - never invent salary scales
      - never infer attachment
      - never import zero-establishment rows
      - never silently repair bad source data
      - never invent authority codes
"""

from __future__ import annotations

import csv
import re
import sys
from collections import Counter
from pathlib import Path

from openpyxl import load_workbook


# ============================================================
# CONFIGURATION
# ============================================================

INPUT_FILE = Path(
    "ymbji_town_council_final_staging_disposition_matrix.xlsx"
)

OUTPUT_CSV = Path(
    "town_council_machine_import.csv"
)

OUTPUT_REPORT = Path(
    "town_council_pre_import_validation.md"
)

SHEET_NAME = "Disposition Matrix"

EXPECTED_ROWS = 546
EXPECTED_IMPORTS = 542
EXPECTED_EXCLUDES = 4
EXPECTED_REJECTIONS = 0


# ============================================================
# SOURCE MATRIX COLUMNS
# ============================================================

SRC = {
    "id": "Disposition ID",
    "department": "Canonical Department",
    "unit": "Canonical Unit / Section",
    "position": "Position",
    "salary": "Salary Scale",
    "establishment": "Establishment",
    "attached": "Attached?",
    "import": "Import?",
    "authority": "Authority",
    "location": "Location / Facility",
    "applicability": "Applicability",
    "disposition": "Disposition",
    "reason": "Disposition Reason",
    "source_line": "Source Line",
}


# ============================================================
# MACHINE OUTPUT COLUMNS
# ============================================================

MACHINE_FIELDS = [
    "source_reference",
    "source_node_type",
    "canonical_department",
    "canonical_unit",
    "position_title",
    "salary_scale",
    "establishment_quantity",
    "is_established",
    "is_frozen",
    "is_attached",
    "is_active",
    "source_authority",
    "authority_code",
    "location",
    "applicability",
    "disposition",
    "source_line",
]


# ============================================================
# CONTROLLED VALUES
# ============================================================

VALID_APPLICABILITY = {
    "NATIONAL REFERENCE",
    "LOCATION-SPECIFIC TEMPLATE",
    "APPLICABLE",
    "REGIONAL / AUTHORITY NOT SPECIFIED",
}

REVIEW_SCALES = {
    "LGSS",
    "(SOURCE SCALE)",
}

LOCATION_NODE_APPLICABILITY = {
    "APPLICABLE",
    "LOCATION-SPECIFIC TEMPLATE",
    "REGIONAL / AUTHORITY NOT SPECIFIED",
}


# ============================================================
# HELPERS
# ============================================================

def fail(message: str) -> None:
    print(f"\nERROR: {message}\n", file=sys.stderr)
    sys.exit(1)


def clean(value) -> str:
    if value is None:
        return ""

    return str(value).strip()


def normalize_scale(value: str) -> str:
    """
    Only approved formatting normalization is performed.

    LGSS07  -> LGSS/07
    LGSS/07 -> LGSS/07

    No substantive scale interpretation occurs.
    """

    value = clean(value)

    if re.fullmatch(r"LGSS\d{2}", value, flags=re.IGNORECASE):
        return f"LGSS/{value[-2:]}"

    return value


def parse_establishment(value, disposition_id: str, position: str):
    """
    Returns:
        (integer_value, error_message)

    The source matrix is expected to contain numeric establishments.
    """

    if value is None or clean(value) == "":
        return None, "missing establishment"

    try:
        numeric = float(value)
    except (TypeError, ValueError):
        return None, f"non-numeric establishment: {value!r}"

    if not numeric.is_integer():
        return None, f"non-integer establishment: {value!r}"

    return int(numeric), None


def parse_yes_no(value, field_name: str):
    value = clean(value).upper()

    if value == "YES":
        return True, None

    if value == "NO":
        return False, None

    return None, f"{field_name} must be Yes or No; received {value!r}"


def classify_node_type(
    applicability: str,
    location: str,
    authority: str,
) -> str:
    """
    Node type is derived from the approved applicability category.

    NATIONAL REFERENCE:
        POSITION

    APPLICABLE:
        LOCATION_SPECIFIC_POSITION

    LOCATION-SPECIFIC TEMPLATE:
        LOCATION_SPECIFIC_POSITION

    REGIONAL / AUTHORITY NOT SPECIFIED:
        LOCATION_SPECIFIC_POSITION

    This does NOT determine attachment.
    """

    app = clean(applicability).upper()

    if app in LOCATION_NODE_APPLICABILITY:
        return "LOCATION_SPECIFIC_POSITION"

    if app == "NATIONAL REFERENCE":
        return "POSITION"

    # Unknown applicability is handled as a validation error.
    return "UNKNOWN"


def authority_looks_like_code(value: str) -> bool:
    """
    Authority codes must come from the authoritative authority registry.

    We intentionally do NOT generate codes from names.

    This function only recognises an already-supplied code if the
    matrix happens to contain one.
    """

    value = clean(value)

    if not value:
        return False

    return bool(re.fullmatch(r"[A-Z0-9_-]{2,12}", value))


# ============================================================
# LOAD MATRIX
# ============================================================

def load_matrix():

    if not INPUT_FILE.exists():
        fail(
            f"Input file not found: {INPUT_FILE.resolve()}"
        )

    wb = load_workbook(
        INPUT_FILE,
        read_only=True,
        data_only=True,
    )

    # FAIL CLOSED.
    if SHEET_NAME not in wb.sheetnames:
        fail(
            f"Required worksheet '{SHEET_NAME}' was not found. "
            f"Available sheets: {wb.sheetnames}"
        )

    ws = wb[SHEET_NAME]

    rows = ws.iter_rows(values_only=True)

    try:
        header_row = next(rows)
    except StopIteration:
        fail("Worksheet is empty.")

    header = [clean(value) for value in header_row]

    expected_headers = set(SRC.values())
    actual_headers = set(header)

    missing = expected_headers - actual_headers

    if missing:
        fail(
            "Required matrix headers are missing:\n"
            + "\n".join(f"  - {item}" for item in sorted(missing))
        )

    index = {
        column: header.index(column)
        for column in expected_headers
    }

    matrix = []

    for row_number, row in enumerate(rows, start=2):

        # Ignore completely blank rows.
        if all(clean(value) == "" for value in row):
            continue

        record = {
            key: clean(row[index[column]])
            for key, column in SRC.items()
        }

        record["_excel_row"] = row_number

        matrix.append(record)

    return matrix


# ============================================================
# TRANSFORM
# ============================================================

def transform(matrix):

    imports = []
    exclusions = []
    rejections = []

    # Independent validation buckets.
    structural_errors = []
    establishment_errors = []
    state_errors = []
    applicability_errors = []
    disposition_errors = []
    authority_warnings = []
    salary_review = []

    seen_source_refs = set()
    duplicate_source_refs = []

    for row in matrix:

        did = clean(row["id"])
        department = clean(row["department"])
        unit = clean(row["unit"])
        position = clean(row["position"])
        salary = normalize_scale(row["salary"])
        raw_establishment = row["establishment"]
        raw_attached = row["attached"]
        raw_import = clean(row["import"]).upper()
        authority = clean(row["authority"])
        location = clean(row["location"])
        applicability = clean(row["applicability"]).upper()
        disposition = clean(row["disposition"]).upper()
        source_line = clean(row["source_line"])

        # ----------------------------------------------------
        # SOURCE REFERENCE
        # ----------------------------------------------------

        if not did:
            structural_errors.append(
                "Missing Disposition ID."
            )
            rejections.append(
                ("UNKNOWN", position, "missing Disposition ID")
            )
            continue

        source_reference = did

        if source_reference in seen_source_refs:
            duplicate_source_refs.append(source_reference)

        seen_source_refs.add(source_reference)

        # ----------------------------------------------------
        # STRUCTURAL VALIDATION
        # ----------------------------------------------------

        if not department:
            structural_errors.append(
                f"{did}: missing canonical department"
            )

        if not position:
            structural_errors.append(
                f"{did}: missing position"
            )

        if not salary:
            structural_errors.append(
                f"{did}: missing salary scale"
            )

        if not disposition:
            disposition_errors.append(
                f"{did}: missing disposition"
            )

        # ----------------------------------------------------
        # DISPOSITION VALIDATION
        # ----------------------------------------------------

        if disposition not in {"IMPORT", "EXCLUDE"}:
            disposition_errors.append(
                f"{did}: invalid disposition '{disposition}'"
            )

        if raw_import not in {"YES", "NO", "EXCLUDE"}:
            state_errors.append(
                f"{did}: Import? must be Yes, No, or Exclude; got '{raw_import}'"
            )

        # ----------------------------------------------------
        # ESTABLISHMENT VALIDATION
        # ----------------------------------------------------

        est, est_error = parse_establishment(
            raw_establishment,
            did,
            position,
        )

        if est_error:
            establishment_errors.append(
                f"{did}: {est_error}"
            )

        # ----------------------------------------------------
        # ATTACHMENT VALIDATION
        # ----------------------------------------------------

        attached, attached_error = parse_yes_no(
            raw_attached,
            "Attached?",
        )

        if attached_error:
            state_errors.append(
                f"{did}: {attached_error}"
            )

        # ----------------------------------------------------
        # APPLICABILITY VALIDATION
        # ----------------------------------------------------

        if applicability not in VALID_APPLICABILITY:
            applicability_errors.append(
                f"{did}: unknown applicability '{applicability}'"
            )

        if applicability == "APPLICABLE":

            if not authority:
                applicability_errors.append(
                    f"{did}: APPLICABLE row has no authority"
                )

            if not location:
                applicability_errors.append(
                    f"{did}: APPLICABLE row has no location"
                )

        if applicability == "REGIONAL / AUTHORITY NOT SPECIFIED":

            # This is an approved source category.
            # It remains unresolved for authority instantiation.
            authority_warnings.append(
                f"{did}: regional source row has no authority-specific "
                f"instantiation"
            )

        # ----------------------------------------------------
        # NODE TYPE
        # ----------------------------------------------------

        node_type = classify_node_type(
            applicability,
            location,
            authority,
        )

        if node_type == "UNKNOWN":
            applicability_errors.append(
                f"{did}: unable to classify node type"
            )

        # ----------------------------------------------------
        # SALARY REVIEW
        # ----------------------------------------------------

        if salary.upper() in REVIEW_SCALES:
            salary_review.append(
                f"{did}: '{salary}' preserved exactly; "
                f"downstream salary/rate resolution required."
            )

        # ----------------------------------------------------
        # IMPORT / EXCLUDE CONTRACT
        # ----------------------------------------------------

        if (
            raw_import == "NO"
            or disposition == "EXCLUDE"
        ):
            exclusions.append({
                "source_reference": source_reference,
                "position": position,
                "establishment": est,
                "reason": row["reason"],
            })

            continue

        # ----------------------------------------------------
        # IMPORT ROWS MUST HAVE POSITIVE ESTABLISHMENT
        # ----------------------------------------------------

        if est is None:
            rejections.append(
                (
                    did,
                    position,
                    "IMPORT row has invalid establishment",
                )
            )
            continue

        if est <= 0:
            rejections.append(
                (
                    did,
                    position,
                    f"IMPORT row has non-positive establishment: {est}",
                )
            )
            continue

        # ----------------------------------------------------
        # IMPORT ROWS MUST HAVE VALID STATE
        # ----------------------------------------------------

        if (
            not department
            or not position
            or not salary
            or attached is None
            or node_type == "UNKNOWN"
        ):
            rejections.append(
                (
                    did,
                    position,
                    "required transformation field is invalid",
                )
            )
            continue

        # ----------------------------------------------------
        # AUTHORITY CODE
        # ----------------------------------------------------

        """
        IMPORTANT:

        The matrix contains authority names such as:

            Kasama Municipal Council
            Katete Town Council
            Livingstone City Council

        These are NOT automatically authority codes.

        Therefore:
            source_authority = source truth
            authority_code   = only populated if an actual code
                               was supplied by the matrix.

        We do NOT derive:
            Kasama Municipal Council -> KAS
            Chilanga Town Council -> CHL
            etc.

        That belongs to the authoritative authority registry.
        """

        authority_code = ""

        if authority and authority_looks_like_code(authority):
            authority_code = authority

        # ----------------------------------------------------
        # BUILD MACHINE RECORD
        # ----------------------------------------------------

        record = {
            "source_reference": source_reference,
            "source_node_type": node_type,
            "canonical_department": department,
            "canonical_unit": unit,
            "position_title": position,
            "salary_scale": salary,
            "establishment_quantity": est,
            "is_established": True,
            "is_frozen": False,
            "is_attached": attached,
            "is_active": True,
            "source_authority": authority,
            "authority_code": authority_code,
            "location": location,
            "applicability": applicability,
            "disposition": "IMPORT",
            "source_line": source_line,
        }

        imports.append(record)

    # --------------------------------------------------------
    # CONSOLIDATE VALIDATION
    # --------------------------------------------------------

    for message in structural_errors:
        rejections.append(
            ("STRUCTURAL", "", message)
        )

    for message in establishment_errors:
        rejections.append(
            ("ESTABLISHMENT", "", message)
        )

    for message in state_errors:
        rejections.append(
            ("STATE", "", message)
        )

    for message in applicability_errors:
        rejections.append(
            ("APPLICABILITY", "", message)
        )

    for message in disposition_errors:
        rejections.append(
            ("DISPOSITION", "", message)
        )

    return {
        "imports": imports,
        "exclusions": exclusions,
        "rejections": rejections,
        "structural_errors": structural_errors,
        "establishment_errors": establishment_errors,
        "state_errors": state_errors,
        "applicability_errors": applicability_errors,
        "disposition_errors": disposition_errors,
        "authority_warnings": authority_warnings,
        "salary_review": salary_review,
        "duplicate_source_refs": duplicate_source_refs,
    }


# ============================================================
# CSV OUTPUT
# ============================================================

def write_csv(imports):

    with OUTPUT_CSV.open(
        "w",
        newline="",
        encoding="utf-8",
    ) as fh:

        writer = csv.DictWriter(
            fh,
            fieldnames=MACHINE_FIELDS,
        )

        writer.writeheader()

        for record in imports:
            writer.writerow(record)


# ============================================================
# REPORT
# ============================================================

def gate(status: bool) -> str:
    return "PASS" if status else "FAIL"


def write_report(matrix, result):

    imports = result["imports"]
    exclusions = result["exclusions"]
    rejections = result["rejections"]

    # --------------------------------------------------------
    # INDEPENDENT GATES
    # --------------------------------------------------------

    gate_1 = len(matrix) == EXPECTED_ROWS

    gate_2 = (
        len(imports) == EXPECTED_IMPORTS
        and len(exclusions) == EXPECTED_EXCLUDES
    )

    gate_3 = (
        len(rejections) == EXPECTED_REJECTIONS
        and not result["duplicate_source_refs"]
    )

    gate_4 = (
        not result["structural_errors"]
        and not result["establishment_errors"]
        and not result["state_errors"]
        and not result["applicability_errors"]
        and not result["disposition_errors"]
    )

    gate_5 = all(
        record["disposition"] == "IMPORT"
        and record["establishment_quantity"] > 0
        and record["is_established"] is True
        and record["is_active"] is True
        and record["is_frozen"] is False
        for record in imports
    )

    # --------------------------------------------------------
    # COUNTS
    # --------------------------------------------------------

    applicability_counts = Counter(
        record["applicability"]
        for record in imports
    )

    node_type_counts = Counter(
        record["source_node_type"]
        for record in imports
    )

    attached_counts = Counter(
        record["is_attached"]
        for record in imports
    )

    department_counts = Counter(
        record["canonical_department"]
        for record in imports
    )

    salary_counts = Counter(
        record["salary_scale"]
        for record in imports
    )

    # --------------------------------------------------------
    # REPORT
    # --------------------------------------------------------

    with OUTPUT_REPORT.open(
        "w",
        encoding="utf-8",
    ) as fh:

        fh.write(
            "# ymbji — Town Council Pre-Import Validation Report\n\n"
        )

        fh.write(
            "## 1. Authority\n\n"
            "This report was generated directly from the approved "
            "Town Council Final Staging Disposition Matrix.\n\n"
        )

        fh.write(
            "The importer acts only as a transformer. It does not "
            "reclassify positions, infer attachment, invent salary "
            "scales, or invent authority codes.\n\n"
        )

        fh.write("## 2. Final Control Totals\n\n")

        fh.write("| Control | Expected | Actual | Status |\n")
        fh.write("|---|---:|---:|---|\n")
        fh.write(
            f"| Source disposition rows | {EXPECTED_ROWS} | "
            f"{len(matrix)} | {gate(len(matrix) == EXPECTED_ROWS)} |\n"
        )
        fh.write(
            f"| Import rows | {EXPECTED_IMPORTS} | "
            f"{len(imports)} | {gate(len(imports) == EXPECTED_IMPORTS)} |\n"
        )
        fh.write(
            f"| Exclude rows | {EXPECTED_EXCLUDES} | "
            f"{len(exclusions)} | {gate(len(exclusions) == EXPECTED_EXCLUDES)} |\n"
        )
        fh.write(
            f"| Rejections | {EXPECTED_REJECTIONS} | "
            f"{len(rejections)} | {gate(len(rejections) == EXPECTED_REJECTIONS)} |\n"
        )

        fh.write("\n## 3. Validation Gates\n\n")

        fh.write("| Gate | Purpose | Status |\n")
        fh.write("|---|---|---|\n")
        fh.write(
            f"| Gate 1 | Matrix row count | {gate(gate_1)} |\n"
        )
        fh.write(
            f"| Gate 2 | Import / exclude control totals | {gate(gate_2)} |\n"
        )
        fh.write(
            f"| Gate 3 | Rejection and duplicate control | {gate(gate_3)} |\n"
        )
        fh.write(
            f"| Gate 4 | Structural/state/applicability validation | {gate(gate_4)} |\n"
        )
        fh.write(
            f"| Gate 5 | Imported record state validation | {gate(gate_5)} |\n"
        )

        all_gates = all([
            gate_1,
            gate_2,
            gate_3,
            gate_4,
            gate_5,
        ])

        fh.write("\n")
        fh.write(
            f"**FINAL MACHINE-STAGING STATUS: "
            f"{'PASS' if all_gates else 'FAIL'}**\n\n"
        )

        # ----------------------------------------------------
        # APPLICABILITY
        # ----------------------------------------------------

        fh.write("## 4. Applicability Distribution\n\n")
        fh.write("| Applicability | Count |\n")
        fh.write("|---|---:|\n")

        for key, count in sorted(applicability_counts.items()):
            fh.write(f"| {key} | {count} |\n")

        # ----------------------------------------------------
        # NODE TYPES
        # ----------------------------------------------------

        fh.write("\n## 5. Source Node Types\n\n")
        fh.write("| Node Type | Count |\n")
        fh.write("|---|---:|\n")

        for key, count in sorted(node_type_counts.items()):
            fh.write(f"| {key} | {count} |\n")

        # ----------------------------------------------------
        # ATTACHMENT
        # ----------------------------------------------------

        fh.write("\n## 6. Attachment State\n\n")
        fh.write("| Attached | Count |\n")
        fh.write("|---|---:|\n")

        fh.write(
            f"| Yes | {attached_counts.get(True, 0)} |\n"
        )
        fh.write(
            f"| No | {attached_counts.get(False, 0)} |\n"
        )

        fh.write(
            "\nAttachment values are copied from the approved matrix. "
            "They are not inferred from salary scale.\n"
        )

        # ----------------------------------------------------
        # DEPARTMENTS
        # ----------------------------------------------------

        fh.write("\n## 7. Imported Positions by Department\n\n")
        fh.write("| Department | Import Rows |\n")
        fh.write("|---|---:|\n")

        for key, count in sorted(department_counts.items()):
            fh.write(f"| {key} | {count} |\n")

        # ----------------------------------------------------
        # SALARY SCALES
        # ----------------------------------------------------

        fh.write("\n## 8. Salary Scale Distribution\n\n")
        fh.write("| Salary Scale | Count |\n")
        fh.write("|---|---:|\n")

        for key, count in sorted(salary_counts.items()):
            fh.write(f"| {key} | {count} |\n")

        # ----------------------------------------------------
        # REVIEW ITEMS
        # ----------------------------------------------------

        fh.write("\n## 9. Controlled Review Items\n\n")

        if result["salary_review"]:
            fh.write("### Incomplete / Placeholder Source Scales\n\n")

            for item in result["salary_review"]:
                fh.write(f"- {item}\n")

            fh.write(
                "\nThese values are deliberately preserved. "
                "The importer does not invent the missing scale.\n"
            )
        else:
            fh.write(
                "No incomplete or placeholder salary scales were detected.\n"
            )

        # ----------------------------------------------------
        # AUTHORITY RESOLUTION
        # ----------------------------------------------------

        fh.write("\n## 10. Authority Resolution\n\n")

        fh.write(
            "The source matrix contains authority names. "
            "The machine staging file therefore preserves these as "
            "`source_authority`.\n\n"
        )

        fh.write(
            "`authority_code` is populated only where an actual "
            "authority code is explicitly present. No code is generated "
            "from an authority name.\n\n"
        )

        fh.write(
            "Authority-name → authority-code resolution is a separate "
            "registry step and must occur before authority-specific "
            "canonical instantiation.\n"
        )

        if result["authority_warnings"]:
            fh.write("\n### Regional / Unspecified Authority Rows\n\n")

            for item in result["authority_warnings"]:
                fh.write(f"- {item}\n")

        # ----------------------------------------------------
        # EXCLUSIONS
        # ----------------------------------------------------

        fh.write("\n## 11. Approved Exclusions\n\n")

        fh.write(
            "These rows remain in the disposition matrix and are "
            "not emitted as canonical import records.\n\n"
        )

        fh.write("| Disposition ID | Position | Establishment | Reason |\n")
        fh.write("|---|---|---:|---|\n")

        for item in exclusions:
            fh.write(
                f"| {item['source_reference']} | "
                f"{item['position']} | "
                f"{item['establishment']} | "
                f"{item['reason']} |\n"
            )

        # ----------------------------------------------------
        # ERRORS
        # ----------------------------------------------------

        fh.write("\n## 12. Rejections / Errors\n\n")

        if rejections:
            fh.write(
                "The following issues prevent the staging artifact "
                "from being considered ready:\n\n"
            )

            for item in rejections:
                fh.write(
                    f"- `{item[0]}` — {item[1]} — {item[2]}\n"
                )
        else:
            fh.write(
                "None. No machine rejection occurred.\n"
            )

        # ----------------------------------------------------
        # DUPLICATES
        # ----------------------------------------------------

        fh.write("\n## 13. Duplicate Source References\n\n")

        if result["duplicate_source_refs"]:
            for ref in result["duplicate_source_refs"]:
                fh.write(f"- {ref}\n")
        else:
            fh.write(
                "None detected.\n"
            )

        # ----------------------------------------------------
        # FINAL CONTRACT
        # ----------------------------------------------------

        fh.write("\n## 14. Final Import Contract\n\n")

        fh.write(
            "A record may enter the machine import CSV only when:\n\n"
            "1. `Import? = Yes`.\n"
            "2. `Disposition = IMPORT`.\n"
            "3. Establishment is a positive integer.\n"
            "4. Required structural fields are present.\n"
            "5. Attachment state is explicitly supplied by the matrix.\n"
            "6. Applicability is a recognised controlled value.\n"
            "7. No transformation rejection exists for the record.\n\n"
        )

        fh.write(
            "The importer does not create employees, payroll records, "
            "salary ledgers, leave ledgers, reporting relationships, "
            "or authority-specific employee assignments.\n\n"
        )

        fh.write(
            "**SOURCE TRUTH → DISPOSITION TRUTH → MACHINE STAGING → "
            "CANONICAL ORGANISATION**\n"
        )


# ============================================================
# HARD FINAL ASSERTIONS
# ============================================================

def final_assertions(matrix, result):

    imports = result["imports"]
    exclusions = result["exclusions"]
    rejections = result["rejections"]

    failures = []

    if len(matrix) != EXPECTED_ROWS:
        failures.append(
            f"Expected {EXPECTED_ROWS} matrix rows; "
            f"found {len(matrix)}"
        )

    if len(imports) != EXPECTED_IMPORTS:
        failures.append(
            f"Expected {EXPECTED_IMPORTS} import records; "
            f"generated {len(imports)}"
        )

    if len(exclusions) != EXPECTED_EXCLUDES:
        failures.append(
            f"Expected {EXPECTED_EXCLUDES} exclusions; "
            f"found {len(exclusions)}"
        )

    if len(rejections) != EXPECTED_REJECTIONS:
        failures.append(
            f"Expected {EXPECTED_REJECTIONS} rejections; "
            f"found {len(rejections)}"
        )

    if result["duplicate_source_refs"]:
        failures.append(
            "Duplicate source references detected: "
            + ", ".join(result["duplicate_source_refs"])
        )

    # --------------------------------------------------------
    # Every import must obey the hard contract.
    # --------------------------------------------------------

    for record in imports:

        if record["disposition"] != "IMPORT":
            failures.append(
                f"{record['source_reference']}: "
                "non-IMPORT record emitted"
            )

        if record["establishment_quantity"] <= 0:
            failures.append(
                f"{record['source_reference']}: "
                "non-positive establishment emitted"
            )

        if record["is_established"] is not True:
            failures.append(
                f"{record['source_reference']}: "
                "is_established is not True"
            )

        if record["is_active"] is not True:
            failures.append(
                f"{record['source_reference']}: "
                "is_active is not True"
            )

        if record["is_frozen"] is not False:
            failures.append(
                f"{record['source_reference']}: "
                "is_frozen is not False"
            )

    if failures:

        print(
            "\nFINAL VALIDATION: FAILED\n",
            file=sys.stderr,
        )

        for failure in failures:
            print(
                f"  - {failure}",
                file=sys.stderr,
            )

        sys.exit(1)

    print("\nFINAL VALIDATION: PASS")
    print(f"  Matrix rows : {len(matrix)}")
    print(f"  Imports     : {len(imports)}")
    print(f"  Excludes    : {len(exclusions)}")
    print(f"  Rejections  : {len(rejections)}")
    print(f"  CSV         : {OUTPUT_CSV}")
    print(f"  Report      : {OUTPUT_REPORT}")


# ============================================================
# MAIN
# ============================================================

def main():

    print()
    print("ymbji — Town Council Machine Import Generator")
    print("=" * 55)
    print(f"Input : {INPUT_FILE}")
    print(f"Sheet : {SHEET_NAME}")
    print()

    matrix = load_matrix()

    print(f"Loaded matrix rows: {len(matrix)}")

    result = transform(matrix)

    write_csv(result["imports"])
    write_report(matrix, result)

    final_assertions(matrix, result)

    print()
    print("Artifacts generated successfully.")
    print()
    print(f"  {OUTPUT_CSV}")
    print(f"  {OUTPUT_REPORT}")
    print()


if __name__ == "__main__":
    main()
