//! Pure validation shared by the Android controller and standalone host tests.

pub const SUPPORTED_KMIS: [&str; 8] = [
    "android12-5.10",
    "android13-5.10",
    "android13-5.15",
    "android14-5.15",
    "android14-6.1",
    "android15-6.6",
    "android16-6.12",
    "android17-6.18",
];
pub const MODULE_MARKER: &str = "SUSFS_LKM_MODULES";
pub const MODULE_VARIANT: &str = "GKI-LKM";
pub const MODULE_VERSION: &str = "v2.3.0";
pub const KERNEL_ADDRESS_MIN: u64 = 0xff00_0000_0000_0000;

#[repr(C)]
pub struct Query<const N: usize> {
    pub text: [u8; N],
    pub err: i32,
}

#[derive(Debug, PartialEq, Eq)]
pub enum Provider {
    Absent,
    Builtin,
    Lkm,
    Inconsistent,
}

pub fn valid_kmi(kmi: &str) -> bool {
    SUPPORTED_KMIS.contains(&kmi)
}

pub fn kernel_series(release: &str) -> Option<(u32, u32)> {
    let mut parts = release.split('.');
    let major = parts.next()?.parse().ok()?;
    let minor = parts.next()?.split('-').next()?.parse().ok()?;
    Some((major, minor))
}

pub fn kmi_matches_release(kmi: &str, release: &str) -> bool {
    valid_kmi(kmi)
        && kmi
            .split_once('-')
            .and_then(|(_, series)| kernel_series(series))
            .zip(kernel_series(release))
            .is_some_and(|(expected, actual)| expected == actual)
}

pub fn decode_string(bytes: &[u8]) -> Option<String> {
    let end = bytes.iter().position(|byte| *byte == 0)?;
    let text = std::str::from_utf8(&bytes[..end]).ok()?;
    (!text.is_empty()).then(|| text.to_owned())
}

pub fn classify(
    module_present: bool,
    builtin_symbols: bool,
    version: Option<&str>,
    variant: Option<&str>,
    features: &[String],
) -> Provider {
    let has_marker = features.iter().any(|feature| feature == MODULE_MARKER);
    let claims_lkm = has_marker || variant == Some(MODULE_VARIANT);
    if module_present {
        return Provider::Lkm;
    }
    if claims_lkm {
        return Provider::Inconsistent;
    }
    if builtin_symbols && version.is_some() && !features.is_empty() {
        return Provider::Builtin;
    }
    if version.is_some() || variant.is_some() || !features.is_empty() {
        Provider::Inconsistent
    } else {
        Provider::Absent
    }
}

pub fn arm64_module_elf(bytes: &[u8]) -> bool {
    bytes.len() >= 64
        && &bytes[..4] == b"\x7fELF"
        && bytes[4] == 2
        && bytes[5] == 1
        && u16::from_le_bytes([bytes[16], bytes[17]]) == 1
        && u16::from_le_bytes([bytes[18], bytes[19]]) == 183
}

pub fn missing_imports<'a>(names: &[&'a str], addresses: &[Option<u64>]) -> Vec<&'a str> {
    names
        .iter()
        .enumerate()
        .filter_map(|(index, name)| {
            addresses
                .get(index)
                .copied()
                .flatten()
                .is_none_or(|address| address < KERNEL_ADDRESS_MIN)
                .then_some(*name)
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_shipped_kmis_are_accepted() {
        assert_eq!(SUPPORTED_KMIS.len(), 8);
        for kmi in SUPPORTED_KMIS {
            assert!(valid_kmi(kmi));
        }
        for kmi in ["android14-5.10", "android15-6.12", "../android14-6.1", ""] {
            assert!(!valid_kmi(kmi));
        }
    }

    #[test]
    fn manual_kmi_cannot_cross_kernel_series() {
        assert!(kmi_matches_release("android17-6.18", "6.18.1-android17-0"));
        assert!(kmi_matches_release("android14-5.15", "5.15.123-vendor"));
        assert!(!kmi_matches_release("android14-6.1", "6.12.1-android16"));
        assert!(!kmi_matches_release("android14-6.1", "malformed"));
    }

    #[test]
    fn provider_requires_runtime_evidence() {
        let features = vec!["CONFIG_KSU_SUSFS_SUS_PATH".into()];
        assert_eq!(
            classify(false, true, Some("v2.3.0"), Some("GKI"), &features),
            Provider::Builtin
        );
        assert_eq!(
            classify(false, false, Some("v2.3.0"), Some("GKI"), &features),
            Provider::Inconsistent
        );
        assert_eq!(classify(false, false, None, None, &[]), Provider::Absent);
        assert_eq!(
            classify(false, true, Some("v2.3.0"), None, &[]),
            Provider::Inconsistent
        );
    }

    #[test]
    fn lkm_protocol_without_a_module_is_inconsistent() {
        let features = vec![MODULE_MARKER.into()];
        assert_eq!(
            classify(
                false,
                false,
                Some(MODULE_VERSION),
                Some(MODULE_VARIANT),
                &features
            ),
            Provider::Inconsistent
        );
        assert_eq!(classify(true, false, None, None, &[]), Provider::Lkm);
    }

    #[test]
    fn probe_strings_must_be_terminated_and_valid_utf8() {
        assert_eq!(decode_string(b"v2.3.0\0garbage"), Some("v2.3.0".into()));
        assert_eq!(decode_string(b"unterminated"), None);
        assert_eq!(decode_string(b"\xff\0"), None);
        assert_eq!(decode_string(b"\0"), None);
    }

    #[test]
    fn unresolved_zero_and_userspace_addresses_are_rejected() {
        let names = ["good", "missing", "zero", "user", "short"];
        let addresses = [Some(KERNEL_ADDRESS_MIN), None, Some(0), Some(0x1000)];
        assert_eq!(
            missing_imports(&names, &addresses),
            ["missing", "zero", "user", "short"]
        );
    }

    #[test]
    fn query_payloads_match_the_kernel_abi() {
        assert_eq!(std::mem::size_of::<Query<16>>(), 20);
        assert_eq!(std::mem::offset_of!(Query<16>, err), 16);
        assert_eq!(std::mem::size_of::<Query<8192>>(), 8196);
        assert_eq!(std::mem::offset_of!(Query<8192>, err), 8192);
        println!("Rust Query<16>: size=20, err_offset=16");
        println!("Rust Query<8192>: size=8196, err_offset=8192");
    }

    #[test]
    fn module_elf_must_be_little_endian_arm64_relocatable() {
        let mut image = [0u8; 64];
        image[..6].copy_from_slice(b"\x7fELF\x02\x01");
        image[16] = 1;
        image[18] = 183;
        assert!(arm64_module_elf(&image));
        image[18] = 62;
        assert!(!arm64_module_elf(&image));
        image[18] = 183;
        image[16] = 3;
        assert!(!arm64_module_elf(&image));
        assert!(!arm64_module_elf(b"\x7fELF"));
    }
}
