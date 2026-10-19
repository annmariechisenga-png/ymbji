package com.localgov.service.payroll.storage;

/**
 * Minimal blob storage abstraction for payslip PDFs.
 *
 * <p>Keys are forward-slash-separated relative paths, e.g.
 * {@code CHILANGA/RUN-2026-10-xxxx/EMP-001.pdf}.
 */
public interface ObjectStorage {

    /** Store the content at the given key. Overwrites if present. */
    String put(String key, byte[] content);

    /** Retrieve bytes for the key, or {@code null} if absent. */
    byte[] get(String key);
}
