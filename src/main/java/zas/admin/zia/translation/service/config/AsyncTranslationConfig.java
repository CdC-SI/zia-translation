package zas.admin.zia.translation.service.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@Configuration
@EnableScheduling
@EnableAsync
class AsyncTranslationConfig {

    @Bean(name = "translationTaskExecutor")
    Executor translationTaskExecutor(@Value("${zia.translation.async.pool-size}") int poolSize) {
        return Executors.newFixedThreadPool(poolSize);
    }
}
