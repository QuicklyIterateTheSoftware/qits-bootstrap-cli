package eu.wohlben.qits.cli.bootstrap.contracts;

import au.com.dius.pact.consumer.dsl.DslPart;
import au.com.dius.pact.consumer.dsl.PactDslJsonArray;
import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import au.com.dius.pact.consumer.dsl.PactDslJsonRootValue;
import au.com.dius.pact.core.model.matchingrules.NullMatcher;
import au.com.dius.pact.core.model.matchingrules.RegexMatcher;
import au.com.dius.pact.core.model.matchingrules.TypeMatcher;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>A recorded answer, cut down to what the bootstrap reads.</b>
 * <p>
 * The rule (qits-1149): a pact binds only what the consumer reads. A row names the JSON paths its
 * code reads — {@code $.entries[*].project.slug} — and this class builds the response body from the
 * provider's recording with those paths and nothing else, so a field the bootstrap never looks at
 * can change on the provider without breaking this contract.
 * <p>
 * Every value comes from the recording; nothing is invented. The matchers:
 * <ul>
 *   <li>a path the index lists under {@code frozen.ids}: a UUID regex;
 *   <li>under {@code frozen.instants}: an ISO-8601 regex;
 *   <li>any other leaf: a type match, widened to "or null" where any recorded element held null;
 *   <li>an array the bootstrap reads into: at least one element, each matched against one template
 *       merged from every recorded element. Never an exact length: the bootstrap loops, and the
 *       provider's page size and order are not its business.
 * </ul>
 */
final class ReadBody {

    /** An ISO-8601 timestamp, any fraction length, Z or a numeric offset. */
    static final String ISO_INSTANT =
            "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2}(\\.\\d{1,9})?)?(Z|[+-]\\d{2}:?\\d{2})$";

    static final String UUID =
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

    private static final Pattern SEGMENT = Pattern.compile("\\.([A-Za-z0-9_]+)|\\[\\*]");

    private ReadBody() {
    }

    /** The response body for {@code reads} out of {@code recorded}. */
    static DslPart of(JsonNode recorded, List<String> reads, GoldenMasters.Operation op) {
        Node tree = Node.parse(reads);
        if (tree.each != null) {
            if (!recorded.isArray()) {
                throw unsupported(op, "$", "read as an array but recorded as " + recorded.getNodeType());
            }
            List<JsonNode> elements = new ArrayList<>();
            recorded.forEach(elements::add);
            if (elements.isEmpty() || !tree.each.isObject()) {
                throw unsupported(op, "$", "a root array of nothing or of scalars");
            }
            PactDslJsonBody template = PactDslJsonArray.arrayMinLike(1);
            fillObject(template, tree.each, elements, "$[*]", op);
            return template.closeObject();
        }
        if (!recorded.isObject()) {
            throw unsupported(op, "$", "read as an object but recorded as " + recorded.getNodeType());
        }
        PactDslJsonBody root = new PactDslJsonBody();
        fillObject(root, tree, List.of(recorded), "$", op);
        return root;
    }

    private static void fillObject(PactDslJsonBody target, Node node, List<JsonNode> examples,
                                   String path, GoldenMasters.Operation op) {
        for (Map.Entry<String, Node> field : node.fields.entrySet()) {
            String name = field.getKey();
            Node child = field.getValue();
            String childPath = path + "." + name;
            List<JsonNode> values = new ArrayList<>();
            for (JsonNode example : examples) {
                values.add(example.get(name));
            }
            if (child.each != null) {
                array(target, name, child.each, values, childPath, op);
            } else if (child.isObject()) {
                List<JsonNode> present = present(values);
                if (present.isEmpty() || present.size() < values.size()) {
                    throw unsupported(op, childPath, "an object the recording leaves null");
                }
                PactDslJsonBody nested = target.object(name);
                fillObject(nested, child, present, childPath, op);
                nested.closeObject();
            } else {
                leaf(target, name, values, childPath, op);
            }
        }
    }

    private static void array(PactDslJsonBody target, String name, Node element,
                              List<JsonNode> values, String path, GoldenMasters.Operation op) {
        List<JsonNode> elements = new ArrayList<>();
        for (JsonNode value : values) {
            if (value == null || !value.isArray()) {
                throw unsupported(op, path, "read as an array but recorded as "
                        + (value == null ? "absent" : value.getNodeType()));
            }
            value.forEach(elements::add);
        }
        if (elements.isEmpty()) {
            // Nothing to build a template from: the recording says "empty", and that is all a
            // contract can say.
            target.array(name).closeArray();
            return;
        }
        String elementPath = path + "[*]";
        if (element.isObject()) {
            PactDslJsonBody template = target.minArrayLike(name, 1);
            fillObject(template, element, elements, elementPath, op);
            ((PactDslJsonArray) template.closeObject()).closeArray();
            return;
        }
        if (element.each != null) {
            throw unsupported(op, elementPath, "an array of arrays");
        }
        target.minArrayLike(name, 1, rootLeaf(elements, elementPath, op), 1);
    }

