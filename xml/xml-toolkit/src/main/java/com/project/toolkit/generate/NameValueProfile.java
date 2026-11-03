package com.project.toolkit.generate;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Per-field overrides to the generator's name-based value rules, loaded from a small YAML file:
 * <pre>
 * rules:
 *   PlcAndNm: xpath
 * </pre>
 * A field matched here skips {@link ValueGenerator}'s ordinary name-sniffing (e.g. "contains nm"
 * -> a person's name) in favor of the named rule. Unmatched fields behave exactly as before.
 */
final class NameValueProfile {

    enum Rule {
        /** A path-like string pointing at another element of the same message, e.g.
         * "/Document/CorpActnGnlInf" -- used for fields like PlcAndNm that name a *place in the
         * message*, not a person or location name despite what "Nm" suggests. */
        ELEMENT_PATH,
        DEFAULT
    }

    private final Map<String, Rule> rules;

    private NameValueProfile(Map<String, Rule> rules) {
        this.rules = rules;
    }

    /** PlcAndNm -> element-path is the one override every seev/supl message needs, regardless of
     * whether a custom profile is supplied, since "PlcAndNm" containing "Nm" is otherwise
     * indistinguishable from a true person/place name field to the ordinary name-sniffing rules. */
    static NameValueProfile defaultProfile() {
        Map<String, Rule> m = new HashMap<>();
        m.put("PlcAndNm", Rule.ELEMENT_PATH);
        return new NameValueProfile(m);
    }

    static NameValueProfile load(Path yamlFile) throws IOException {
        Map<String, Rule> m = new HashMap<>(defaultProfile().rules);
        Yaml yaml = new Yaml();
        try (InputStream in = Files.newInputStream(yamlFile)) {
            Object parsed = yaml.load(in);
            if (parsed instanceof Map<?, ?> doc && doc.get("rules") instanceof Map<?, ?> rulesMap) {
                for (var entry : rulesMap.entrySet()) {
                    m.put(String.valueOf(entry.getKey()), parseRule(String.valueOf(entry.getValue())));
                }
            }
        }
        return new NameValueProfile(m);
    }

    private static Rule parseRule(String value) {
        return "xpath".equalsIgnoreCase(value) || "element-path".equalsIgnoreCase(value)
                ? Rule.ELEMENT_PATH : Rule.DEFAULT;
    }

    Rule ruleFor(String elementLocalName) {
        return rules.getOrDefault(elementLocalName, Rule.DEFAULT);
    }
}
