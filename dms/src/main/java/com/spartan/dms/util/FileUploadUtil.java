package com.spartan.dms.util;

import com.spartan.dms.exception.FileStorageException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;

@Component
public class FileUploadUtil {

    /**
     * Root folder on disk. Everything is stored under here, one subfolder
     * per feature. Bug fix: this used to be the bare relative string
     * "uploads", which resolves against the JVM's current working
     * directory — every time this project got re-extracted into a new
     * folder, that root silently moved with it, orphaning every file
     * uploaded before the move (see the app.upload-dir comment in
     * application.properties). Now backed by an absolute, configured path
     * that stays put across moves.
     */
    // The ":${user.home}/brisk-dms-uploads" fallback here is deliberate and
    // NOT redundant with the same default in application.properties: if the
    // running app's target/classes/application.properties is ever stale
    // (missing the app.upload-dir line -- e.g. IntelliJ hot-restarted the
    // .java changes without re-running Maven's resource-copy step), Spring
    // would otherwise fail to start with "Could not resolve placeholder
    // 'app.upload-dir'". Baking the same default straight into the
    // annotation means the app still boots correctly even from a stale
    // build.
    @Value("${app.upload-dir:${user.home}/brisk-dms-uploads}")
    private String uploadRoot;

    /**
     * Result of a successful upload: everything a caller needs to persist a
     * DB record and build a browser-accessible URL, without ever having to
     * re-derive path/naming logic itself.
     */
    public record StoredFile(String storedFileName, String relativePath, String publicUrl) {
    }

    /**
     * Validates and writes a MultipartFile to disk under
     * {@code uploads/<subDirectory>/}, using a collision-proof generated
     * file name (original name is preserved for display, not for storage).
     *
     * @param file           the uploaded file
     * @param subDirectory   e.g. "paymentproofs", "products", "companylogo"
     * @param allowedTypes   whitelist of acceptable MIME types (content-type
     *                       sniffing is left to the browser/client, but this
     *                       still blocks the obvious cases); pass null/empty
     *                       to allow any type
     * @param maxSizeBytes   maximum allowed size in bytes; pass null for no
     *                       extra limit beyond the global multipart config
     */
    public StoredFile uploadFile(MultipartFile file,
                                 String subDirectory,
                                 List<String> allowedTypes,
                                 Long maxSizeBytes) {

        if (file == null || file.isEmpty()) {
            throw new FileStorageException("No file was uploaded, or the file is empty.");
        }

        if (allowedTypes != null && !allowedTypes.isEmpty()) {
            String contentType = file.getContentType();
            if (contentType == null || allowedTypes.stream().noneMatch(contentType::equalsIgnoreCase)) {
                throw new FileStorageException(
                        "Unsupported file type '" + contentType + "'. Allowed types: " + allowedTypes);
            }
            // BUG-M4: file.getContentType() is just the client-supplied
            // header -- trivially spoofable. Sniff the file's actual magic
            // bytes and make sure they match what the declared content type
            // claims before accepting it.
            validateMagicBytes(file, contentType);
        }

        if (maxSizeBytes != null && file.getSize() > maxSizeBytes) {
            throw new FileStorageException(
                    "File is too large (" + (file.getSize() / 1024) + " KB). "
                            + "Maximum allowed size is " + (maxSizeBytes / 1024) + " KB.");
        }

        String originalName = sanitize(file.getOriginalFilename());
        String storedFileName = UUID.randomUUID() + "_" + originalName;

        try {
            Path uploadPath = Paths.get(uploadRoot, subDirectory);
            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath);
            }

            Path targetPath = uploadPath.resolve(storedFileName);
            Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);

            String relativePath = subDirectory + "/" + storedFileName;
            String publicUrl = "/uploads/" + relativePath;

            return new StoredFile(storedFileName, relativePath, publicUrl);

        } catch (IOException e) {
            throw new FileStorageException("Failed to store file '" + originalName + "': " + e.getMessage());
        }
    }

    /** Backward-compatible overload: no validation, stored directly under uploads/. */
    public String uploadFile(MultipartFile file) throws IOException {
        try {
            return uploadFile(file, "", null, null).storedFileName();
        } catch (FileStorageException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    public void deleteFile(String relativePath) throws IOException {
        Path filePath = Paths.get(uploadRoot).resolve(relativePath);
        Files.deleteIfExists(filePath);
    }

    public Path getFile(String relativePath) {
        return Paths.get(uploadRoot).resolve(relativePath);
    }

    /**
     * BUG-M4: real magic-byte sniffing for the whitelist this app actually
     * uses (image/jpeg, image/jpg, image/png, image/webp, application/pdf).
     * No new dependency (e.g. Apache Tika) is added for this -- it's not
     * already on the classpath, and this whitelist is small and stable
     * enough that a handful of manual signature checks is the smaller,
     * lower-risk change.
     */
    private void validateMagicBytes(MultipartFile file, String declaredContentType) {

        byte[] header;
        try (java.io.InputStream in = file.getInputStream()) {
            header = in.readNBytes(12);
        } catch (IOException e) {
            throw new FileStorageException("Could not read file to verify its type: " + e.getMessage());
        }

        if (!magicBytesMatch(header, declaredContentType)) {
            throw new FileStorageException(
                    "The uploaded file's actual content does not match its declared type ('"
                            + declaredContentType + "'). The file may be mislabeled or corrupted.");
        }
    }

    private boolean magicBytesMatch(byte[] header, String contentType) {

        if (contentType == null) {
            return false;
        }

        switch (contentType.toLowerCase()) {
            case "image/jpeg":
            case "image/jpg":
                // FF D8 FF
                return startsWith(header, 0xFF, 0xD8, 0xFF);
            case "image/png":
                // 89 50 4E 47 0D 0A 1A 0A
                return startsWith(header, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A);
            case "application/pdf":
                // ASCII "%PDF"
                return header.length >= 4
                        && header[0] == '%' && header[1] == 'P' && header[2] == 'D' && header[3] == 'F';
            case "image/webp":
                // ASCII "RIFF" at offset 0, ASCII "WEBP" at offset 8
                return header.length >= 12
                        && header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                        && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P';
            default:
                // No known signature for this content type -- nothing to
                // compare against here, so don't block it (the caller's own
                // allowedTypes whitelist already rejects anything not on it).
                return true;
        }
    }

    private boolean startsWith(byte[] header, int... expectedBytes) {
        if (header.length < expectedBytes.length) {
            return false;
        }
        for (int i = 0; i < expectedBytes.length; i++) {
            if ((header[i] & 0xFF) != expectedBytes[i]) {
                return false;
            }
        }
        return true;
    }

    private String sanitize(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "file";
        }
        // Strip any path components a malicious client might smuggle in
        // (e.g. "../../etc/passwd") and keep only a safe character set.
        String name = Paths.get(fileName).getFileName().toString();
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}