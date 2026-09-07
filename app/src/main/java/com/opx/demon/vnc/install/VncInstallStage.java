package com.opx.demon.vnc.install;

import com.opx.demon.R;

public enum VncInstallStage {
    REFRESH(R.string.vnc_install_stage_refresh),
    PACKAGES(R.string.vnc_install_stage_packages),
    PASSWORD(R.string.vnc_install_stage_password),
    SCRIPTS(R.string.vnc_install_stage_scripts),
    VERIFY(R.string.vnc_install_stage_verify);

    public final int titleRes;

    VncInstallStage(int titleRes) {
        this.titleRes = titleRes;
    }
}
