/*
 * Copyright (2023) The Delta Lake Project Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.delta.kernel.defaults.internal.types;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

import io.delta.kernel.types.*;
import static io.delta.kernel.internal.util.Preconditions.checkArgument;

/**
 * Parses JSON serialized Delta data types to their {@link DataType} class based on the
 * <a href="https://github.com/delta-io/delta/blob/master/PROTOCOL.md#primitive-types">
 *     serialization rules </a> outlined in the Delta Protocol.
 */
public class DataTypeParser {

    private DataTypeParser() {}

    public static StructType parseSchema(JsonNode json) {
        DataType parsedType = parseDataType(json);
        if (parsedType instanceof StructType) {
            return (StructType) parsedType;
        } else {
            throw new IllegalArgumentException(String.format(
                "Could not parse the following JSON as a valid StructType:\n%s", json));
        }
    }

    /**
     * Parses a Delta data type from JSON. Data types can either be serialized as strings (for
     * primitive types) or as objects (for complex types).
     *
     * For example:
     * <pre>
     * // Map type field is serialized as:
     * {
     *   "name" : "f",
     *   "type" : {
     *     "type" : "map",
     *     "keyType" : "string",
     *     "valueType" : "string",
     *     "valueContainsNull" : true
     *   },
     *   "nullable" : true,
     *   "metadata" : { }
     * }
     *
     * // Integer type field serialized as:
     * {
     *   "name" : "a",
     *   "type" : "integer",
     *   "nullable" : false,
     *   "metadata" : { }
     * }
     * </pre>
     */
    static DataType parseDataType(JsonNode json) {
        switch (json.getNodeType()) {
            case STRING:
                // simple types are stored as just a string
                return nameToType(json.textValue());
            case OBJECT:
                // complex types (array, map, or struct are stored as JSON objects)
                String type = getStringField(json, "type");
                switch (type) {
                    case "struct":
                        return parseStructType(json);
                    case "array":
                        return parseArrayType(json);
                    case "map":
                        return parseMapType(json);
                    // No default case here; fall through to the following error when no match
                }
            default:
                throw new IllegalArgumentException(String.format(
                    "Could not parse the following JSON as a valid Delta data type:\n%s", json));
        }
    }

    /**
     * Parses an <a href="https://github.com/delta-io/delta/blob/master/PROTOCOL.md#array-type">
     *     array type </a>
     */
    private static ArrayType parseArrayType(JsonNode json) {
        checkArgument(json.isObject() && json.size() == 3,
            String.format(
                "Expected JSON object with 3 fields for array data type but got:\n%s", json));
        boolean containsNull = getBooleanField(json, "containsNull");
        DataType dataType = parseDataType(getNonNullField(json, "elementType"));
        return new ArrayType(dataType, containsNull);
    }

    /**
     * Parses an <a href="https://github.com/delta-io/delta/blob/master/PROTOCOL.md#map-type">
     *     map type </a>
     */
    private static MapType parseMapType(JsonNode json) {
        checkArgument(json.isObject() && json.size() == 4,
            String.format(
                "Expected JSON object with 4 fields for map data type but got:\n%s", json));
        boolean valueContainsNull = getBooleanField(json, "valueContainsNull");
        DataType keyType = parseDataType(getNonNullField(json, "keyType"));
        DataType valueType = parseDataType(getNonNullField(json, "valueType"));
        return new MapType(keyType, valueType, valueContainsNull);
    }

    /**
     * Parses an <a href="https://github.com/delta-io/delta/blob/master/PROTOCOL.md#struct-type">
     *     struct type </a>
     */
    private static StructType parseStructType(JsonNode json) {
        checkArgument(json.isObject() && json.size() == 2,
            String.format(
                "Expected JSON object with 2 fields for struct data type but got:\n%s", json));
        JsonNode fieldsNode = getNonNullField(json, "fields");
        checkArgument(fieldsNode.isArray(),
            String.format("Expected array for fieldName=%s in:\n%s", "fields", json));
        Iterator<JsonNode> fields = fieldsNode.elements();
        List<StructField> parsedFields = new ArrayList<>();
        while (fields.hasNext()) {
            parsedFields.add(parseStructField(fields.next()));
        }
        return new StructType(parsedFields);
    }

