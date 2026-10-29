# YMBJI Federation Upload — Operator Guide

This guide is for **YMBJI Ministry Superadmins** who register CA data
(salary notches, allowance rates, statutory rules, etc.) using the platform UI.

## What this does

The YMBJI Platform allows the Ministry to register a new CA year without
requiring developer involvement. You upload CSV/JSON files, the system validates
them against existing data, shows warnings (like rate drift or missing records),
and writes them atomically to the database.

## How to access

1. Open **http://localhost:8080/platform/login.html** (or `https://ymbji.com` in production).
2. Enter your YMBJI Superadmin username and password.
3. If MFA is enabled for your account, enter the 6-digit code when prompted.
4. You will land on the **Federation Data Layer** preview page.

## Registering a CA Year (The Workflow)

### 1. CSV Uploads (Notches, Allowances, Hardship)

On the main page:
1. Select the **Data Type** (e.g., "Notch values").
2. Pick the **Effective From** date (usually `YYYY-01-01`).
3. Click **Choose File** and select your prepared CSV.
4. Click **Validate & Preview**.
5. Review the warnings table (see "Interpreting Warnings" below).
6. If there are no blocking ERRORs, check the acknowledgment box (if required).
7. Click **Confirm & Write to Database**.

### 2. JSON Uploads (Statutory Rules, Events, Users)

For these structured data types, use the Swagger UI:
1. Go to **http://localhost:8080/swagger-ui/index.html**.
2. Click **Authorize** and paste your JWT (found in browser Local Storage under `erp_token` after logging in).
3. Use the `/federation/rates/statutory-rules`, `/federation/events`, or `/federation/users` endpoints.

## Interpreting Warnings

**ERRORs** block the upload. You must fix the CSV and re-upload.
**WARNINGs** require you to acknowledge them (check the box) before confirming.

| Code | Severity | Meaning |
|---|---|---|
| `MISSING_ALLOWANCE` | WARNING | A prior-year allowance is missing from this upload. Did the CA abolish it? |
| `RATE_DRIFT` | WARNING | Rate changed >30% YoY (e.g., RISK 2%→5%). Verify this matches the circular. |
| `CLASSIFICATION_CHANGE` | WARNING | A council moved between NONE/RURAL/REMOTE. Verify with the CA. |
| `INVALID_RATE` | ERROR | Rate must be a decimal (e.g., `0.20`, not `20`). |
| `UNKNOWN_ALLOWANCE_CODE` | ERROR | The code doesn't exist in the DB. (New codes require a schema migration.) |
| `PAYE_BANDS_MALFORMED` | ERROR | The JSON bands are not contiguous. |

## Troubleshooting

**"403: Caller is not a superadmin"**
You logged in with a council user (e.g., from `chilanga.ymbji.com`). The
federation UI requires the `admin.superadmin` role on the root `ymbji.com` domain.

**"Confirm failed: Upload session not found or expired"**
Previews expire after 30 minutes. Re-upload the file to generate a new session.
