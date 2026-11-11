package zas.admin.zia.translation.service.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownStorageServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void storeLoadExistsAndDelete_workAsExpected() throws IOException {
        MarkdownStorageService service = new MarkdownStorageService(tempDir.toString());
        byte[] markdownContent = "# Titre\n\nContenu traduit".getBytes();

        String jobId = "123e4567-e89b-12d3-a456-426614174000";

        service.store(jobId, markdownContent);

        assertThat(service.exists(jobId)).isTrue();
        assertThat(service.load(jobId)).isPresent();

        service.delete(jobId);

        assertThat(service.exists(jobId)).isFalse();
        assertThat(service.load(jobId)).isEmpty();
    }
}
