package com.telcobright.seed.callflow.api;

/**
 * The answer of the PREPROCESSING hook: ok (→ ADMITTING), a refusal with its cause (→ FAILED, the failed CDR says why),
 * or PENDING ({@link #later()}) — the hook handed the work to something asynchronous that re-enters the machine with a
 * {@code Preprocessed} event through the registry; the state's timeout ends a pending that never answers
 * ({@code PREPROCESS_TIMEOUT}).
 */
public record PreprocessVerdict(Kind kind, String cause) {

    public enum Kind { OK, REFUSED, PENDING }

    private static final PreprocessVerdict OK_VERDICT = new PreprocessVerdict(Kind.OK, null);
    private static final PreprocessVerdict PENDING_VERDICT = new PreprocessVerdict(Kind.PENDING, null);

    public static PreprocessVerdict accept() { return OK_VERDICT; }
    public static PreprocessVerdict later() { return PENDING_VERDICT; }
    public static PreprocessVerdict refuse(String cause) { return new PreprocessVerdict(Kind.REFUSED, cause); }
    public static PreprocessVerdict refuse(AdCause cause) { return refuse(cause.name()); }

    public boolean ok() { return kind == Kind.OK; }
    public boolean pending() { return kind == Kind.PENDING; }
}
