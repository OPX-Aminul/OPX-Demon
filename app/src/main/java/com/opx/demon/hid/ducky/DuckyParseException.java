package com.opx.demon.hid.ducky;

public final class DuckyParseException extends Exception {

    public final int line;

    public DuckyParseException(int line, String message) {
        super("line " + line + ": " + message);
        this.line = line;
    }
}
