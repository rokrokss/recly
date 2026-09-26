//! `--network-cost`: whether the connection the PC is on right now costs by the byte, asked by the
//! app before it downloads the on-device speech model (about 1 GB) so a metered connection is a
//! question rather than a surprise.
//!
//! The answer is one word on stdout — `metered`, `unmetered` or `unknown` — and anything this helper
//! cannot find out is `unknown`, which the app downloads over without asking.

/// What the app is told.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum NetworkCost {
    Metered,
    Unmetered,
    Unknown,
}

impl NetworkCost {
    /// The word printed for the app (`CaptureHelper.networkCost` parses it).
    pub fn as_str(self) -> &'static str {
        match self {
            NetworkCost::Metered => "metered",
            NetworkCost::Unmetered => "unmetered",
            NetworkCost::Unknown => "unknown",
        }
    }
}

/// WinRT's `NetworkCostType`, named here so the mapping can be tested on any host.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CostType {
    Unknown,
    Unrestricted,
    Fixed,
    Variable,
}

/// The internet connection profile's cost, as the app wants it: a data plan with a cap (`Fixed`),
/// one charged by use (`Variable`), roaming, or a plan already past its limit is metered; an
/// unrestricted connection is not; anything else is not known.
pub fn classify(cost_type: CostType, roaming: bool, over_data_limit: bool) -> NetworkCost {
    if roaming || over_data_limit {
        return NetworkCost::Metered;
    }
    match cost_type {
        CostType::Fixed | CostType::Variable => NetworkCost::Metered,
        CostType::Unrestricted => NetworkCost::Unmetered,
        CostType::Unknown => NetworkCost::Unknown,
    }
}

/// The connection the PC is on now. No internet profile, or any error reading it, is `unknown`.
#[cfg(windows)]
pub fn cost() -> NetworkCost {
    use windows::Networking::Connectivity::{NetworkCostType, NetworkInformation};

    let read = || -> windows::core::Result<NetworkCost> {
        let profile = NetworkInformation::GetInternetConnectionProfile()?;
        let cost = profile.GetConnectionCost()?;
        let cost_type = match cost.NetworkCostType()? {
            NetworkCostType::Unrestricted => CostType::Unrestricted,
            NetworkCostType::Fixed => CostType::Fixed,
            NetworkCostType::Variable => CostType::Variable,
            _ => CostType::Unknown,
        };
        Ok(classify(cost_type, cost.Roaming()?, cost.OverDataLimit()?))
    };
    read().unwrap_or(NetworkCost::Unknown)
}

/// Not a Windows build: there is no connection profile to read.
#[cfg(not(windows))]
pub fn cost() -> NetworkCost {
    NetworkCost::Unknown
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_capped_or_charged_plan_is_metered() {
        assert_eq!(classify(CostType::Fixed, false, false), NetworkCost::Metered);
        assert_eq!(classify(CostType::Variable, false, false), NetworkCost::Metered);
    }

    #[test]
    fn roaming_or_over_the_limit_is_metered_whatever_the_plan() {
        assert_eq!(classify(CostType::Unrestricted, true, false), NetworkCost::Metered);
        assert_eq!(classify(CostType::Unrestricted, false, true), NetworkCost::Metered);
        assert_eq!(classify(CostType::Unknown, true, false), NetworkCost::Metered);
    }

    #[test]
    fn an_unrestricted_connection_is_unmetered() {
        assert_eq!(classify(CostType::Unrestricted, false, false), NetworkCost::Unmetered);
    }

    #[test]
    fn an_unknown_cost_type_is_unknown() {
        assert_eq!(classify(CostType::Unknown, false, false), NetworkCost::Unknown);
    }

    #[test]
    fn the_app_reads_one_of_three_words() {
        assert_eq!(NetworkCost::Metered.as_str(), "metered");
        assert_eq!(NetworkCost::Unmetered.as_str(), "unmetered");
        assert_eq!(NetworkCost::Unknown.as_str(), "unknown");
    }

    #[cfg(not(windows))]
    #[test]
    fn a_build_that_is_not_windows_does_not_know() {
        assert_eq!(cost(), NetworkCost::Unknown);
    }
}
