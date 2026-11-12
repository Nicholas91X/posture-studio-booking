package com.posturestudio.booking.shared;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.PropertyCustomizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;

/**
 * The OpenAPI contract the frontend generates its types from (D-083). springdoc reads the controllers and the
 * records; here three corrections, so that the generated types tell the truth:
 *
 * 1. the error answers: every operation may answer with a ProblemDetail (ApiExceptionHandler), described once as
 *    ApiProblem, with "code" as a closed list of every readable code (ErrorCode, the codes of ApiExceptionHandler,
 *    and those the modules declare as ProblemCodes);
 * 2. what an answer always contains: Java records have no "optional" field, so every field of a schema that only
 *    answers is required; the ones that may be null say so with @Schema(nullable = true) on the component. The
 *    schemas that are also request bodies keep what Bean Validation says (@NotNull...);
 * 3. a title and a version.
 * And one correction to a single property: a nullable reference to another schema (a ReturnedCredit that may be
 * null) cannot be written as "$ref + nullable" in OpenAPI 3.0, where a $ref ignores everything next to it; it is
 * written as "allOf: [$ref], nullable: true", the form of the standard (nullableReferences).
 *
 * The contract exists only with springdoc.api-docs.enabled=true (development): this configuration costs nothing
 * in production, where nobody asks for it.
 */
