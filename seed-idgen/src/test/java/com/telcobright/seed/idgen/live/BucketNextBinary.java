package com.telcobright.seed.idgen.live;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * The id service's binary the live tests start: the {@code tenant-first} line of bucket-next (git@github.com:pialmmh/bucket-next),
 * built ONCE per JVM from a {@code git archive} of the ref into a TEMP directory — never the repository's tree, never its {@code bin/}
 * (the bed's pre-tenant binary lives there) — and removed when the JVM ends.
 *
 * <ul>
 *   <li>{@code -Dbucketnext.bin=<path>} names a built binary outright: used as is, nothing built;</li>
 *   <li>{@code -Dbucketnext.repo=<path>} (default {@code ~/telcobright-projects/bucket-next}) and {@code -Dbucketnext.ref=<ref>}
 *       (default {@code tenant-first}) say what to archive and build.</li>
 * </ul>
 * A test SKIPS when nothing can be built here ({@link #whyNot}: no repository, no {@code go}); a build that FAILS with both present is
 * an error, never a skip (its log is in the message).
 */
public final class BucketNextBinary {

    public static final Path REPO = Path.of(System.getProperty("bucketnext.repo", System.getProperty("user.home") + "/telcobright-projects/bucket-next"));
    public static final String REF = System.getProperty("bucketnext.ref", "tenant-first");
    private static final String NAMED = System.getProperty("bucketnext.bin");

    private static Path built;

    private BucketNextBinary() {}

    /** Empty when a binary can be had here; else the one reason a live test skips. */
    public static Optional<String> whyNot() {
        if (NAMED != null) return Files.isExecutable(Path.of(NAMED)) ? Optional.empty() : Optional.of("no executable at -Dbucketnext.bin=" + NAMED);
        if (!Files.isDirectory(REPO.resolve(".git"))) return Optional.of("no bucket-next repository at " + REPO + " (-Dbucketnext.repo=<path>)");
        if (onPath("go") == null) return Optional.of("no go toolchain on PATH to build bucket-next " + REF);
        if (onPath("git") == null) return Optional.of("no git on PATH to archive bucket-next " + REF);
        return Optional.empty();
    }

    public static boolean available() { return whyNot().isEmpty(); }

    /** The binary: the named one, else the one built here (once). */
    public static synchronized Path path() {
        if (NAMED != null) return Path.of(NAMED);
        if (built == null) built = build();
        return built;
    }

    private static Path build() {
        whyNot().ifPresent(why -> { throw new IllegalStateException(why); });
        try {
            Path dir = Files.createTempDirectory("bucket-next-" + REF.replaceAll("[^A-Za-z0-9._-]", "_") + "-");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteTree(dir)));
            Path src = Files.createDirectories(dir.resolve("src"));
            Path bin = dir.resolve("bucket-next");
            String sha = run(dir, "rev-parse", List.of("git", "-C", REPO.toString(), "rev-parse", "--short", REF)).strip();
            run(dir, "archive", List.of("git", "-C", REPO.toString(), "archive", "--output=" + dir.resolve("src.tar"), REF));
            run(dir, "untar", List.of("tar", "-xf", dir.resolve("src.tar").toString(), "-C", src.toString()));
            run(src, "go-build", List.of("go", "build", "-o", bin.toString(), "./cmd/bucket-next"));
            if (!Files.isExecutable(bin)) throw new IllegalStateException("go build left no executable at " + bin);
            System.out.println("BucketNextBinary: bucket-next " + REF + " (" + sha + ") built from a git archive into " + dir);
            return bin;
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("building bucket-next " + REF + " from " + REPO + ": " + e, e);
        }
    }

    /** One step of the build, its output kept; a non-zero exit is an error naming the step and showing the output. */
    private static String run(Path cwd, String step, List<String> command) throws IOException, InterruptedException {
        Path log = cwd.resolve(step + ".log");
        ProcessBuilder pb = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        pb.environment().put("GOFLAGS", "-mod=mod");
        Process p = pb.start();
        if (!p.waitFor(5, TimeUnit.MINUTES)) { p.destroyForcibly(); throw new IllegalStateException(step + " of bucket-next " + REF + " did not finish in 5 min"); }
        String out = Files.readString(log);
        if (p.exitValue() != 0) throw new IllegalStateException(step + " of bucket-next " + REF + " failed (exit " + p.exitValue() + "): " + String.join(" ", command) + "\n" + out);
        return out;
    }

    private static String onPath(String tool) {
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String dir : path.split(java.io.File.pathSeparator)) if (Files.isExecutable(Path.of(dir, tool))) return Path.of(dir, tool).toString();
        return null;
    }

    private static void deleteTree(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) { } });
        } catch (IOException ignored) {
            // the temp directory is the JVM's to lose
        }
    }
}
