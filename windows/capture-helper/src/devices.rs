//! `--devices`: what a start made now would capture — the default microphone and the default output, the two
//! endpoints the capture opens ([`crate::capture`]'s streams take the `eConsole` default of each) — named for the
//! tray before it starts (docs/14, 2026-11-04). A microphone that is not the one meant shows there before the
//! recording rather than in it.
//!
//! The answer is up to two lines on stdout, `microphone=<name>` and `output=<name>`; an endpoint there is none of,
//! or one this helper cannot name, is left out.

/// The two names, as the app is told them.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Devices {
    pub microphone: Option<String>,
    pub output: Option<String>,
}

impl Devices {
    /// The lines printed for the app (`CaptureHelper.devices` parses them). A name is one line: a line break in a
    /// driver's name would end it early, so it is a space here.
    pub fn lines(&self) -> String {
        let mut out = String::new();
        for (key, name) in [("microphone", &self.microphone), ("output", &self.output)] {
            let Some(name) = name else { continue };
            let name = name.replace(['\r', '\n'], " ");
            let name = name.trim();
            if !name.is_empty() {
                out.push_str(key);
                out.push('=');
                out.push_str(name);
                out.push('\n');
            }
        }
        out
    }
}

/// The default endpoints' friendly names (`PKEY_Device_FriendlyName`), each `None` when there is no such endpoint
/// or it cannot be read.
#[cfg(windows)]
pub fn current() -> Devices {
    use windows::core::GUID;
    use windows::Win32::Foundation::PROPERTYKEY;
    use windows::Win32::Media::Audio::{eCapture, eConsole, eRender, EDataFlow, IMMDeviceEnumerator, MMDeviceEnumerator};
    use windows::Win32::System::Com::StructuredStorage::{PropVariantClear, PropVariantToStringAlloc};
    use windows::Win32::System::Com::{CoCreateInstance, CoTaskMemFree, CLSCTX_ALL, STGM_READ};

    // `PKEY_Device_FriendlyName`, spelled out rather than taking the whole `FunctionDiscovery` feature for one key.
    const FRIENDLY_NAME: PROPERTYKEY = PROPERTYKEY {
        fmtid: GUID::from_u128(0xa45c254e_df1c_4efd_8020_67d146a850e0),
        pid: 14,
    };

    let _apartment = crate::capture::wasapi::Apartment::enter().ok();
    let name = |flow: EDataFlow| -> Option<String> {
        unsafe {
            let enumerator: IMMDeviceEnumerator = CoCreateInstance(&MMDeviceEnumerator, None, CLSCTX_ALL).ok()?;
            let device = enumerator.GetDefaultAudioEndpoint(flow, eConsole).ok()?;
            let store = device.OpenPropertyStore(STGM_READ).ok()?;
            let mut value = store.GetValue(&FRIENDLY_NAME).ok()?;
            let text = PropVariantToStringAlloc(&value).ok();
            let _ = PropVariantClear(&mut value);
            let text = text?;
            let name = text.to_string().ok();
            CoTaskMemFree(Some(text.0 as _));
            name
        }
    };
    Devices { microphone: name(eCapture), output: name(eRender) }
}

/// Not a Windows build: there are no endpoints to name.
#[cfg(not(windows))]
pub fn current() -> Devices {
    Devices::default()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn each_name_is_one_line_and_a_missing_one_is_left_out() {
        let devices = Devices { microphone: Some("Headset\r\nMic ".into()), output: None };
        assert_eq!(devices.lines(), "microphone=Headset  Mic\n");
        assert_eq!(Devices::default().lines(), "");
        let both = Devices { microphone: Some("Mic".into()), output: Some("Speakers".into()) };
        assert_eq!(both.lines(), "microphone=Mic\noutput=Speakers\n");
    }
}
