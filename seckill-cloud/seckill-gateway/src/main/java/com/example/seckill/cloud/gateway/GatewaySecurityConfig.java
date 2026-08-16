package com.example.seckill.cloud.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

@Configuration
public class GatewaySecurityConfig {
    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .authorizeExchange(exchange -> exchange.anyExchange().permitAll())
                .build();
    }

    @Bean
    JwtDecoder jwtDecoder(@Value("${security.jwt.public-key:}") String configuredPublicKey,
                          @Value("${security.jwt.public-key-file:keys/public.pem}") String publicKeyFile)
            throws Exception {
        String publicKey = loadPem(configuredPublicKey, publicKeyFile, "JWT public key");
        String content = publicKey.replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "").replaceAll("\\s", "");
        RSAPublicKey key = (RSAPublicKey) KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(content)));
        return NimbusJwtDecoder.withPublicKey(key).build();
    }

    private static String loadPem(String configuredValue, String file, String description) throws IOException {
        if (configuredValue != null && !configuredValue.isBlank()) {
            return configuredValue.replace("\\n", "\n");
        }
        for (Path candidate : new Path[]{Path.of(file), Path.of("..", file), Path.of("seckill-cloud", file)}) {
            Path absolute = candidate.toAbsolutePath().normalize();
            if (Files.isRegularFile(absolute)) return Files.readString(absolute);
        }
        throw new IllegalStateException(description + " not found. Set JWT_PUBLIC_KEY or place the key at "
                + Path.of(file).toAbsolutePath().normalize());
    }
}
