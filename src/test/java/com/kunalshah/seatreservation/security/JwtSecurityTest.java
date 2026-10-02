package com.kunalshah.seatreservation.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "jwt.secret=0123456789abcdef0123456789abcdef",
        "jwt.issuer=seat-reservation",
        "jwt.audience=seat-reservation-api"
})
@AutoConfigureMockMvc
class JwtSecurityTest {
    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    @Autowired
    MockMvc mvc;

    @Test
    void guestTokensHaveDifferentSubjectsOneHourExpiryAndUserRole() throws Exception {
        String firstBody = mvc.perform(post("/auth/guest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user_id").isNotEmpty())
                .andExpect(jsonPath("$.expires_at").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String first = JsonPath.read(firstBody, "$.token");
        String second = guestToken();
        JWTClaimsSet claims = SignedJWT.parse(first).getJWTClaimsSet();

        assertThat(claims.getSubject()).isNotBlank().isNotEqualTo(SignedJWT.parse(second).getJWTClaimsSet().getSubject());
        assertThat(claims.getSubject()).isEqualTo(JsonPath.read(firstBody, "$.user_id"));
        assertThat(claims.getIssuer()).isEqualTo("seat-reservation");
        assertThat(claims.getAudience()).contains("seat-reservation-api");
        assertThat(claims.getStringClaim("role")).isEqualTo("USER");
        assertThat(claims.getExpirationTime().toInstant())
                .isBetween(Instant.now().plusSeconds(3550), Instant.now().plusSeconds(3650));
    }

    @Test
    void expiredAndWrongIssuerAndWrongAudienceTokensAreRejected() throws Exception {
        Instant now = Instant.now();
        assertUnauthorized(token("USER", "seat-reservation", "seat-reservation-api",
                now.minusSeconds(7200), now.minusSeconds(3600)));
        assertUnauthorized(token("USER", "other-issuer", "seat-reservation-api",
                now, now.plusSeconds(3600)));
        assertUnauthorized(token("USER", "seat-reservation", "other-audience",
                now, now.plusSeconds(3600)));
    }

    @Test
    void userCannotCreateShowsAndAdminCannotReserveSeats() throws Exception {
        mvc.perform(post("/shows")
                .header("Authorization", "Bearer " + guestToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));

        mvc.perform(post("/shows/" + UUID.randomUUID() + "/reserve")
                .header("Authorization", "Bearer " + token(
                        "ADMIN", "seat-reservation", "seat-reservation-api",
                        Instant.now(), Instant.now().plusSeconds(3600)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"seats\":[\"A1\"],\"idempotency_key\":\"key\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
    }

    private void assertUnauthorized(String token) throws Exception {
        mvc.perform(post("/shows/" + UUID.randomUUID() + "/reserve")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"seats\":[\"A1\"],\"idempotency_key\":\"key\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"));
    }

    private String guestToken() throws Exception {
        String body = mvc.perform(post("/auth/guest"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.token");
    }

    private static String token(
            String role, String issuer, String audience, Instant issuedAt, Instant expiresAt) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("test-" + UUID.randomUUID())
                .issuer(issuer)
                .audience(audience)
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(expiresAt))
                .claim("role", role)
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }
}
