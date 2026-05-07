package zas.admin.zia.translation.service.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

@Service
public class PdfStorageService {

    private final Path storagePath;

    public PdfStorageService(@Value("${zia.translation.pdf.storage-path}") String storagePath) {
        this.storagePath = Path.of(storagePath);
    }

    public void store(String jobId, byte[] content) throws IOException {
        Files.createDirectories(storagePath);
        Files.write(resolvePath(jobId), content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    public Optional<Resource> load(String jobId) {
        Path path = resolvePath(jobId);
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new UrlResource(path.toUri()));
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    public void delete(String jobId) {
        try {
            Files.deleteIfExists(resolvePath(jobId));
        } catch (IOException ignored) {
            // best-effort cleanup
        }
    }

    public boolean exists(String jobId) {
        return Files.exists(resolvePath(jobId));
    }

    private Path resolvePath(String jobId) {
        return storagePath.resolve(jobId + ".pdf");
    }
}
