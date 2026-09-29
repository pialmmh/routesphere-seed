package com.telcobright.seed.callflow.spi;

import com.telcobright.seed.callflow.api.AdAdmission;
import com.telcobright.seed.callflow.api.AdCallPayload;
import com.telcobright.statewalk.pipeline.StepMode;

/**
 * The chain admission of ONE candidate payload (design §2.3, contract item 2): the entry tenant from the payer, the ancestor
 * chain leaf → root, and at every tier the partner, its status, its concurrent cap, the rate on THAT tier's plan and one
 * DEBIT; a later tier's refusal credits the earlier tiers back. {@code SIMULATE} rates and walks the chain but never debits.
 */
public interface AdAdmissionPort {

    AdAdmission admit(AdCallPayload payload, String requestId, StepMode mode);

    /** Credit every tier of an admitted candidate back (reason {@code compensation:<why>}) — the one automatic refund. */
    void compensate(AdAdmission admission, String requestId, String why);
}
