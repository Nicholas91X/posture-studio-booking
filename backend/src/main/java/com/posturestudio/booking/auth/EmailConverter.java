package com.posturestudio.booking.auth;

import jakarta.persistence.Converter;
import jakarta.persistence.AttributeConverter;

/**
 * EmailConverter
 */
@Converter 
public class EmailConverter implements AttributeConverter<Email, String> {

    @Override
    public String convertToDatabaseColumn(Email attribute) {
        return attribute == null ? null : attribute.value();
    }

    @Override
    public Email convertToEntityAttribute(String dbData) {
        return dbData == null ? null : new Email(dbData);
    }

}
