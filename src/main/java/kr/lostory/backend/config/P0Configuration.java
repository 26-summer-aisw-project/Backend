package kr.lostory.backend.config;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
	FoundItemProperties.class,
	ObjectStorageProperties.class,
	VisionProperties.class,
	MatchingProperties.class,
	LostReportProperties.class
})
public class P0Configuration {

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}
}
