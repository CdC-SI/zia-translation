package zas.admin.zia.translation.service.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PdfStorageServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void storeLoadExistsAndDelete_workAsExpected() throws IOException {
        PdfStorageService service = new PdfStorageService(tempDir.toString());
        byte[] pdfContent = "%PDF-1.4".getBytes();

        String jobId = "123e4567-e89b-12d3-a456-426614174000";

        service.store(jobId, pdfContent);

        assertThat(service.exists(jobId)).isTrue();
        assertThat(service.load(jobId)).isPresent();

        service.delete(jobId);

        assertThat(service.exists(jobId)).isFalse();
        assertThat(service.load(jobId)).isEmpty();
    }
}
