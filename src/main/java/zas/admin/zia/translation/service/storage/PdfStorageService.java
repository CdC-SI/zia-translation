package zas.admin.zia.translation.service.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.UUID;

@Service
public class PdfStorageService {

    private static final Logger log = LoggerFactory.getLogger(PdfStorageService.class);
    private final Path storagePath;

    public PdfStorageService(@Value("${zia.translation.pdf.storage-path}") String storagePath) {
        this.storagePath = Path.of(storagePath);
    }

    public void store(String jobId, byte[] content) throws IOException {
        Files.createDirectories(storagePath);
        Files.write(resolvePath(jobId), content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    public Optional<Resource> load(String jobId) {
        Path path = resolvePathSafely(jobId).orElse(null);
        if (path == null) {
            return Optional.empty();
        }
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new UrlResource(path.toUri()));
        } catch (IOException exception) {
            log.warn("Failed to load translated PDF for job {}", jobId, exception);
            return Optional.empty();
        }
    }

    public void delete(String jobId) {
        Path path = resolvePathSafely(jobId).orElse(null);
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best-effort cleanup
        }
    }

    public boolean exists(String jobId) {
        return resolvePathSafely(jobId).map(Files::exists).orElse(false);
    }

    private Path resolvePath(String jobId) {
        String safeJobId = UUID.fromString(jobId).toString();
        Path normalizedBase = storagePath.toAbsolutePath().normalize();
        Path candidate = normalizedBase.resolve(safeJobId + ".pdf").normalize();
        if (!candidate.startsWith(normalizedBase)) {
            throw new IllegalArgumentException("Invalid job identifier");
        }
        return candidate;
    }

    private Optional<Path> resolvePathSafely(String jobId) {
        try {
            return Optional.of(resolvePath(jobId));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }
}
