package pl.edu.pjasensei.identity;

import jakarta.servlet.DispatcherType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/** Spring is only a resource server: it validates tokens and never sees a login or a password. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IdentityProperties.class)
class SecurityConfig {

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, KnownUserRule knownUser) throws Exception {
        return http
                // Applies the mappings of CrossOriginConfig, which would otherwise never be reached.
                .cors(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        // The Compose healthcheck and the page before sign-in call it without a token.
                        .requestMatchers(HttpMethod.GET, "/api/status").permitAll()
                        // Answers from the token alone, for people Refresh has not added yet.
                        .requestMatchers(HttpMethod.GET, "/api/me").authenticated()
                        .anyRequest().access(knownUser))
                .oauth2ResourceServer(server -> server.jwt(Customizer.withDefaults()))
                .build();
    }

    @Bean
    JwtDecoder jwtDecoder(IdentityProperties identity, RestTemplateBuilder builder) {
        var decoder = NimbusJwtDecoder.withJwkSetUri(identity.jwkSetUri())
                .restOperations(builder.build())
                .build();
        // Cognito access tokens carry the client in client_id and normally have no aud.
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(identity.issuer()),
                // The default checks the expiry only of a token that has one.
                new JwtClaimValidator<Instant>(JwtClaimNames.EXP, Objects::nonNull),
                new JwtClaimValidator<String>("client_id", identity.clientId()::equals)));
        return decoder;
    }

    /** Role comes only from the groups in the token, so a group change takes effect at next login. */
    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter(IdentityProperties identity) {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(token -> {
            List<String> groups = token.getClaimAsStringList("cognito:groups");
            if (groups == null) {
                return List.of();
            }
            var authorities = new ArrayList<GrantedAuthority>();
            if (groups.stream().anyMatch(identity::isInstructorGroup)) {
                authorities.add(Role.INSTRUCTOR.authority());
            }
            if (groups.stream().anyMatch(identity::isClassGroup)) {
                authorities.add(Role.STUDENT.authority());
            }
            return authorities;
        });
        return converter;
    }
}