// the models of swagger-core use the raw Schema type everywhere (getSchemas() is a Map<String, Schema>)
@SuppressWarnings("rawtypes")
@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {

    static final String PROBLEM = "ApiProblem";
    static final String FIELD_ERROR = "ApiFieldError";

    // the codes written by ApiExceptionHandler itself, outside ErrorCode
    private static final List<String> HANDLER_CODES = List.of("CSRF_TOKEN_INVALID", "DATA_CONFLICT");

    // the statuses ApiExceptionHandler (and Spring Security through it) may answer with, on any operation
    private static final Map<String, String> ERROR_STATUSES = errorStatuses();

    @Bean
    OpenApiCustomizer contractCorrections(ObjectProvider<ProblemCodes> moduleCodes) {
        return openApi -> {
            openApi.info(new Info().title("Posture studio booking").version("1"));
            addProblemSchema(openApi, moduleCodes.orderedStream().toList());
            addErrorResponses(openApi);
            requireAnswerFields(openApi);
        };
    }

    @Bean
    PropertyCustomizer nullableReferences() {
        return (property, type) -> {
            boolean declaredNullable = Boolean.TRUE.equals(property.getNullable()) || (type.getCtxAnnotations() != null
                    && Arrays.stream(type.getCtxAnnotations())
                            .anyMatch(annotation -> annotation instanceof io.swagger.v3.oas.annotations.media.Schema schema
                                    && schema.nullable()));
            if (property.get$ref() == null || !declaredNullable) {
                return property;
            }
            return new ComposedSchema().allOf(List.of(new Schema<>().$ref(property.get$ref()))).nullable(true);
        };
    }

    // ***** 1. the error answers *****

    private static void addProblemSchema(OpenAPI openApi, List<ProblemCodes> moduleCodes) {
        // TreeSet: sorted and without repetitions, so the committed contract changes only when a code does
        Set<String> codes = new TreeSet<>();
        Arrays.stream(ErrorCode.values()).map(Enum::name).forEach(codes::add);
        codes.addAll(HANDLER_CODES);
        moduleCodes.forEach(module -> codes.addAll(module.codes()));

        Schema<?> code = new StringSchema()._enum(new ArrayList<>(codes))
                .description("The readable code of the error, when there is one: the frontend translates it into Italian");
        Schema<?> fieldError = new ObjectSchema()
                .addProperty("field", new StringSchema())
                .addProperty("constraint", new StringSchema().description("The violated constraint, e.g. NotBlank"))
                .required(List.of("field", "constraint"));
        Schema<?> problem = new ObjectSchema()
                .description("An error answer, RFC 9457 (ApiExceptionHandler). detail is for the log, never for the customer")
                .addProperty("type", new StringSchema())
                .addProperty("title", new StringSchema())
                .addProperty("status", new IntegerSchema())
                .addProperty("detail", new StringSchema())
                .addProperty("instance", new StringSchema())
                .addProperty("code", code)
                .addProperty("errors", new ArraySchema().items(new Schema<>().$ref(FIELD_ERROR))
                        .description("400: one entry per invalid field"))
                .required(List.of("status"));
        openApi.getComponents().addSchemas(FIELD_ERROR, fieldError).addSchemas(PROBLEM, problem);
    }

    private static void addErrorResponses(OpenAPI openApi) {
        forEachOperation(openApi, operation -> {
            ApiResponses responses = operation.getResponses();
            ERROR_STATUSES.forEach((status, description) -> {
                if (!responses.containsKey(status)) {
                    responses.addApiResponse(status, new ApiResponse().description(description)
                            .content(new Content().addMediaType("application/problem+json",
                                    new MediaType().schema(new Schema<>().$ref(PROBLEM)))));
                }
            });
        });
    }

    // ***** 2. what an answer always contains *****

    private static void requireAnswerFields(OpenAPI openApi) {
        Map<String, Schema> schemas = openApi.getComponents().getSchemas();
        Set<String> inAnswers = new HashSet<>();
        Set<String> inRequests = new HashSet<>();
        forEachOperation(openApi, operation -> {
            if (operation.getRequestBody() != null && operation.getRequestBody().getContent() != null) {
                operation.getRequestBody().getContent().values()
                        .forEach(media -> collect(media.getSchema(), schemas, inRequests));
            }
            operation.getResponses().forEach((status, response) -> {
                if (status.startsWith("2") && response.getContent() != null) {
                    response.getContent().values().forEach(media -> collect(media.getSchema(), schemas, inAnswers));
                }
            });
        });
        inAnswers.removeAll(inRequests);
        inAnswers.remove(PROBLEM);
        for (String name : inAnswers) {
            Schema<?> schema = schemas.get(name);
            if (schema != null && schema.getProperties() != null) {
                schema.setRequired(new ArrayList<>(new TreeSet<>(schema.getProperties().keySet())));
            }
        }
    }

    // the names of the schemas a schema refers to, through arrays and properties, each visited once
    private static void collect(Schema<?> schema, Map<String, Schema> schemas, Set<String> found) {
        if (schema == null) {
            return;
        }
        if (schema.get$ref() != null) {
            String name = schema.get$ref().substring(schema.get$ref().lastIndexOf('/') + 1);
            if (found.add(name)) {
                collect(schemas.get(name), schemas, found);
            }
            return;
        }
        collect(schema.getItems(), schemas, found);
        if (schema.getAllOf() != null) {
            schema.getAllOf().forEach(part -> collect(part, schemas, found));
        }
        if (schema.getProperties() != null) {
            schema.getProperties().values().forEach(property -> collect(property, schemas, found));
        }
    }

    // ***** helpers *****

    private static void forEachOperation(OpenAPI openApi, Consumer<Operation> action) {
        if (openApi.getPaths() == null) {
            return;
        }
        openApi.getPaths().values().forEach(path -> path.readOperations().forEach(action));
    }

    private static Map<String, String> errorStatuses() {
        Map<String, String> statuses = new LinkedHashMap<>();
        statuses.put("400", "Invalid request: wrong format, or invalid fields (errors)");
        statuses.put("401", "Nobody is signed in, or a wrong or expired access code (code)");
        statuses.put("403", "Not allowed for this account, a missing CSRF token, or a deactivated account (code)");
        statuses.put("404", "Not found, or someone else's: the same answer");
        statuses.put("409", "A business rule forbids it now (code)");
        statuses.put("429", "Too many requests: Retry-After says in how many seconds");
        statuses.put("500", "Unexpected error");
        statuses.put("503", "A service the request needs did not answer (code, when known)");
        return statuses;
    }
}
