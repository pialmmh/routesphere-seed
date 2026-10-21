package com.telcobright.seed.sessionflow.samples.sms;

import com.telcobright.statewalk.session.Route;

/** The SMS's hop: the SMSC (an SMPP bind) the message is submitted to — the route's name and its supplier for the CDR. */
public record Smsc(String smscId, int supplierId, String bind) implements Route {}
