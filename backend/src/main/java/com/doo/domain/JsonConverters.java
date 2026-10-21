package com.doo.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.*;

public class JsonConverters {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Converter(autoApply = false)
    public static class MapConverter implements AttributeConverter<Map<String, Object>, String> {
        @Override
        public String convertToDatabaseColumn(Map<String, Object> attribute) {
            if (attribute == null) return "{}";
            try {
                return MAPPER.writeValueAsString(attribute);
            } catch (JsonProcessingException e) {
                return "{}";
            }
        }

        @Override
        public Map<String, Object> convertToEntityAttribute(String dbData) {
            if (dbData == null || dbData.isBlank()) return new HashMap<>();
            try {
                return MAPPER.readValue(dbData, new TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                return new HashMap<>();
            }
        }
    }

    @Converter(autoApply = false)
    public static class SourceRefListConverter implements AttributeConverter<List<TaskResult.SourceRef>, String> {
        @Override
        public String convertToDatabaseColumn(List<TaskResult.SourceRef> attribute) {
            if (attribute == null) return "[]";
            try {
                return MAPPER.writeValueAsString(attribute);
            } catch (JsonProcessingException e) {
                return "[]";
            }
        }

        @Override
        public List<TaskResult.SourceRef> convertToEntityAttribute(String dbData) {
            if (dbData == null || dbData.isBlank()) return new ArrayList<>();
            try {
                return MAPPER.readValue(dbData, new TypeReference<List<TaskResult.SourceRef>>() {});
            } catch (Exception e) {
                return new ArrayList<>();
            }
        }
    }
}
