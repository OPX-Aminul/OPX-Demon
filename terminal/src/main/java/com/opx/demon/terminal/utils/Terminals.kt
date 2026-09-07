package com.opx.demon.terminal.utils

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.opx.demon.terminal.backend.TerminalSession
import com.opx.demon.terminal.component.ComponentManager
import com.opx.demon.terminal.component.config.NeoPreference
import com.opx.demon.terminal.component.font.FontComponent
import com.opx.demon.terminal.component.session.SessionComponent
import com.opx.demon.terminal.component.session.ShellParameter
import com.opx.demon.terminal.component.session.XParameter
import com.opx.demon.terminal.component.session.XSession
import com.opx.demon.terminal.frontend.session.view.TerminalView
import com.opx.demon.terminal.frontend.session.view.TerminalViewClient
import com.opx.demon.terminal.frontend.session.view.extrakey.ExtraKeysView

object Terminals {
  fun setupTerminalView(terminalView: TerminalView?, terminalViewClient: TerminalViewClient? = null) {
    terminalView?.textSize = NeoPreference.getFontSize();

    val fontComponent = ComponentManager.getComponent<FontComponent>()
    fontComponent.applyFont(terminalView, null, fontComponent.getCurrentFont())

    if (terminalViewClient != null) {
      terminalView?.setTerminalViewClient(terminalViewClient)
    }
  }

  fun setupExtraKeysView(extraKeysView: ExtraKeysView?) {
    val fontComponent = ComponentManager.getComponent<FontComponent>()
    val font = fontComponent.getCurrentFont()
    fontComponent.applyFont(null, extraKeysView, font)
  }

  fun createSession(context: Context, parameter: ShellParameter): TerminalSession {
    val sessionComponent = ComponentManager.getComponent<SessionComponent>()
    return sessionComponent.createSession(context, parameter)
  }

  fun createSession(activity: AppCompatActivity, parameter: XParameter): XSession {
    val sessionComponent = ComponentManager.getComponent<SessionComponent>()
    return sessionComponent.createSession(activity, parameter)
  }

  fun escapeString(s: String?): String {
    if (s == null) {
      return ""
    }

    val builder = StringBuilder()
    val specialChars = "\"\\$`!"
    builder.append('"')
    val length = s.length
    for (i in 0 until length) {
      val c = s[i]
      if (specialChars.indexOf(c) >= 0) {
        builder.append('\\')
      }
      builder.append(c)
    }
    builder.append('"')
    return builder.toString()
  }
}
