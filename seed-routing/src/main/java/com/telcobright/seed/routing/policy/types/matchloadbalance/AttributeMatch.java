package com.telcobright.seed.routing.policy.types.matchloadbalance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.telcobright.seed.routing.policy.PolicyFormatException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * How ONE attribute of a request is matched by a rule. A small CLOSED set of forms, so a future screen can offer
 * them as a drop-down and the JSON stays readable by hand:
 *
 * <pre>
 *   "btcl"                       equals (case-insensitive)
 *   "*"                          anything, present or not
 *   ["zone0", "uttara"]          one of
 *   {"prefix": ["88017","88013"]}  starts with one of   (the dialplan's prefix, as a rule)
 *   {"range": [10, 500]}         a number within, both ends included (null = open end)
 *   {"present": false}           the request does not carry the attribute
 *   {"not": &lt;any form above&gt;}    the opposite
 * </pre>
 *
 * An attribute the request does not carry matches only {@code "*"}, {@code present:false} and a {@code not}.
 */
public sealed interface AttributeMatch {

    boolean matches(String value);

    /** How specific the form is — the tie-break between two rules of one priority (an exact value beats a list beats "*"). */
    int specificity();

    JsonNode toJson();

    /** The form in words, for the trace a person reads ("wanted one of [zone0, uttara]"). */
    String describe();

    /** The exact values this form pins the attribute to (lower case) — what an index can key on; empty = none. */
    default List<String> pinned() { return List.of(); }

    record Any() implements AttributeMatch {
        @Override public boolean matches(String value) { return true; }
        @Override public int specificity() { return 0; }
        @Override public JsonNode toJson() { return JsonNodeFactory.instance.textNode("*"); }
        @Override public String describe() { return "anything"; }
    }

    record Eq(String wanted) implements AttributeMatch {
        @Override public boolean matches(String value) { return value != null && value.equalsIgnoreCase(wanted); }
        @Override public int specificity() { return 4; }
        @Override public JsonNode toJson() { return JsonNodeFactory.instance.textNode(wanted); }
        @Override public String describe() { return "'" + wanted + "'"; }
        @Override public List<String> pinned() { return List.of(wanted.toLowerCase(Locale.ROOT)); }
    }

    record In(List<String> wanted) implements AttributeMatch {
        public In { wanted = List.copyOf(wanted); }
        @Override public boolean matches(String value) {
            if (value == null) return false;
            for (String w : wanted) if (value.equalsIgnoreCase(w)) return true;
            return false;
        }
        @Override public int specificity() { return 3; }
        @Override public JsonNode toJson() {
            ArrayNode a = JsonNodeFactory.instance.arrayNode();
            wanted.forEach(a::add);
            return a;
        }
        @Override public String describe() { return "one of " + wanted; }
        @Override public List<String> pinned() { return wanted.stream().map(w -> w.toLowerCase(Locale.ROOT)).distinct().toList(); }
    }

    record Prefix(List<String> prefixes) implements AttributeMatch {
        public Prefix { prefixes = List.copyOf(prefixes); }
        @Override public boolean matches(String value) {
            if (value == null) return false;
            String v = value.toLowerCase(Locale.ROOT);
            for (String p : prefixes) if (v.startsWith(p.toLowerCase(Locale.ROOT))) return true;
            return false;
        }
        /** The longest prefix this form holds: a longer prefix is the more specific rule, as in a dialplan. */
        public int longest() { return prefixes.stream().mapToInt(String::length).max().orElse(0); }
        @Override public int specificity() { return 2; }
        @Override public JsonNode toJson() {
            ObjectNode o = JsonNodeFactory.instance.objectNode();
            ArrayNode a = o.putArray("prefix");
            prefixes.forEach(a::add);
            return o;
        }
        @Override public String describe() { return "starting with one of " + prefixes; }
    }

    record Range(BigDecimal min, BigDecimal max) implements AttributeMatch {
        @Override public boolean matches(String value) {
            if (value == null) return false;
            try {
                BigDecimal v = new BigDecimal(value.trim());
                return (min == null || v.compareTo(min) >= 0) && (max == null || v.compareTo(max) <= 0);
            } catch (NumberFormatException e) {
                return false;
            }
        }
        @Override public int specificity() { return 2; }
        @Override public JsonNode toJson() {
            ObjectNode o = JsonNodeFactory.instance.objectNode();
            ArrayNode a = o.putArray("range");
            if (min == null) a.addNull(); else a.add(min);
            if (max == null) a.addNull(); else a.add(max);
            return o;
        }
        @Override public String describe() { return "a number within [" + (min == null ? "…" : min.toPlainString()) + ", " + (max == null ? "…" : max.toPlainString()) + "]"; }
    }

    record Present(boolean wanted) implements AttributeMatch {
        @Override public boolean matches(String value) { return (value != null) == wanted; }
        @Override public int specificity() { return 1; }
        @Override public JsonNode toJson() { return JsonNodeFactory.instance.objectNode().put("present", wanted); }
        @Override public String describe() { return wanted ? "present" : "absent"; }
    }

    record Not(AttributeMatch inner) implements AttributeMatch {
        @Override public boolean matches(String value) { return !inner.matches(value); }
        @Override public int specificity() { return 1; }
        @Override public JsonNode toJson() {
            ObjectNode o = JsonNodeFactory.instance.objectNode();
            o.set("not", inner.toJson());
            return o;
        }
        @Override public String describe() { return "not " + inner.describe(); }
    }

    /** Read one form; {@code where} names the place in the document for the error message a person will read. */
    static AttributeMatch parse(JsonNode n, String where) {
        if (n == null || n.isNull()) throw new PolicyFormatException(where + ": a match value is missing (use \"*\" for anything)");
        if (n.isTextual() || n.isNumber() || n.isBoolean()) {
            String s = n.asText().trim();
            if (s.isEmpty()) throw new PolicyFormatException(where + ": an empty value never matches (use \"*\" for anything)");
            return "*".equals(s) ? new Any() : new Eq(s);
        }
        if (n.isArray()) {
            List<String> values = strings(n, where);
            if (values.contains("*")) return new Any();
            return new In(values);
        }
        if (n.isObject()) {
            if (n.size() != 1) throw new PolicyFormatException(where + ": an operator object holds exactly one of prefix | range | present | not");
            String op = n.fieldNames().next();
            JsonNode arg = n.get(op);
            return switch (op) {
                case "prefix" -> new Prefix(arg.isArray() ? strings(arg, where + ".prefix") : List.of(text(arg, where + ".prefix")));
                case "range" -> {
                    if (!arg.isArray() || arg.size() != 2) throw new PolicyFormatException(where + ".range: expected [min, max] (null = open end)");
                    BigDecimal lo = number(arg.get(0), where + ".range[0]");
                    BigDecimal hi = number(arg.get(1), where + ".range[1]");
                    if (lo != null && hi != null && lo.compareTo(hi) > 0) throw new PolicyFormatException(where + ".range: min is above max");
                    yield new Range(lo, hi);
                }
                case "present" -> {
                    if (!arg.isBoolean()) throw new PolicyFormatException(where + ".present: expected true or false");
                    yield new Present(arg.asBoolean());
                }
                case "not" -> new Not(parse(arg, where + ".not"));
                default -> throw new PolicyFormatException(where + ": unknown operator '" + op + "' (known: prefix, range, present, not)");
            };
        }
        throw new PolicyFormatException(where + ": expected a value, a list or an operator object");
    }

    private static List<String> strings(JsonNode array, String where) {
        List<String> out = new ArrayList<>();
        for (JsonNode e : array) out.add(text(e, where + "[]"));
        if (out.isEmpty()) throw new PolicyFormatException(where + ": an empty list never matches");
        return out;
    }

    private static String text(JsonNode n, String where) {
        if (n == null || n.isNull() || n.isContainerNode() || n.asText().isBlank()) throw new PolicyFormatException(where + ": expected a value");
        return n.asText().trim();
    }

    private static BigDecimal number(JsonNode n, String where) {
        if (n == null || n.isNull()) return null;
        try {
            return new BigDecimal(n.asText().trim());
        } catch (NumberFormatException e) {
            throw new PolicyFormatException(where + ": expected a number");
        }
    }
}
