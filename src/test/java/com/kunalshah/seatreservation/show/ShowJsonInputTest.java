package com.kunalshah.seatreservation.show;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "jwt.secret=0123456789abcdef0123456789abcdef"
})
class ShowJsonInputTest {
    @Autowired JsonMapper mapper;

    @Test
    void fractionalPricesAndLimitsAreRejectedInsteadOfTruncated() {
        for (String values : new String[] {
                "\"price_paise\":125.9",
                "\"price_paise\":-0.5",
                "\"price_paise\":100,\"per_user_limit\":2.5"
        }) {
            String json = "{\"name\":\"concert\",\"seats\":[\"A1\"]," + values + "}";
            assertThatThrownBy(() -> mapper.readValue(json, ShowDtos.ShowCreateRequest.class))
                    .as("fractional input must fail: %s", json)
                    .isInstanceOf(RuntimeException.class);
        }
    }
}
