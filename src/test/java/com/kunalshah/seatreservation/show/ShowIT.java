package com.kunalshah.seatreservation.show;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
class ShowIT {
    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void createDefaultsToFourAndGetReconcilesEveryAvailableSeat() throws Exception {
        String body = create("""
                {"name":"concert","seats":["A2","A1"],"price_paise":125}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.per_user_limit").value(4))
                .andExpect(jsonPath("$.price_paise").value(125))
                .andExpect(jsonPath("$.total_seats").value(2))
                .andExpect(jsonPath("$.available").value(2))
                .andExpect(jsonPath("$.held").value(0))
                .andExpect(jsonPath("$.confirmed").value(0))
                .andExpect(jsonPath("$.seats[0].seat_label").value("A1"))
                .andExpect(jsonPath("$.seats[0].state").value("available"))
                .andExpect(jsonPath("$.seats[1].seat_label").value("A2"))
                .andExpect(jsonPath("$.seats[1].state").value("available"))
                .andReturn().getResponse().getContentAsString();

        String id = JsonPath.read(body, "$.id");
        mvc.perform(get("/shows/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.available").value(2))
                .andExpect(jsonPath("$.confirmed").value(0));
    }

    @Test
    void createAcceptsCustomLimitAndLargeIntegerPrice() throws Exception {
        create("""
                {"name":"play","seats":["B1"],"price_paise":3000000000,"per_user_limit":2}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.price_paise").value(3000000000L))
                .andExpect(jsonPath("$.per_user_limit").value(2));
    }

    @Test
    void invalidSeatListsAndPricesLeaveNoShowBehind() throws Exception {
        Integer before = jdbc.queryForObject("select count(*) from shows", Integer.class);
        String[] invalid = {
                "{\"name\":\"empty\",\"seats\":[],\"price_paise\":100}",
                "{\"name\":\"duplicate\",\"seats\":[\"A1\",\"A1\"],\"price_paise\":100}",
                "{\"name\":\"blank seat\",\"seats\":[\"  \"],\"price_paise\":100}",
                "{\"name\":\"negative\",\"seats\":[\"A1\"],\"price_paise\":-1}",
                "{\"name\":\"bad limit\",\"seats\":[\"A1\"],\"price_paise\":100,\"per_user_limit\":0}"
        };
        for (String body : invalid) {
            create(body).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_request"));
        }
        assertThat(jdbc.queryForObject("select count(*) from shows", Integer.class)).isEqualTo(before);
    }

    @Test
    void unknownShowReturnsNotFound() throws Exception {
        mvc.perform(get("/shows/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    private ResultActions create(String body) throws Exception {
        return mvc.perform(post("/shows")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }
}
