package zas.admin.zia.translation.service.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import zas.admin.zia.translation.service.extraction.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {ExtractionProfileController.class, ExtractionExceptionHandler.class})
@AutoConfigureMockMvc(addFilters = false)
class ExtractionProfileControllerTest {

    private static final ExtractionProfile PROFILE = new ExtractionProfile("sumex-invoice", "Medical invoice",
            "instructions",
            Map.of("sex", new FieldDefinition("sex", FieldType.ENUM, "Sex", List.of("male", "female"))),
            Map.of("services", new CollectionDefinition("services", "Service lines",
                    List.of(new FieldDefinition("amount", FieldType.AMOUNT, "Amount", null)))));

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ExtractionProfileRegistry registry;

    @Test
    void list_returnsSummaries() throws Exception {
        when(registry.all()).thenReturn(List.of(PROFILE));

        mockMvc.perform(get(ExtractionProfileController.BASE_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("sumex-invoice"))
                .andExpect(jsonPath("$[0].description").value("Medical invoice"))
                .andExpect(jsonPath("$[0].fieldCount").value(1))
                .andExpect(jsonPath("$[0].collections[0]").value("services"));
    }

    @Test
    void get_returnsFieldsAndCollections() throws Exception {
        when(registry.find("sumex-invoice")).thenReturn(Optional.of(PROFILE));

        mockMvc.perform(get(ExtractionProfileController.BASE_PATH + "/sumex-invoice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields[0].key").value("sex"))
                .andExpect(jsonPath("$.fields[0].type").value("ENUM"))
                .andExpect(jsonPath("$.fields[0].values[1]").value("female"))
                .andExpect(jsonPath("$.collections[0].fields[0].key").value("amount"))
                .andExpect(jsonPath("$.collections[0].fields[0].values").doesNotExist())
                .andExpect(jsonPath("$.instructions").doesNotExist());
    }

    @Test
    void get_unknownProfile_returns404() throws Exception {
        when(registry.find("unknown")).thenReturn(Optional.empty());

        mockMvc.perform(get(ExtractionProfileController.BASE_PATH + "/unknown"))
                .andExpect(status().isNotFound());
    }
}
