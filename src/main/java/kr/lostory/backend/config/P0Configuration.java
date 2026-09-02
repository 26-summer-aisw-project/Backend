package kr.lostory.backend.config;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties({
	FoundItemProperties.class,
	ObjectStorageProperties.class,
	VisionProperties.class,
	MatchingProperties.class,
	LostReportProperties.class,
	kr.lostory.backend.point.domain.PointPolicy.class
})
public class P0Configuration {

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}
}
