package zas.admin.zia.translation;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import zas.admin.zia.translation.service.glossary.AcronymLookupTool;
import zas.admin.zia.translation.service.glossary.Glossary;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "zia.translation.glossary.tool-enabled=true",
        "zia.translation.glossary.path=classpath:glossary/glossary-fixture.json"
})
class GlossaryToolEnabledContextTest {

    @MockitoBean(name = "visionChatClient")
    ChatClient visionChatClient;

    @MockitoBean(name = "llmChatClient")
    ChatClient llmChatClient;

    @Autowired
    Glossary glossary;

    @Autowired
    AcronymLookupTool acronymLookupTool;

    @Test
    void contextLoads_withTheGlossaryTool() {
        assertThat(glossary.size()).isEqualTo(8);
        assertThat(acronymLookupTool).isNotNull();
    }
}
