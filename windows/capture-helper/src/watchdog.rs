//! docs/01 "Long recordings — watchdog" on Windows. There is no server and no alarm to schedule ahead, so
//! when the app dies under a recording the one process left to notice is this one: the helper outlives
//! the app it was spawned by, closes the segment it was writing (the next launch's recovery files it),
//! and says so — `Recording may have stopped` / `Open Recly to check.`, in the app's language, handed
//! over with the `start` command ([`crate::protocol::Notice`]).
//!
//! The notice is a balloon on a tray icon of the helper's own (`Shell_NotifyIconW`, which Windows 10
//! and 11 show as a toast) — the mechanism the app's AWT balloons already use, so it needs no app
//! identity registered with the shell. A balloon belongs to its icon — taking the icon down can take
//! the notice with it — so the helper keeps the icon until the person clicks the balloon or the icon,
//! or [`LINGER`] has passed, and only then exits.

use std::time::Duration;

use crate::protocol::Notice;

/// How long the notice is kept for a person who is away from the PC — a working day and a night.
pub const LINGER: Duration = Duration::from_secs(12 * 60 * 60);

/// Whether the app is gone from under a recording. A `stop` never gets here. Without one, stdin closing
/// alone is the app letting this helper go (`HelperClient.close`) while it still reads stdout; a line
/// that can no longer be written to stdout is the app gone — its end of the pipe closed with its process.
pub fn app_gone(recording: bool, output_closed: bool) -> bool {
    recording && output_closed
}

/// `Shell_NotifyIconW`'s callback events (`NOTIFYICON_VERSION_4`: the low word of `lParam`) that end the
/// notice: the person clicked the balloon, or the icon. A balloon that timed out has gone to the
/// notification centre, and is kept there.
pub fn dismissed(event: u32) -> bool {
    matches!(
        event,
        NIN_SELECT | NIN_KEYSELECT | NIN_BALLOONUSERCLICK | WM_LBUTTONUP | WM_CONTEXTMENU
    )
}

const NIN_SELECT: u32 = 0x400;
const NIN_KEYSELECT: u32 = 0x401;
const NIN_BALLOONUSERCLICK: u32 = 0x405;
const WM_LBUTTONUP: u32 = 0x202;
const WM_CONTEXTMENU: u32 = 0x7b;

/// UTF-16 for one of `NOTIFYICONDATAW`'s fixed fields: cut to fit, always terminated.
pub fn wide<const N: usize>(text: &str) -> [u16; N] {
    let mut out = [0u16; N];
    for (slot, unit) in out.iter_mut().take(N - 1).zip(text.encode_utf16()) {
        *slot = unit;
    }
    out
}

/// Raises [notice] and waits for it to be dismissed (Windows). On the development host there is no tray
/// to raise it in, and the stderr line the caller wrote is all there is.
pub fn raise(notice: &Notice) {
    #[cfg(windows)]
    {
        if let Err(error) = tray::show(notice, LINGER) {
            use std::io::Write;
            let _ = writeln!(std::io::stderr(), "recly-capture-helper: rec.watchdog.unshown {error}");
        }
    }
    #[cfg(not(windows))]
    {
        let _ = notice;
    }
}

#[cfg(windows)]
mod tray {
    use std::time::Duration;

    use windows::core::{w, Result};
    use windows::Win32::Foundation::{HWND, LPARAM, LRESULT, WPARAM};
    use windows::Win32::System::LibraryLoader::GetModuleHandleW;
    use windows::Win32::UI::Shell::{
        Shell_NotifyIconW, NIF_ICON, NIF_INFO, NIF_MESSAGE, NIF_SHOWTIP, NIF_TIP, NIIF_WARNING,
        NIM_ADD, NIM_DELETE, NIM_MODIFY, NIM_SETVERSION, NOTIFYICONDATAW, NOTIFYICONDATAW_0,
        NOTIFYICON_VERSION_4,
    };
    use windows::Win32::UI::WindowsAndMessaging::{
        CreateWindowExW, DefWindowProcW, DestroyWindow, DispatchMessageW, GetMessageW, LoadIconW,
        PostQuitMessage, RegisterClassW, SetTimer, TranslateMessage, IDI_WARNING, MSG, WINDOW_EX_STYLE,
        WM_APP, WM_TIMER, WNDCLASSW, WS_OVERLAPPED,
    };

    use crate::protocol::Notice;

    /// The icon's callback message.
    const CALLBACK: u32 = WM_APP + 1;

