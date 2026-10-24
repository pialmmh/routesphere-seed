package com.telcobright.seed.sessionflow.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-M · {@code api.LogText} is a thin delegate of seed-logtext's {@code LogText} (the cleaner moved to the seed's bottom so seed-campaign
 * reaches it): the same answers, the same caps — a lane's import of the api one keeps working. The cleaner's own tests are seed-logtext's.
 */
class LogTextTest {

    @Test
    void theApisLogText_answersAsTheBottomOne() {
        String forged = "d-1\n[app] forged " + "x".repeat(300);
        IllegalStateException failure = new IllegalStateException("bad\nline");

        assertThat(LogText.clean(forged)).isEqualTo(com.telcobright.seed.logtext.LogText.clean(forged)).doesNotContain("\n");
        assertThat(LogText.failureText(failure)).isEqualTo(com.telcobright.seed.logtext.LogText.failureText(failure));
        assertThat(LogText.cleaned(failure).toString()).isEqualTo(com.telcobright.seed.logtext.LogText.cleaned(failure).toString());
        assertThat(LogText.CAP).isEqualTo(com.telcobright.seed.logtext.LogText.CAP).isEqualTo(200);
        assertThat(LogText.FAILURE_CAP).isEqualTo(com.telcobright.seed.logtext.LogText.FAILURE_CAP).isEqualTo(1000);
    }
}
