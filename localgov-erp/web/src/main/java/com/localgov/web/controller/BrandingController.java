package com.localgov.web.controller;

import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/platform")
public class BrandingController {

    private final JdbcTemplate jdbc;

    public BrandingController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/branding")
    public ResponseEntity<Map<String, Object>> getBranding(
            HttpServletRequest request,
            @RequestParam(required = false) String authority) {

        List<Map<String, Object>> rows;

        if (authority != null && !authority.isEmpty()) {
            rows = jdbc.queryForList(
                "SELECT authority_code, subdomain, display_name, primary_color, accent_color, logo_url " +
                "FROM tenant_branding WHERE authority_code = ?", authority);
        } else {
            String host = request.getServerName();
            String subdomain = host.split("\\.")[0];
            rows = jdbc.queryForList(
                "SELECT authority_code, subdomain, display_name, primary_color, accent_color, logo_url " +
                "FROM tenant_branding WHERE subdomain = ?", subdomain);

            if (rows.isEmpty()) {
                rows = jdbc.queryForList(
                    "SELECT authority_code, subdomain, display_name, primary_color, accent_color, logo_url " +
                    "FROM tenant_branding WHERE authority_code = 'ZM-09-01'");
            }
        }

        if (rows.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> row = rows.get(0);
        String baseUrl = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
        row.put("logo_url", baseUrl + row.get("logo_url"));
        return ResponseEntity.ok(row);
    }

    @GetMapping("/councils")
    public ResponseEntity<List<Map<String, Object>>> listCouncils() {
        List<Map<String, Object>> councils = jdbc.queryForList(
            "SELECT authority_code, display_name, subdomain FROM tenant_branding ORDER BY display_name");
        return ResponseEntity.ok(councils);
    }

    @PostMapping("/branding/logo")
    @PreAuthorize("hasRole('SUPERADMIN')")
    public ResponseEntity<?> uploadLogo(
            @RequestParam("authorityCode") String authorityCode,
            @RequestParam("file") MultipartFile file) {
        try {
            String uploadDir = "storage/tenant_logos/";
            Path uploadPath = Paths.get(uploadDir);
            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath);
            }

            String originalFilename = file.getOriginalFilename();
            String extension = originalFilename != null && originalFilename.contains(".")
                ? originalFilename.substring(originalFilename.lastIndexOf("."))
                : ".png";
            String safeFilename = authorityCode + "_" + UUID.randomUUID().toString().substring(0, 8) + extension;

            Path filePath = uploadPath.resolve(safeFilename);
            Files.copy(file.getInputStream(), filePath);

            String logoUrl = "/platform/branding/logo/" + safeFilename;
            jdbc.update("UPDATE tenant_branding SET logo_url = ? WHERE authority_code = ?",
                logoUrl, authorityCode);

            return ResponseEntity.ok(Map.of("success", true, "logo_url", logoUrl));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to save file: " + e.getMessage()));
        }
    }

    @GetMapping("/branding/logo/{filename}")
    public ResponseEntity<Resource> getLogo(@PathVariable String filename) {
        try {
            Path filePath = Paths.get("storage/tenant_logos/").resolve(filename).normalize();
            Resource resource = new UrlResource(filePath.toUri());
            if (resource.exists() && resource.isReadable()) {
                return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_PNG)
                    .body(resource);
            }
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }
}