    pub fn show(notice: &Notice, linger: Duration) -> Result<()> {
        unsafe {
            let instance = GetModuleHandleW(None)?;
            let class = w!("ReclyCaptureHelperWatchdog");
            let window_class = WNDCLASSW {
                lpfnWndProc: Some(window),
                hInstance: instance.into(),
                lpszClassName: class,
                ..Default::default()
            };
            if RegisterClassW(&window_class) == 0 {
                return Err(windows::core::Error::from_win32());
            }
            // Never shown: it is only where the icon's clicks arrive.
            let hwnd = CreateWindowExW(
                WINDOW_EX_STYLE::default(),
                class,
                w!("Recly"),
                WS_OVERLAPPED,
                0,
                0,
                0,
                0,
                None,
                None,
                Some(instance.into()),
                None,
            )?;
            let mut data = NOTIFYICONDATAW {
                cbSize: std::mem::size_of::<NOTIFYICONDATAW>() as u32,
                hWnd: hwnd,
                uID: 1,
                uFlags: NIF_MESSAGE | NIF_ICON | NIF_TIP | NIF_SHOWTIP,
                uCallbackMessage: CALLBACK,
                hIcon: LoadIconW(None, IDI_WARNING)?,
                szTip: super::wide(&notice.title),
                ..Default::default()
            };
            if !Shell_NotifyIconW(NIM_ADD, &data).as_bool() {
                let _ = DestroyWindow(hwnd);
                return Err(windows::core::Error::from_win32());
            }
            data.Anonymous = NOTIFYICONDATAW_0 { uVersion: NOTIFYICON_VERSION_4 };
            let _ = Shell_NotifyIconW(NIM_SETVERSION, &data);
            // The balloon, as AWT raises its own: the icon first, then the message on it. The sound is the
            // system's — nothing is being recorded any more for it to be heard in.
            data.uFlags = NIF_INFO;
            data.szInfoTitle = super::wide(&notice.title);
            data.szInfo = super::wide(&notice.body);
            data.dwInfoFlags = NIIF_WARNING;
            let _ = Shell_NotifyIconW(NIM_MODIFY, &data);

            let millis = u32::try_from(linger.as_millis()).unwrap_or(u32::MAX);
            SetTimer(Some(hwnd), 1, millis, None);
            let mut message = MSG::default();
            // 0 is WM_QUIT and −1 an error; either ends the wait.
            while GetMessageW(&mut message, None, 0, 0).0 > 0 {
                let _ = TranslateMessage(&message);
                DispatchMessageW(&message);
            }
            let _ = Shell_NotifyIconW(NIM_DELETE, &data);
            let _ = DestroyWindow(hwnd);
        }
        Ok(())
    }

    unsafe extern "system" fn window(hwnd: HWND, message: u32, wparam: WPARAM, lparam: LPARAM) -> LRESULT {
        let done = match message {
            WM_TIMER => true,
            CALLBACK => super::dismissed((lparam.0 as u32) & 0xffff),
            _ => false,
        };
        if done {
            PostQuitMessage(0);
            return LRESULT(0);
        }
        DefWindowProcW(hwnd, message, wparam, lparam)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The decision the whole module rests on: only a recording, and only when the app is not reading.
    #[test]
    fn only_a_recording_whose_app_stopped_reading_is_warned_about() {
        assert!(app_gone(true, true));
        assert!(!app_gone(true, false), "stdin closed by an app that still reads: it let this helper go");
        assert!(!app_gone(false, true), "a detection-only helper records nothing to warn about");
        assert!(!app_gone(false, false));
    }

    #[test]
    fn a_click_dismisses_the_notice_and_a_timeout_keeps_it() {
        assert!(dismissed(NIN_BALLOONUSERCLICK));
        assert!(dismissed(NIN_SELECT));
        assert!(dismissed(NIN_KEYSELECT));
        assert!(dismissed(WM_LBUTTONUP));
        assert!(dismissed(WM_CONTEXTMENU));
        // NIN_BALLOONTIMEOUT and NIN_BALLOONHIDE: gone to the notification centre, still standing.
        assert!(!dismissed(0x404));
        assert!(!dismissed(0x403));
        // A mouse moving over the icon.
        assert!(!dismissed(0x200));
    }

    #[test]
    fn a_wide_field_is_cut_to_fit_and_terminated() {
        let short: [u16; 8] = wide("Recly");
        assert_eq!(&short[..6], &[82, 101, 99, 108, 121, 0]);
        let cut: [u16; 4] = wide("Recording");
        assert_eq!(cut, [82, 101, 99, 0]);
        let korean: [u16; 8] = wide("녹음");
        assert_eq!(&korean[..3], &[0xb179, 0xc74c, 0]);
    }
}
