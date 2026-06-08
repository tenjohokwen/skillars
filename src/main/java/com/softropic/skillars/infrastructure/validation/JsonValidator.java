package com.softropic.skillars.infrastructure.validation;

//TODO: Refactor this class to make it more general and useful for validate objects in any place
public class JsonValidator {
    /*final private static Logger log = LoggerFactory.getLogger(JsonValidator.class);

    final private static ObjectMapper mapper = new ObjectMapper();
    final private static Cache<String, JsonSchema> scheme = CacheBuilder.newBuilder().maximumSize(200).build();
    final private static JsonSchemaFactory jsonSchemaFactory = JsonSchemaFactory.byDefault();

    public static void validate(String schemaPath, Map<?, ?> map) throws JsonConstraintViolationException, IOException, ExecutionException, ProcessingException {
        validate(schemaPath, null, map);
    }

    public static void validate(String schemaPath, String schemaName, Map<?, ?> map) {
        ProcessingReport report = null;
        try {
            report = getSchema(schemaPath, schemaName).validate(mapper.valueToTree(map), true);
        } catch (ProcessingException | IOException | ExecutionException e) {
            Throwables.propagate(e);
        }

        if(!report.isSuccess()) {
            if (schemaName == null) {
                schemaName = Paths.get(schemaPath).getFileName().toString();
            }
            throw new JsonConstraintViolationException(schemaName, resolveContraintViolations(report, schemaName));
        }
    }

    private static JsonSchema getSchema(String schemaPath, String schemaName) throws IOException, ExecutionException {
        return scheme.get(schemaPath+"#"+schemaName, () -> loadSchema(schemaPath, schemaName));
    }

    private static JsonSchema loadSchema(String schemaPath, String schemaName) throws IOException, ProcessingException{
        JsonNode schemaNode = JsonLoader.fromURL(ResourceUtils.getURL("classpath:" + schemaPath + ".json"));

        // Composite schema json file
        if (schemaName != null ) {
            return jsonSchemaFactory.getJsonSchema(schemaNode, "/"+schemaName);
        } else {
            return jsonSchemaFactory.getJsonSchema(schemaNode);
        }
    }

    private static List<JsonConstraintViolation> resolveContraintViolations(ProcessingReport report, String schemaName) {
        ImmutableList.Builder<JsonConstraintViolation> violationsBuilder = ImmutableList.builder();

        JsonPath.using(new JacksonJsonNodeJsonProvider()).parse(((ListProcessingReport) report).asJson())
            .read("$..[?(@.message)]", ArrayNode.class) // flat nested error messages
            .elements().forEachRemaining(msgJson -> {
            String property = schemaName + getProperty(msgJson);

            if (msgJson.hasNonNull("keyword")) {
                switch (msgJson.get("keyword").asText()) {
                    case "required":
                        violationsBuilder.addAll(resolveRequiredFieldViolations(property, msgJson));
                        break;
                    case "type":
                        violationsBuilder.add(resolveTypeViolation(property, msgJson));
                        break;
                    case "format":
                        violationsBuilder.add(resolveFormatViolation(property));
                        break;
                    case "enum":
                        violationsBuilder.add(resolveEnumViolation(property, msgJson));
                        break;
                    case "oneOf":
                    case "allOf":
                        violationsBuilder.add(resolveSemanticViolation(property));
                        break;
                    default:
                        violationsBuilder.add(resolveConstraintError(property, msgJson.get("message").asText()));
                }
            } else {
                violationsBuilder.add(resolveUncategorizedError(msgJson.get("message").asText()));
            }
        });

        return violationsBuilder.build();
    }

    private static String getProperty(JsonNode msgJson) {
        StringBuilder pointer = new StringBuilder("");
        if (msgJson.hasNonNull("instance")) {
            JsonNode instance = msgJson.get("instance");
            if (instance.hasNonNull("pointer")) {
                pointer.append(instance.get("pointer").asText().replaceAll("/", "."));
            }
        }
        return pointer.toString();
    }

    private static List<JsonConstraintViolation> resolveRequiredFieldViolations(String property, JsonNode msgJson ) {
        List<JsonConstraintViolation> requiredFieldsViolations = newArrayList();

        msgJson.get("missing").elements().forEachRemaining(field ->
                        requiredFieldsViolations.add(new JsonConstraintViolation(JsonConstraintViolationCode.MISSING_PROPERTY, String.format("%s.%s is required", property, field.asText())))
        );
        return requiredFieldsViolations;
    }

    private static JsonConstraintViolation resolveTypeViolation(String property, JsonNode msgJson) {
        return new JsonConstraintViolation(JsonConstraintViolationCode.INVALID_TYPE, String.format("%s should be a(n) %s", property, msgJson.get("expected").get(0).asText()));
    }

    private static JsonConstraintViolation resolveFormatViolation(String property) {
        return new JsonConstraintViolation(JsonConstraintViolationCode.INVALID_FORMAT, String.format("%s is in invalid format", property));
    }

    private static JsonConstraintViolation resolveEnumViolation(String property, JsonNode msgJson) {
        return new JsonConstraintViolation(JsonConstraintViolationCode.INVALID_ENUMERATED_VALUE, String.format("%s must be in %s", property,  Joiner.on(",").join(msgJson.get("enum").elements())));
    }

    private static JsonConstraintViolation resolveSemanticViolation(String property) {
        return new JsonConstraintViolation(JsonConstraintViolationCode.SEMANTIC_ERROR, String.format("%s has a semantic error", property));
    }

    private static JsonConstraintViolation resolveConstraintError(String property, String message) {
        return new JsonConstraintViolation(JsonConstraintViolationCode.CONSTRAINT_ERROR, property + " : " + message);
    }

    private static JsonConstraintViolation resolveUncategorizedError(String message) {
        return new JsonConstraintViolation(JsonConstraintViolationCode.UNCATEGORIZED_ERROR, message);
    }*/
}
