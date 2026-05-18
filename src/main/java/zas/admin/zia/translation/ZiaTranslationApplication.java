package zas.admin.zia.translation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ZiaTranslationApplication {
    private ZiaTranslationApplication() {
        /* This utility class should not be instantiated */
    }

    public static void main(String[] args) {
        SpringApplication.run(ZiaTranslationApplication.class, args);
    }

}
