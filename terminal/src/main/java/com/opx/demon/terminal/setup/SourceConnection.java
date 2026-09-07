package com.opx.demon.terminal.setup;

import java.io.IOException;
import java.io.InputStream;

public interface SourceConnection {
  InputStream getInputStream() throws IOException;
  int getSize();
  void close();
}
