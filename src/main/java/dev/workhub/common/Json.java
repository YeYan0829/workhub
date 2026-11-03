package dev.workhub.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;

@Component
public class Json {
    private final ObjectMapper mapper;

    public Json(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public <T> T read(String text, Class<T> type) {
        try {
            return mapper.readValue(text, type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot decode value", e);
        }
    }

    public String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot encode value", e);
        }
    }
}
