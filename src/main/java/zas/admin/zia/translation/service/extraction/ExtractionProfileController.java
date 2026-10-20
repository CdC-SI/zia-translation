package zas.admin.zia.translation.service.extraction;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import zas.admin.zia.translation.service.config.TranslatorAccess;

import java.util.List;

/**
 * Extraction profiles known by the service: the keys a client can request with a profile.
 */
@RestController
@RequestMapping(value = ExtractionProfileController.BASE_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize(TranslatorAccess.PRE_AUTHORIZE_EXPRESSION)
class ExtractionProfileController {

    static final String BASE_PATH = "/api/v1/extraction-profiles";

    private final ExtractionProfileRegistry registry;

    ExtractionProfileController(ExtractionProfileRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    List<ProfileSummary> list() {
        return registry.all().stream()
                .map(profile -> new ProfileSummary(profile.id(), profile.description(),
                        profile.fields().size(), List.copyOf(profile.collections().keySet())))
                .toList();
    }

    @GetMapping("/{profileId}")
    ResponseEntity<ProfileDetail> get(@PathVariable String profileId) {
        return registry.find(profileId)
                .map(profile -> ResponseEntity.ok(new ProfileDetail(profile.id(), profile.description(),
                        List.copyOf(profile.fields().values()), List.copyOf(profile.collections().values()))))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    record ProfileSummary(String id, String description, int fieldCount, List<String> collections) {
    }

    record ProfileDetail(String id, String description, List<FieldDefinition> fields,
                         List<CollectionDefinition> collections) {
    }
}
