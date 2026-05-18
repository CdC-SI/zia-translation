package zas.admin.zia.translation;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
class ZiaTranslationApplicationTests {

    @MockitoBean(name = "visionChatClient")
    ChatClient visionChatClient;

    @MockitoBean(name = "llmChatClient")
    ChatClient llmChatClient;

    @Test
    void contextLoads() {
    }

}
