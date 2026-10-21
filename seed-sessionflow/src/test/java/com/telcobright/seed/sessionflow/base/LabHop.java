package com.telcobright.seed.sessionflow.base;

import com.telcobright.statewalk.session.Route;

/** Where the lab call goes: a carrier by name. */
public record LabHop(String carrier) implements Route {}
