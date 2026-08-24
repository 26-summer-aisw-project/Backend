package kr.lostory.backend.config;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import jakarta.servlet.http.HttpServletResponse;
import kr.lostory.backend.common.exception.ErrorCode;
import kr.lostory.backend.common.response.ErrorResponse;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

	@Bean
	SecretKey jwtSecretKey(JwtProperties properties) {
		return new SecretKeySpec(properties.secret(), "HmacSHA256");
	}

	@Bean
	JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
		return new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(jwtSecretKey));
	}

	@Bean
	JwtDecoder jwtDecoder(SecretKey jwtSecretKey, JwtProperties properties,
		OAuth2TokenValidator<Jwt> jwtRolesValidator) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSecretKey)
			.macAlgorithm(MacAlgorithm.HS256)
			.build();
		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
			JwtValidators.createDefaultWithIssuer(properties.issuer()), jwtRolesValidator));
		return decoder;
	}

	@Bean
	OAuth2TokenValidator<Jwt> jwtRolesValidator() {
		OAuth2Error error = new OAuth2Error("invalid_token", "The roles claim is invalid.", null);
		Set<String> allowedRoles = Set.of("USER", "ADMIN");
		return jwt -> {
			Object claim = jwt.getClaims().get("roles");
			if (!(claim instanceof Collection<?> roles) || roles.isEmpty()
				|| roles.stream().anyMatch(role -> !(role instanceof String) || !allowedRoles.contains(role))) {
				return OAuth2TokenValidatorResult.failure(error);
			}
			return OAuth2TokenValidatorResult.success();
		};
	}

	@Bean
	JwtAuthenticationConverter jwtAuthenticationConverter() {
		JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
		converter.setJwtGrantedAuthoritiesConverter(jwt -> {
			List<String> roles = jwt.getClaimAsStringList("roles");
			return roles.stream().<GrantedAuthority>map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList();
		});
		return converter;
	}

	@Bean
	@Qualifier("unauthorizedEntryPoint")
	AuthenticationEntryPoint unauthorizedEntryPoint(ObjectMapper objectMapper) {
		return (request, response, exception) -> writeError(response, objectMapper, ErrorCode.UNAUTHORIZED);
	}

	@Bean
	@Qualifier("invalidTokenEntryPoint")
	AuthenticationEntryPoint invalidTokenEntryPoint(ObjectMapper objectMapper) {
		return (request, response, exception) -> writeError(response, objectMapper, ErrorCode.INVALID_TOKEN);
	}

	@Bean
	AccessDeniedHandler accessDeniedHandler(ObjectMapper objectMapper) {
		return (request, response, exception) -> writeError(response, objectMapper, ErrorCode.FORBIDDEN);
	}

	@Bean
	SecurityFilterChain securityFilterChain(
		HttpSecurity http,
		@Qualifier("unauthorizedEntryPoint") AuthenticationEntryPoint unauthorizedEntryPoint,
		@Qualifier("invalidTokenEntryPoint") AuthenticationEntryPoint invalidTokenEntryPoint,
		AccessDeniedHandler accessDeniedHandler,
		JwtAuthenticationConverter jwtAuthenticationConverter
	) throws Exception {
		http.csrf(AbstractHttpConfigurer::disable)
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests((requests) -> requests
				.requestMatchers(EndpointRequest.to(HealthEndpoint.class)).permitAll()
				.requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
				.requestMatchers(
					"/api/v1/auth/signup",
					"/api/v1/auth/login",
					"/swagger-ui.html",
					"/swagger-ui/**",
					"/v3/api-docs",
					"/v3/api-docs/**",
					"/v3/api-docs.yaml")
				.permitAll()
				.anyRequest().authenticated())
			.exceptionHandling(exceptions -> exceptions
				.authenticationEntryPoint(unauthorizedEntryPoint)
				.accessDeniedHandler(accessDeniedHandler))
			.oauth2ResourceServer(resourceServer -> resourceServer
				.authenticationEntryPoint(invalidTokenEntryPoint)
				.accessDeniedHandler(accessDeniedHandler)
				.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
			.httpBasic(AbstractHttpConfigurer::disable)
			.formLogin(AbstractHttpConfigurer::disable)
			.logout(AbstractHttpConfigurer::disable);
		return http.build();
	}

	private static void writeError(HttpServletResponse response, ObjectMapper objectMapper, ErrorCode errorCode)
		throws IOException {
		response.setStatus(errorCode == ErrorCode.FORBIDDEN ? 403 : 401);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		objectMapper.writeValue(response.getOutputStream(), new ErrorResponse(errorCode));
	}

}
