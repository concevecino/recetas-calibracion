package com.example.rag;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "ml.storage-directory=target/ml-application-test-${random.uuid}")
class ApplicationTest {
    @Test void startsWithoutProviderCredentials() {}
}
