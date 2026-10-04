package com.opx.demon.utils;

/**
 * Outbound links shown in the app (About, dashboard social row, bug report).
 *
 * <p>Ported from strykerapp 6.5, which keeps the same set of constants; the
 * targets are this project's own repository and site.
 */
public final class Links {

    public static final String SITE = "https://github.com/OPX-Aminul/OPX-Demon";

    public static final String GITHUB = "https://github.com/OPX-Aminul/OPX-Demon";

    public static final String ISSUES = GITHUB + "/issues/new";

    public static final String BLOG = "https://t.me/strykerapp";

    public static final String CHAT = "https://t.me/strykerchat";

    private Links() {
    }
}