    private static void leaf(PactDslJsonBody target, String name, List<JsonNode> values,
                             String path, GoldenMasters.Operation op) {
        List<JsonNode> present = present(values);
        if (present.isEmpty()) {
            target.nullValue(name);
            return;
        }
        boolean nullable = present.size() < values.size();
        JsonNode example = present.get(0);
        if (op.ids().contains(path) || op.instants().contains(path)) {
            String regex = op.ids().contains(path) ? UUID : ISO_INSTANT;
            if (!example.isTextual()) {
                throw unsupported(op, path, "a frozen value that is not a string");
            }
            if (nullable) {
                target.or(name, example.asText(), new RegexMatcher(regex, example.asText()),
                        NullMatcher.INSTANCE);
            } else {
                target.stringMatcher(name, regex, example.asText());
            }
        } else if (nullable) {
            target.or(name, scalar(example, path, op), TypeMatcher.INSTANCE, NullMatcher.INSTANCE);
        } else if (example.isTextual()) {
            target.stringType(name, example.asText());
        } else if (example.isNumber()) {
            target.numberType(name, example.numberValue());
        } else if (example.isBoolean()) {
            target.booleanType(name, example.asBoolean());
        } else {
            throw unsupported(op, path, "a " + example.getNodeType() + " read as a leaf");
        }
    }

    private static PactDslJsonRootValue rootLeaf(List<JsonNode> elements, String path,
                                                 GoldenMasters.Operation op) {
        JsonNode example = elements.get(0);
        if (present(elements).size() < elements.size()) {
            throw unsupported(op, path, "an array holding nulls");
        }
        if (op.ids().contains(path)) {
            return PactDslJsonRootValue.uuid(example.asText());
        }
        if (op.instants().contains(path)) {
            return PactDslJsonRootValue.stringMatcher(ISO_INSTANT, example.asText());
        }
        if (example.isTextual()) {
            return PactDslJsonRootValue.stringType(example.asText());
        }
        if (example.isNumber()) {
            return PactDslJsonRootValue.numberType(example.numberValue());
        }
        if (example.isBoolean()) {
            return PactDslJsonRootValue.booleanType(example.asBoolean());
        }
        throw unsupported(op, path, "a " + example.getNodeType() + " array element");
    }

    private static List<JsonNode> present(List<JsonNode> values) {
        List<JsonNode> present = new ArrayList<>();
        for (JsonNode value : values) {
            if (value != null && !value.isNull() && !value.isMissingNode()) {
                present.add(value);
            }
        }
        return present;
    }

    private static Object scalar(JsonNode example, String path, GoldenMasters.Operation op) {
        if (example.isTextual()) {
            return example.asText();
        }
        if (example.isNumber()) {
            return example.numberValue();
        }
        if (example.isBoolean()) {
            return example.asBoolean();
        }
        throw unsupported(op, path, "a " + example.getNodeType() + " read as a leaf");
    }

    private static IllegalStateException unsupported(GoldenMasters.Operation op, String path,
                                                     String what) {
        return new IllegalStateException("golden master " + op.state() + "/" + op.operationId()
                + ": " + path + " is " + what + ", which ReadBody cannot express");
    }

    /** The read paths as a tree: named fields, or {@code [*]} for every element of an array. */
    static final class Node {
        final Map<String, Node> fields = new LinkedHashMap<>();
        Node each;

        boolean isObject() {
            return !fields.isEmpty();
        }

        static Node parse(List<String> paths) {
            Node root = new Node();
            for (String path : paths) {
                if (!path.startsWith("$")) {
                    throw new IllegalArgumentException("a read path starts with $: " + path);
                }
                Node at = root;
                Matcher m = SEGMENT.matcher(path);
                int end = 1;
                while (m.find()) {
                    if (m.start() != end) {
                        throw new IllegalArgumentException("not a read path: " + path);
                    }
                    end = m.end();
                    if (m.group(1) != null) {
                        at = at.fields.computeIfAbsent(m.group(1), k -> new Node());
                    } else {
                        if (at.each == null) {
                            at.each = new Node();
                        }
                        at = at.each;
                    }
                }
                if (end != path.length() || end == 1) {
                    throw new IllegalArgumentException("not a read path: " + path);
                }
            }
            return root;
        }
    }
}
