package app.recly.windows.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.window.FrameWindowScope
import app.recly.windows.core.Host
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinReg
import com.sun.jna.ptr.IntByReference
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

/*
 * docs/09 "Accessibility" · "Disabled, focus and structure": the Windows settings the app follows besides high contrast
 * ([systemHighContrast]) — the animation switch, the text size, and the title bar's light or dark. None of them has an
 * AWT desktop property, so they are read through JNA, and only on Windows: every other host (the macOS development one
 * included) gets null, which reads as "animations on, text at 100 %".
 */

/**
 * Whether Windows animates ("Animation effects" in Settings → Accessibility → Visual effects): the
 * `SPI_GETCLIENTAREAANIMATION` flag. Null off Windows or when the call fails.
 */
fun systemAnimationsOn(): Boolean? {
    if (!Host.isWindows) return null
    return runCatching {
        val on = IntByReference()
        if (User32Settings.lib.SystemParametersInfoW(SPI_GETCLIENTAREAANIMATION, 0, on, 0)) on.value != 0 else null
    }.getOrNull()
}

/** docs/09 "Motion": motion is reduced only when the system said it is off — a machine that did not say animates. */
fun reduceMotionOf(animationsOn: Boolean?): Boolean = animationsOn == false

/**
 * The text size of Settings → Accessibility → Text size: `HKCU\SOFTWARE\Microsoft\Accessibility\TextScaleFactor`, a
 * percentage. Null where there is no such value — off Windows, or a Windows that was never moved off 100.
 */
fun systemTextScale(): Int? {
    if (!Host.isWindows) return null
    return runCatching {
        if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, TEXT_SCALE_KEY, TEXT_SCALE_VALUE)) {
            Advapi32Util.registryGetIntValue(WinReg.HKEY_CURRENT_USER, TEXT_SCALE_KEY, TEXT_SCALE_VALUE)
        } else {
            null
        }
    }.getOrNull()
}

/** The font scale [percent] asks for, inside the 100–225 range Windows offers; 1 when nothing was said. */
fun textScaleOf(percent: Int?): Float = (percent ?: 100).coerceIn(100, 225) / 100f

/**
 * The title bar of a decorated window (Details, Settings) in the app's own light or dark — the Theme setting can differ
 * from the system's, and Windows draws the bar from the system's unless the window says otherwise
 * (`DWMWA_USE_IMMERSIVE_DARK_MODE`). Set as soon as the window has a handle, and again whenever [dark] changes; a
 * failure leaves the bar as Windows drew it.
 */
@Composable
fun FrameWindowScope.TitleBarTheme(dark: Boolean) {
    DisposableEffect(window, dark) {
        if (!Host.isWindows) return@DisposableEffect onDispose {}
        val frame = window
        if (frame.isDisplayable) applyTitleBarTheme(frame, dark)
        val opened = object : WindowAdapter() {
            override fun windowOpened(event: WindowEvent) = applyTitleBarTheme(frame, dark)
        }
        frame.addWindowListener(opened)
        onDispose { frame.removeWindowListener(opened) }
    }
}

private fun applyTitleBarTheme(window: java.awt.Window, dark: Boolean) {
    runCatching {
        val hwnd = HWND(Native.getComponentPointer(window))
        Dwmapi.lib.DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, IntByReference(if (dark) 1 else 0), Int.SIZE_BYTES)
    }
}

/** `jna-platform`'s `User32` has no `SystemParametersInfo`, so this is the one function, as `WindowsCredentialStore` does. */
private interface User32Settings : Library {
    fun SystemParametersInfoW(action: Int, param: Int, value: IntByReference, winIni: Int): Boolean

    companion object {
        /** Loaded on first use, which on any machine but Windows is never. */
        val lib: User32Settings by lazy { Native.load("user32", User32Settings::class.java) }
    }
}

/** Nor has it a `dwmapi` binding. */
private interface Dwmapi : Library {
    fun DwmSetWindowAttribute(hwnd: HWND, attribute: Int, value: IntByReference, size: Int): Int

    companion object {
        val lib: Dwmapi by lazy { Native.load("dwmapi", Dwmapi::class.java) }
    }
}

private const val SPI_GETCLIENTAREAANIMATION = 0x1042
private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
private const val TEXT_SCALE_KEY = "SOFTWARE\\Microsoft\\Accessibility"
private const val TEXT_SCALE_VALUE = "TextScaleFactor"
