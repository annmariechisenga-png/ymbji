package com.localgov.web.federation.store;

import com.localgov.web.federation.dto.NotchValuePreview;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory store for upload previews awaiting confirmation.
 *
 * <p>Lifetime: 30 minutes from upload. Previews older than that are
 * discarded on the next access. Operator must re-upload if expired.
 *
 * <p><b>Single-instance design.</b> If the application is deployed
 * behind a load balancer with multiple instances, the upload (POST) and
 * confirm (POST /confirm) calls could land on different instances and
 * the preview would be missing. In that case, migrate this store to the
 * database (a {@code federation_upload_pending} table with TTL cleanup).
 * Currently the application runs single-instance; this constraint is
 * documented for future scale-out.
 */
@Component
public class UploadSessionStore {

    private static final long TTL_MINUTES = 30;

    private final ConcurrentMap<UUID, NotchValuePreview> previews = new ConcurrentHashMap<>();

    public UUID save(NotchValuePreview preview) {
        previews.put(preview.uploadId(), preview);
        return preview.uploadId();
    }

    public Optional<NotchValuePreview> get(UUID uploadId) {
        NotchValuePreview preview = previews.get(uploadId);
        if (preview == null) return Optional.empty();
        if (preview.uploadedAt().isBefore(LocalDateTime.now().minusMinutes(TTL_MINUTES))) {
            previews.remove(uploadId);
            return Optional.empty();
        }
        return Optional.of(preview);
    }

    public void remove(UUID uploadId) {
        previews.remove(uploadId);
    }

    /** Visible for tests — clear all previews. */
    public void clear() {
        previews.clear();
    }
}
