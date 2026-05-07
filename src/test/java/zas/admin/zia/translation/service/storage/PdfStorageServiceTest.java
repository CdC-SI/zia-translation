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

        service.store("job-1", pdfContent);

        assertThat(service.exists("job-1")).isTrue();
        assertThat(service.load("job-1")).isPresent();

        service.delete("job-1");

        assertThat(service.exists("job-1")).isFalse();
        assertThat(service.load("job-1")).isEmpty();
    }
}
