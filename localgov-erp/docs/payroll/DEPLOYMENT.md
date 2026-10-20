# Payroll Deployment Notes

## Object storage for payslip PDFs

Payslip PDFs are written to `payroll.pdf.object-store-root`. The value
differs per profile:

| Profile | Value | Purpose |
|---|---|---|
| default (`application.yml`) | `./var/payslips` | dev / local — relative to working directory |
| `prod` (`application-prod.yml`) | `/var/lib/localgov-erp/payslips` | absolute, durable, backed up |

### Requirements for the production path

The directory must be:

- Writable by the application service user
- Durable — not under `/tmp` or a tmpfs
- Backed up — payslips are legally required audit records
- Monitored — a full disk will cause payroll runs to fail

### Provisioning (Linux)

    sudo mkdir -p /var/lib/localgov-erp/payslips
    sudo chown <app-user>:<app-group> /var/lib/localgov-erp/payslips
    sudo chmod 750 /var/lib/localgov-erp/payslips

Where `<app-user>` is whatever user the Spring Boot service runs as
(e.g. `localgov`, `tomcat`, `app`). Never run payroll as root.

### Backing up

The filesystem holds `payslips.pdf_object_key` — one PDF per payslip
row. Backing up the database alone is insufficient; the PDFs are part
of the audit record. Back up both:

- The PostgreSQL `payslips` table (via `pg_dump`)
- The `payroll.pdf.object-store-root` directory (rsync, borg, restic, etc.)

A payslip whose PDF is missing but whose `payslips` row exists is an
integrity gap — auditors will flag it.

### Migrating to S3 / MinIO (future)

`FilesystemObjectStorage` implements `ObjectStorage`. Adding S3 is a
new class (`S3ObjectStorage`) + config-driven bean selection. The
`payroll.pdf.object-store-root` property becomes `payroll.pdf.bucket-name`
and `payroll.pdf.region`. No change to the payroll engine or renderer.

## Running with the prod profile

    SPRING_PROFILES_ACTIVE=prod java -jar app.jar
    # or
    java -jar app.jar --spring.profiles.active=prod

Without the prod profile, the dev default `./var/payslips` is used —
do NOT deploy to production without activating `prod`, or PDFs will
land in the working directory.
