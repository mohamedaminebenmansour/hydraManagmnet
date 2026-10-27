package tn.hydra.document.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lenient JPA converter for {@link ProjectType}.
 *
 * <p>Reads: maps the stored string to the enum; an unknown or blank value
 * falls back to {@link ProjectType#AUTRE} with a WARN log instead of throwing
 * "No enum constant …" (which used to turn every project/dossier read that
 * materializes the Project entity into an HTTP 500).</p>
 *
 * <p>Writes: stores the enum name as-is ({@code project.project_type} is a
 * plain text column). Projects are actually created/updated through JDBC in
 * {@code ProjectService}, so this converter mainly hardens reads.</p>
 */
@Converter
public class ProjectTypeConverter implements AttributeConverter<ProjectType, String> {

    private static final Logger log = LoggerFactory.getLogger(ProjectTypeConverter.class);

    @Override
    public String convertToDatabaseColumn(ProjectType attribute) {
        return attribute == null ? null : attribute.name();
    }

    @Override
    public ProjectType convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return null;
        }
        try {
            return ProjectType.valueOf(dbData);
        } catch (IllegalArgumentException e) {
            log.warn("[PROJECT-TYPE] Unknown project_type '{}' in database — reading as AUTRE "
                    + "(add the value to ProjectType to display it properly)", dbData);
            return ProjectType.AUTRE;
        }
    }
}
