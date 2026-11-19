//! Enumerate local adapters with the Windows API; never probe or scan the LAN.
use std::net::Ipv4Addr;

pub fn usable(ip: Ipv4Addr) -> bool {
    let [a, b, _, _] = ip.octets();
    a != 0 && a != 127 && a < 224 && !(a == 169 && b == 254)
}

#[derive(Debug)]
struct Candidate {
    ip: Ipv4Addr,
    hardware: bool,
    gateway: bool,
    metric: u32,
}

fn ranked(mut candidates: Vec<Candidate>) -> Vec<Ipv4Addr> {
    candidates.retain(|c| usable(c.ip));
    candidates.sort_by_key(|c| (!c.hardware, !c.gateway, c.metric, c.ip));
    let mut ips = Vec::new();
    for candidate in candidates {
        if !ips.contains(&candidate.ip) {
            ips.push(candidate.ip);
        }
    }
    ips
}

pub fn candidates() -> Vec<Ipv4Addr> {
    #[cfg(windows)]
    {
        use windows_sys::Win32::{
            NetworkManagement::{IpHelper::*, Ndis::IfOperStatusUp},
            Networking::WinSock::{AF_INET, IpDadStatePreferred, SOCKADDR_IN},
        };
        // u64 storage provides the alignment required by the native linked list.
        // Retry boundedly if an adapter changes between sizing and enumeration.
        let mut bytes = 15_000u32;
        for _ in 0..3 {
            let mut storage = vec![0u64; (bytes as usize).div_ceil(8)];
            let first = storage.as_mut_ptr().cast::<IP_ADAPTER_ADDRESSES_LH>();
            // SAFETY: buffer is aligned, writable and at least `bytes` long;
            // pointers returned by Windows remain valid while storage is alive.
            let result = unsafe {
                GetAdaptersAddresses(
                    AF_INET as u32,
                    GAA_FLAG_INCLUDE_GATEWAYS,
                    std::ptr::null(),
                    first,
                    &mut bytes,
                )
            };
            if result == 111 {
                continue;
            } // ERROR_BUFFER_OVERFLOW
            if result != 0 {
                return Vec::new();
            }
            let mut entries = Vec::new();
            let mut cursor = first;
            unsafe {
                while let Some(adapter) = cursor.as_ref() {
                    if adapter.OperStatus == IfOperStatusUp
                        && adapter.IfType != 24
                        && adapter.IfType != 131
                    {
                        let mut row = MIB_IF_ROW2::default();
                        row.InterfaceLuid = adapter.Luid;
                        let hardware = GetIfEntry2(&mut row) == 0
                            && row.InterfaceAndOperStatusFlags._bitfield & 1 != 0;
                        let mut address = adapter.FirstUnicastAddress;
                        while let Some(unicast) = address.as_ref() {
                            if unicast.DadState == IpDadStatePreferred
                                && !unicast.Address.lpSockaddr.is_null()
                                && unicast.Address.iSockaddrLength as usize
                                    >= std::mem::size_of::<SOCKADDR_IN>()
                            {
                                let socket = &*unicast.Address.lpSockaddr.cast::<SOCKADDR_IN>();
                                if socket.sin_family == AF_INET {
                                    entries.push(Candidate {
                                        ip: Ipv4Addr::from(
                                            socket.sin_addr.S_un.S_addr.to_ne_bytes(),
                                        ),
                                        hardware,
                                        gateway: !adapter.FirstGatewayAddress.is_null(),
                                        metric: adapter.Ipv4Metric,
                                    });
                                }
                            }
                            address = unicast.Next;
                        }
                    }
                    cursor = adapter.Next;
                }
            }
            return ranked(entries);
        }
    }
    Vec::new()
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn filters_unusable_and_prefers_physical_default_route_then_metric() {
        let c = |ip: &str, hardware, gateway, metric| Candidate {
            ip: ip.parse().unwrap(),
            hardware,
            gateway,
            metric,
        };
        let ips = ranked(vec![
            c("127.0.0.1", true, true, 0),
            c("169.254.1.2", true, true, 0),
            c("224.0.0.1", true, true, 0),
            c("0.0.0.0", true, true, 0),
            c("10.9.0.1", false, true, 1),
            c("192.168.2.3", true, false, 1),
            c("192.168.1.8", true, true, 20),
            c("192.168.1.9", true, true, 30),
        ]);
        assert_eq!(
            ips.iter().map(ToString::to_string).collect::<Vec<_>>(),
            vec!["192.168.1.8", "192.168.1.9", "192.168.2.3", "10.9.0.1"]
        );
    }
    #[cfg(windows)]
    #[test]
    fn native_enumeration_returns_only_usable_unique_addresses() {
        let ips = candidates();
        assert!(ips.iter().all(|ip| usable(*ip)));
        for (index, ip) in ips.iter().enumerate() {
            assert!(!ips[..index].contains(ip));
        }
    }
}
