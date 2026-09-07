package com.opx.demon.terminal.component.extrakey

import android.content.Context
import android.os.Build
import io.neolang.frontend.ConfigVisitor
import com.opx.demon.terminal.App
import com.opx.demon.terminal.component.ConfigFileBasedComponent
import com.opx.demon.terminal.component.config.NeoTermPath
import com.opx.demon.terminal.frontend.session.view.extrakey.ExtraKeysView
import com.opx.demon.terminal.utils.NLog
import com.opx.demon.terminal.utils.extractAssetsDir
import java.io.File

class ExtraKeyComponent : ConfigFileBasedComponent<NeoExtraKey>(NeoTermPath.EKS_PATH) {
  override val checkComponentFileWhenObtained
    get() = true

  private val extraKeys: MutableMap<String, NeoExtraKey> = mutableMapOf()

  override fun onCheckComponentFiles() {
    val defaultFile = File(NeoTermPath.EKS_DEFAULT_FILE)
    if (!defaultFile.exists()) {
      extractDefaultConfig(App.get())
    }
    reloadExtraKeyConfig()
  }

  override fun onCreateComponentObject(configVisitor: ConfigVisitor): NeoExtraKey {
    return NeoExtraKey()
  }

  fun showShortcutKeys(program: String, extraKeysView: ExtraKeysView?) {
    if (extraKeysView == null) {
      return
    }

    val extraKey = extraKeys[program]
    if (extraKey != null) {
      extraKey.applyExtraKeys(extraKeysView)
      return
    }

    extraKeysView.loadDefaultUserKeys()
  }

  private fun registerShortcutKeys(extraKey: NeoExtraKey) =
    extraKey.programNames.forEach {
      extraKeys[it] = extraKey
    }

  private fun extractDefaultConfig(context: Context) {
    try {
      context.extractAssetsDir("eks", baseDir)
    } catch (e: Exception) {
      NLog.e("ExtraKey", "Failed to extract configure: ${e.localizedMessage}")
    }
  }

  private fun reloadExtraKeyConfig() {
    extraKeys.clear()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      File(baseDir)
        .listFiles(NEOLANG_FILTER).orEmpty()
        .filter { it.absolutePath != NeoTermPath.EKS_DEFAULT_FILE }
        .mapNotNull { this.loadConfigure(it) }
        .forEach {
          registerShortcutKeys(it)
        }
    }
  }
}
