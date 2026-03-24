"""
Static AWS / GCP / Azure on-demand pricing tables.
Source: public pricing pages as of PRICING_DATE.
Instance lists are sorted ascending by hourly_usd so callers can
pick the cheapest fitting instance with a linear scan.
"""

from typing import Dict, List

PRICING_DATE = "2025-01-01"
HOURS_PER_MONTH = 730.0
GIB_IN_BYTES = 1_073_741_824.0


class InstanceType:
    __slots__ = ("name", "vcpu", "hourly_usd")

    def __init__(self, name: str, vcpu: float, hourly_usd: float) -> None:
        self.name = name
        self.vcpu = vcpu
        self.hourly_usd = hourly_usd


# ── AWS EC2 (us-east-1, Linux on-demand) ─────────────────────────────────────

_AWS: List[InstanceType] = sorted(
    [
        InstanceType("t3.nano", 2, 0.0052),
        InstanceType("t3.micro", 2, 0.0104),
        InstanceType("t3.small", 2, 0.0208),
        InstanceType("t3.medium", 2, 0.0416),
        InstanceType("t3.large", 2, 0.0832),
        InstanceType("c5.large", 2, 0.0850),
        InstanceType("m5.large", 2, 0.0960),
        InstanceType("r5.large", 2, 0.1260),
        InstanceType("t3.xlarge", 4, 0.1664),
        InstanceType("c5.xlarge", 4, 0.1700),
        InstanceType("m5.xlarge", 4, 0.1920),
        InstanceType("r5.xlarge", 4, 0.2520),
        InstanceType("t3.2xlarge", 8, 0.3328),
        InstanceType("c5.2xlarge", 8, 0.3400),
        InstanceType("m5.2xlarge", 8, 0.3840),
        InstanceType("r5.2xlarge", 8, 0.5040),
        InstanceType("c5.4xlarge", 16, 0.6800),
        InstanceType("m5.4xlarge", 16, 0.7680),
        InstanceType("c5.9xlarge", 36, 1.5300),
        InstanceType("m5.8xlarge", 32, 1.5360),
    ],
    key=lambda i: i.hourly_usd,
)

# ── GCP Compute Engine (us-central1, Linux on-demand) ────────────────────────

_GCP: List[InstanceType] = sorted(
    [
        InstanceType("e2-micro", 2, 0.0084),
        InstanceType("e2-small", 2, 0.0168),
        InstanceType("e2-medium", 2, 0.0335),
        InstanceType("e2-standard-2", 2, 0.0671),
        InstanceType("n2-standard-2", 2, 0.0971),
        InstanceType("e2-standard-4", 4, 0.1341),
        InstanceType("n2-standard-4", 4, 0.1942),
        InstanceType("c2-standard-4", 4, 0.2088),
        InstanceType("e2-standard-8", 8, 0.2683),
        InstanceType("n2-standard-8", 8, 0.3883),
        InstanceType("c2-standard-8", 8, 0.4176),
        InstanceType("e2-standard-16", 16, 0.5366),
        InstanceType("n2-standard-16", 16, 0.7766),
        InstanceType("c2-standard-16", 16, 0.8352),
        InstanceType("e2-standard-32", 32, 1.0732),
        InstanceType("c2-standard-30", 30, 1.5660),
    ],
    key=lambda i: i.hourly_usd,
)

# ── Azure VM (East US, Linux on-demand) ──────────────────────────────────────

_AZURE: List[InstanceType] = sorted(
    [
        InstanceType("B1ms", 1, 0.0207),
        InstanceType("B2s", 2, 0.0416),
        InstanceType("B2ms", 2, 0.0832),
        InstanceType("F2s_v2", 2, 0.0850),
        InstanceType("D2s_v5", 2, 0.0960),
        InstanceType("B4ms", 4, 0.1664),
        InstanceType("F4s_v2", 4, 0.1700),
        InstanceType("D4s_v5", 4, 0.1920),
        InstanceType("B8ms", 8, 0.3328),
        InstanceType("F8s_v2", 8, 0.3400),
        InstanceType("D8s_v5", 8, 0.3840),
        InstanceType("B16ms", 16, 0.6656),
        InstanceType("F16s_v2", 16, 0.6800),
        InstanceType("D16s_v5", 16, 0.7680),
        InstanceType("F32s_v2", 32, 1.3600),
        InstanceType("D32s_v5", 32, 1.5360),
    ],
    key=lambda i: i.hourly_usd,
)

# ── Egress rates ($/GiB outbound) ─────────────────────────────────────────────

_EGRESS: Dict[str, float] = {"AWS": 0.09, "GCP": 0.085, "AZURE": 0.087}


def get_instances(provider: str) -> List[InstanceType]:
    p = (provider or "AWS").upper()
    if p == "GCP":
        return _GCP
    if p == "AZURE":
        return _AZURE
    return _AWS


def get_egress_rate_per_gib(provider: str) -> float:
    return _EGRESS.get((provider or "AWS").upper(), 0.09)
