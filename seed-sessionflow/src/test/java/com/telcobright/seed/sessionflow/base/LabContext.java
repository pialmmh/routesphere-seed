package com.telcobright.seed.sessionflow.base;

import com.telcobright.seed.sessionflow.api.RoutesphereContext;

/** The lab application's context: the base's, plus nothing but words (a Jackson POJO). */
public class LabContext extends RoutesphereContext {
    public String labNote;
    public LabContext() {}
}
