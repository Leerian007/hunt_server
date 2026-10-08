package com.guxx.ray.ai.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class ResultJsonTest {
    private final ObjectMapper mapper = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    @Test
    void serializesSuccessWithGenericPayload() throws Exception {
        JsonNode json = mapper.readTree(mapper.writeValueAsString(
                Result.success(Map.of("steam_id", "76561198000000000"))));
        assertThat(json.size()).isEqualTo(3);
        assertThat(json.get("code").asInt()).isZero();
        assertThat(json.get("message").asText()).isEqualTo("success");
        assertThat(json.get("data").get("steam_id").asText()).isEqualTo("76561198000000000");
    }

    @Test
    void preservesNullDataDespiteGlobalNullExclusion() throws Exception {
        JsonNode json = mapper.readTree(mapper.writeValueAsString(Result.error(404, "Player not found")));
        assertThat(json.size()).isEqualTo(3);
        assertThat(json.get("code").asInt()).isEqualTo(404);
        assertThat(json.get("message").asText()).isEqualTo("Player not found");
        assertThat(json.has("data")).isTrue();
        assertThat(json.get("data").isNull()).isTrue();
    }
}