package com.telcobright.seed.sessionflow;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-O item 4 — the guard that keeps the owner's strict rule kept ("avoiding a LIST lookup on the hot path is a STRICT rule for partner and
 * tenant identification", 2026-10-25; the architect's A8 b). The MAIN sources of seed-sessionflow, seed-tenant, seed-routing and (L2a, the
 * base's money steps moved there) seed-prepaid are scanned
 * for a walk — a {@code for (…)} over {@code partners()}, {@code routes()}, {@code children()}, {@code tiers()} or {@code tenants()}, any
 * {@code .stream(} or {@code .forEach(} — and every one must stand in {@code docs/agents/hot-path-allowlist.txt} by its
 * {@code Class#method} with a reason (the index builder, the install, a road that is not a session's). A new walk in a flow class is red,
 * with its file:line; an allow-list entry that names no walk any more is red too (the list stays true).
 *
 * <p>The pattern is A8's, with the five names matched WITHOUT case — so {@code getPartners()}, {@code getChildren()} and {@code getRoutes()}
 * count: as written (lower case only) it would not see the very loop P1-O removed ({@code for (Partner p : parent.getContext()
 * .getPartners().values())}). Comments, strings and text blocks are not scanned; a lambda belongs to the method it is written in.
 */
class HotPathHasNoListScanTest {

    static final Pattern WALK = Pattern.compile("\\bfor\\s*\\(.*(?i:partners|routes|children|tiers|tenants)\\(\\)|\\.(stream|forEach)\\(");
    /** L2a: seed-prepaid's main sources too — the base's money steps (admission's hold among them) moved there. */
    static final List<String> MODULES = List.of("seed-sessionflow", "seed-tenant", "seed-routing", "seed-prepaid");
    static final String ALLOW_LIST = "docs/agents/hot-path-allowlist.txt";

    /** One walk: where, in which class and method, and the line. */
    record Walk(String file, int line, String owner, String text) {
        @Override public String toString() { return file + ":" + line + "  " + owner + "  | " + text.strip(); }
    }

    @Test
    void no_main_class_walks_a_list_outside_the_allow_list_and_the_allow_list_names_only_real_walks() throws IOException {
        Path reactor = reactorRoot();
        Map<String, String> allowed = allowList(reactor.resolve(ALLOW_LIST));
        List<Walk> walks = new ArrayList<>();
        for (String module : MODULES) walks.addAll(walksIn(reactor, reactor.resolve(module).resolve("src/main/java")));

        List<Walk> outside = walks.stream().filter(w -> !isAllowed(w.owner(), allowed)).toList();
        assertThat(outside).as("a walk on the hot path (P1-O): use the snapshot's index, or name it in " + ALLOW_LIST
            + " as Class#method — reason").isEmpty();
        List<String> stale = allowed.keySet().stream().filter(entry -> walks.stream().noneMatch(w -> matches(entry, w.owner()))).toList();
        assertThat(stale).as("allow-list entries that name no walk any more: remove them").isEmpty();
    }

    @Test
    void the_scanner_names_the_class_and_method_of_a_walk_and_skips_comments_strings_and_text_blocks() {
        String source = """
            package x;
            class Climb {
                private final java.util.List<String> names = java.util.List.of("a").stream().toList();
                Climb() { }
                static String standsFor(Tenant parent) {
                    // for (Partner p : parent.getContext().getPartners().values()) — a comment
                    String quoted = "names.forEach(x)";
                    for (Partner p : parent.getContext().getPartners().values()) { if (p != null) return p.getPartnerName(); }
                    return null;
                }
                record Line(String a) {
                    Line { java.util.List.of(a).forEach(System.out::println); }
                }
                void later() { Runnable r = () -> java.util.List.of().stream().count(); }
            }
            """.replace("Tenant parent", "Object parent");
        List<String> found = walksInSource("Climb.java", source).stream().map(w -> w.owner() + "@" + w.line()).toList();
        assertThat(found).containsExactly("Climb#<field>@3", "Climb#standsFor@8", "Climb.Line#Line@12", "Climb#later@14");
    }

    // ── the scan ──────────────────────────────────────────────────────────

    static List<Walk> walksIn(Path reactor, Path sources) throws IOException {
        List<Walk> walks = new ArrayList<>();
        try (Stream<Path> files = Files.walk(sources)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                walks.addAll(walksInSource(reactor.relativize(file).toString(), Files.readString(file, StandardCharsets.UTF_8)));
            }
        }
        return walks;
    }

    /** Every walk of one source, each with the class and method it is written in. */
    static List<Walk> walksInSource(String file, String source) {
        String code = withoutCommentsAndStrings(source);
        TreeMap<Integer, String> ownerFrom = owners(code);
        List<Walk> walks = new ArrayList<>();
        String[] lines = code.split("\n", -1), original = source.split("\n", -1);
        int offset = 0;
        for (int i = 0; i < lines.length; i++) {
            Matcher m = WALK.matcher(lines[i]);
            if (m.find()) walks.add(new Walk(file, i + 1, ownerFrom.floorEntry(offset + m.start()).getValue(), original[i]));
            offset += lines[i].length() + 1;
        }
        return walks;
    }

    /** The source with every comment, string, char and text block blanked (newlines kept), so a brace or a word in them counts for nothing. */
    static String withoutCommentsAndStrings(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            if (s.startsWith("//", i)) { while (i < s.length() && s.charAt(i) != '\n') { out.append(' '); i++; } }
            else if (s.startsWith("/*", i)) { int end = s.indexOf("*/", i + 2); end = end < 0 ? s.length() : end + 2; blank(s, i, end, out); i = end; }
            else if (s.startsWith("\"\"\"", i)) { int end = s.indexOf("\"\"\"", i + 3); end = end < 0 ? s.length() : end + 3; blank(s, i, end, out); i = end; }
            else if (s.charAt(i) == '"' || s.charAt(i) == '\'') { int end = closingQuote(s, i); blank(s, i, end, out); i = end; }
            else { out.append(s.charAt(i)); i++; }
        }
        return out.toString();
    }

    private static int closingQuote(String s, int start) {
        char quote = s.charAt(start);
        int i = start + 1;
        while (i < s.length() && s.charAt(i) != quote && s.charAt(i) != '\n') i += s.charAt(i) == '\\' ? 2 : 1;
        return Math.min(i + 1, s.length());
    }

    private static void blank(String s, int from, int to, StringBuilder out) { for (int i = from; i < to; i++) out.append(s.charAt(i) == '\n' ? '\n' : ' '); }

    /** Where each scope starts: offset → {@code Class#method} ({@code <field>} at a class's own level, {@code <static>} in a static block). */
    static TreeMap<Integer, String> owners(String code) {
        TreeMap<Integer, String> ownerFrom = new TreeMap<>();
        Deque<String[]> scopes = new ArrayDeque<>();                   // {kind, name}: class · method · block
        ownerFrom.put(0, "<file>#<file>");
        StringBuilder header = new StringBuilder();
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') { scopes.push(scopeOf(header.toString(), scopes)); header.setLength(0); ownerFrom.put(i + 1, ownerOf(scopes)); }
            else if (c == '}') { if (!scopes.isEmpty()) scopes.pop(); header.setLength(0); ownerFrom.put(i + 1, ownerOf(scopes)); }
            else if (c == ';') header.setLength(0);
            else header.append(c);
        }
        return ownerFrom;
    }

    private static final Pattern TYPE = Pattern.compile("\\b(class|interface|enum|record)\\s+(\\w+)");
    private static final Pattern METHOD = Pattern.compile("(\\w+)\\s*\\((?:[^()]|\\([^()]*\\))*\\)\\s*(?:throws\\s+[\\w.,\\s<>]+)?$");
    private static final Set<String> NOT_A_METHOD = Set.of("if", "for", "while", "switch", "catch", "synchronized", "try", "return", "new", "else", "do");

    private static String[] scopeOf(String rawHeader, Deque<String[]> scopes) {
        String header = rawHeader.replaceAll("@\\w+(\\s*\\((?:[^()]|\\([^()]*\\))*\\))?", " ").replaceAll("\\s+", " ").strip();
        Matcher type = TYPE.matcher(header);
        if (type.find() && !header.contains("new ")) return new String[] {"class", type.group(2)};
        boolean classLevel = !scopes.isEmpty() && scopes.peek()[0].equals("class");
        if (!classLevel) return new String[] {"block", ""};
        Matcher method = METHOD.matcher(header);
        if (method.find() && !NOT_A_METHOD.contains(method.group(1)) && !header.contains("new ") && !header.contains("=")) return new String[] {"method", method.group(1)};
        if (header.equals("static")) return new String[] {"method", "<static>"};
        String last = header.isEmpty() ? "" : header.substring(header.lastIndexOf(' ') + 1);
        if (last.equals(scopes.peek()[1])) return new String[] {"method", last};          // a record's compact constructor
        return new String[] {"block", ""};
    }

    private static String ownerOf(Deque<String[]> scopes) {
        List<String> classes = new ArrayList<>();
        String method = null;
        for (String[] scope : scopes) {                                 // innermost first
            if (scope[0].equals("method") && method == null && classes.isEmpty()) method = scope[1];
            if (scope[0].equals("class")) classes.add(0, scope[1]);
        }
        if (classes.isEmpty()) return "<file>#<file>";
        return String.join(".", classes) + "#" + (method != null ? method : "<field>");
    }

    // ── the allow-list ────────────────────────────────────────────────────

    /** {@code Class#method — reason} per line ({@code Class#*}: every method of the class); a line starting with {@code # } is a comment. */
    static Map<String, String> allowList(Path file) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String entry = line.strip();
            if (entry.isEmpty() || entry.startsWith("# ")) continue;
            int dash = entry.indexOf(" — ");
            assertThat(dash).as("an allow-list entry is 'Class#method — reason': " + entry).isPositive();
            entries.put(entry.substring(0, dash).strip(), entry.substring(dash + 3).strip());
        }
        return entries;
    }

    private static boolean isAllowed(String owner, Map<String, String> allowed) { return allowed.keySet().stream().anyMatch(entry -> matches(entry, owner)); }

    private static boolean matches(String entry, String owner) {
        return entry.endsWith("#*") ? owner.startsWith(entry.substring(0, entry.length() - 1)) : entry.equals(owner);
    }

    private static Path reactorRoot() {
        Path here = Path.of("").toAbsolutePath();
        while (here != null && !(Files.isDirectory(here.resolve("seed-sessionflow")) && Files.isDirectory(here.resolve("docs/agents")))) here = here.getParent();
        assertThat(here).as("the reactor root (seed-sessionflow beside docs/agents) above " + Path.of("").toAbsolutePath()).isNotNull();
        return here;
    }
}
