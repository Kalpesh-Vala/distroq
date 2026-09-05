package com.distroq;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@Disabled("Needs a running PostgreSQL and Redis (docker compose up -d); enable manually")
class DistroqApplicationTests {

	@Test
	void contextLoads() {
	}

}
