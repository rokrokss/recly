//! docs/14 "감지": which app is holding the microphone, polled while `detect on` is in force.
//!
//! The signal is the capture endpoints' *active* audio sessions (`IAudioSessionManager2`), which is
//! what the macOS side reads too (`kAudioDevicePropertyDeviceIsRunningSomewhere` plus the running
//! meeting apps, docs/12) — with the advantage that Windows names the process that owns the session,
//! so the app gets `Zoom.exe` rather than "something".
//!
//! Two seconds between polls, and only while detection is on: this runs all day on a laptop, and
//! enumerating every session of every endpoint is not free.

use crate::protocol::Event;

/// docs/14 deliverable 5.
pub const POLL_SEC: u64 = 2;

/// Reports the transitions, not the state: the app wants to be told once when a meeting app takes
/// the microphone, not twice a second for the length of the meeting.
pub struct MicWatcher {
    holder: Option<String>,
    fake: Option<String>,
}

impl MicWatcher {
    /// [fake] is `--mic-in-use`, a stand-in for a capture session: there is no
    /// `IAudioSessionManager2` on macOS, and `HelperClientTest` still has to see the event. It
    /// overrides the real query on Windows too, so the same test runs there.
    pub fn new(fake: Option<String>) -> Self {
        Self { holder: None, fake }
    }

    /// The events since the last poll — at most one, because at most one thing changed.
    pub fn poll(&mut self) -> Option<Event> {
        let now = self.current();
        match (&self.holder, now) {
            (None, Some(app)) => {
                self.holder = Some(app.clone());
                Some(Event::MicInUse { app, in_use: true })
            }
            (Some(previous), None) => {
                let app = previous.clone();
                self.holder = None;
                Some(Event::MicInUse { app, in_use: false })
            }
            // A second app joining an already-busy microphone is not a new meeting.
            _ => None,
        }
    }

    /// The first active capture session that is not this process, by executable name.
    fn current(&self) -> Option<String> {
        if let Some(fake) = self.fake.as_ref() {
            return Some(fake.clone());
        }
        #[cfg(windows)]
        {
            windows_impl::microphone_holder().ok().flatten()
        }
        #[cfg(not(windows))]
        {
            None
        }
    }
}

#[cfg(windows)]
mod windows_impl {
    use windows::core::Interface;
    use windows::Win32::Foundation::{CloseHandle, MAX_PATH};
    use windows::Win32::Media::Audio::{
        eCapture, AudioSessionStateActive, IAudioSessionControl2, IAudioSessionManager2,
        IMMDeviceEnumerator, MMDeviceEnumerator, DEVICE_STATE_ACTIVE,
    };
    use windows::Win32::System::Com::{CoCreateInstance, CLSCTX_ALL};
    use windows::Win32::System::Threading::{
        OpenProcess, QueryFullProcessImageNameW, PROCESS_NAME_WIN32,
        PROCESS_QUERY_LIMITED_INFORMATION,
    };

    pub fn microphone_holder() -> windows::core::Result<Option<String>> {
        let own = std::process::id();
        unsafe {
            let enumerator: IMMDeviceEnumerator =
                CoCreateInstance(&MMDeviceEnumerator, None, CLSCTX_ALL)?;
            let devices = enumerator.EnumAudioEndpoints(eCapture, DEVICE_STATE_ACTIVE)?;
            for index in 0..devices.GetCount()? {
                let device = devices.Item(index)?;
                let manager: IAudioSessionManager2 = device.Activate(CLSCTX_ALL, None)?;
                let sessions = manager.GetSessionEnumerator()?;
                for session in 0..sessions.GetCount()? {
                    let control = sessions.GetSession(session)?;
                    if control.GetState()? != AudioSessionStateActive {
                        continue;
                    }
                    let control: IAudioSessionControl2 = control.cast()?;
                    // No `IsSystemSoundsSession` check: it answers S_FALSE for "no", which
                    // `windows`-rs reports as `Ok` like any other success, so the test would skip
                    // every session. It is also a render-endpoint concern — these are the capture
                    // endpoints — and the process id below is the real filter.
                    let pid = control.GetProcessId()?;
                    if pid == own || pid == 0 {
                        continue;
                    }
                    if let Some(name) = process_name(pid) {
                        return Ok(Some(name));
                    }
                }
            }
        }
        Ok(None)
    }

    /// `Zoom.exe`, `ms-teams.exe`, `chrome.exe` — the executable's own name, which is what docs/14
    /// "감지" lists and what the app matches meeting apps against.
    fn process_name(pid: u32) -> Option<String> {
        unsafe {
            let handle = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, pid).ok()?;
            let mut buffer = [0u16; MAX_PATH as usize];
            let mut length = buffer.len() as u32;
            let query = QueryFullProcessImageNameW(
                handle,
                PROCESS_NAME_WIN32,
                windows::core::PWSTR(buffer.as_mut_ptr()),
                &mut length,
            );
            let _ = CloseHandle(handle);
            query.ok()?;
            let path = String::from_utf16_lossy(&buffer[..length as usize]);
            path.rsplit('\\').next().map(str::to_string)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Deliverable 5's contract, on the one path a macOS host has: one event when the microphone is
    /// taken, one when it is given back, and nothing in between.
    #[test]
    fn only_the_transitions_are_reported() {
        let mut watcher = MicWatcher::new(Some("Zoom.exe".into()));
        assert_eq!(
            Some(Event::MicInUse { app: "Zoom.exe".into(), in_use: true }),
            watcher.poll(),
        );
        assert_eq!(None, watcher.poll());
        watcher.fake = None;
        assert_eq!(
            Some(Event::MicInUse { app: "Zoom.exe".into(), in_use: false }),
            watcher.poll(),
        );
        assert_eq!(None, watcher.poll());
    }
}