    /**
     * Parses an <a href="https://github.com/delta-io/delta/blob/master/PROTOCOL.md#struct-field">
     *     struct field </a>
     */
    private static StructField parseStructField(JsonNode json) {
        checkArgument(json.isObject(), "Expected JSON object for struct field");
        String name = getStringField(json, "name");
        DataType type = parseDataType(getNonNullField(json, "type"));
        boolean nullable = getBooleanField(json, "nullable");
        Map<String, String> metadata = parseFieldMetadata(json.get("metadata"));
        return new StructField(
            name,
            type,
            nullable,
            metadata
        );
    }

    // TODO for now we maintain the current behavior that parses field metadata as a
    //  Map<String, String> for either string or numerical value types. A follow-up PR will refactor
    //  this to support all the supported value types for field metadata and add a FieldMetadata
    //  class in place of Map<String, String>
    private static Map<String, String> parseFieldMetadata(JsonNode json) {
        if (json == null || json.isNull()) {
            return Collections.emptyMap();
        }

        checkArgument(json.isObject(), "Expected JSON object for struct field metadata");
        final Iterator<Map.Entry<String,JsonNode>> iterator = json.fields();
        final Map<String, String> metadata = new HashMap<>();
        while (iterator.hasNext()) {
            Map.Entry<String, JsonNode> entry = iterator.next();
            JsonNode value = entry.getValue();
            String key = entry.getKey();

            if (!(value.isTextual() || value.isIntegralNumber())) {
                throw new UnsupportedOperationException(
                    "Only numerical or string type field metadata values are supported");
            }
            metadata.put(key, value.asText());
        }
        return metadata;
    }

    private static String FIXED_DECIMAL_REGEX = "decimal\\(\\s*(\\d+)\\s*,\\s*(\\-?\\d+)\\s*\\)";
    private static Pattern FIXED_DECIMAL_PATTERN = Pattern.compile(FIXED_DECIMAL_REGEX);

    /**
     * Parses primitive string type names to a {@link DataType}
     */
    private static DataType nameToType(String name) {
        if (BasePrimitiveType.isPrimitiveType(name)) {
            return BasePrimitiveType.createPrimitive(name);
        } else if (name.equals("decimal")) {
            return DecimalType.USER_DEFAULT;
        } else {
            // decimal has a special pattern with a precision and scale
            Matcher decimalMatcher = FIXED_DECIMAL_PATTERN.matcher(name);
            if (decimalMatcher.matches()) {
                int precision = Integer.parseInt(decimalMatcher.group(1));
                int scale = Integer.parseInt(decimalMatcher.group(2));
                return new DecimalType(precision, scale);
            }

            throw new IllegalArgumentException(
                String.format("%s is not a supported delta data type", name));
        }
    }

    private static JsonNode getNonNullField(JsonNode rootNode, String fieldName) {
        JsonNode node = rootNode.get(fieldName);
        if (node == null || node.isNull()) {
            throw new IllegalArgumentException(
                String.format("Expected non-null for fieldName=%s in:\n%s", fieldName, rootNode));
        }
        return node;
    }

    private static String getStringField(JsonNode rootNode, String fieldName) {
        JsonNode node = getNonNullField(rootNode, fieldName);
        checkArgument(node.isTextual(),
            String.format("Expected string for fieldName=%s in:\n%s", fieldName, rootNode));
        return node.textValue(); // double check this only works for string values! and isTextual()!
    }

    private static boolean getBooleanField(JsonNode rootNode, String fieldName) {
        JsonNode node = getNonNullField(rootNode, fieldName);
        checkArgument(node.isBoolean(),
            String.format("Expected boolean for fieldName=%s in:\n%s", fieldName, rootNode));
        return node.booleanValue();
    }

}
