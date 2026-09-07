package com.opx.demon.terminal;

import com.opx.demon.terminal.xorg.NeoXorgViewClient;

public class NeoXorgSettings {
  public static void init(NeoXorgViewClient client) {
    Settings.Load(client);
  }
}
