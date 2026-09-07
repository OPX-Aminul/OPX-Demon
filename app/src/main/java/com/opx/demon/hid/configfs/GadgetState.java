package com.opx.demon.hid.configfs;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public final class GadgetState {

    public final boolean opxdemonGadgetExists;
    public final boolean opxdemonGadgetBound;
    public final String boundUdc;
    public final Set<GadgetFunction> linkedFunctions;
    public final String massStorageFile;

    public GadgetState(boolean opxdemonGadgetExists,
                       boolean opxdemonGadgetBound,
                       String boundUdc,
                       Set<GadgetFunction> linkedFunctions,
                       String massStorageFile) {
        this.opxdemonGadgetExists = opxdemonGadgetExists;
        this.opxdemonGadgetBound = opxdemonGadgetBound;
        this.boundUdc = boundUdc;
        this.linkedFunctions = linkedFunctions == null
                ? Collections.unmodifiableSet(EnumSet.noneOf(GadgetFunction.class))
                : Collections.unmodifiableSet(EnumSet.copyOf(linkedFunctions));
        this.massStorageFile = massStorageFile;
    }
}
