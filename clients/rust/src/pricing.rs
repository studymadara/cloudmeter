use crate::model::InstanceType;

pub const PRICING_DATE: &str = "2025-01-01";
pub const HOURS_PER_MONTH: f64 = 730.0;
pub const GIB_IN_BYTES: f64 = 1_073_741_824.0;

pub fn get_instances(provider: &str) -> Vec<InstanceType> {
    match provider.to_uppercase().as_str() {
        "GCP" => gcp_instances(),
        "AZURE" => azure_instances(),
        _ => aws_instances(),
    }
}

pub fn get_egress_rate_per_gib(provider: &str) -> f64 {
    match provider.to_uppercase().as_str() {
        "GCP" => 0.085,
        "AZURE" => 0.087,
        _ => 0.09, // AWS
    }
}

fn aws_instances() -> Vec<InstanceType> {
    let mut list = vec![
        InstanceType {
            name: "t3.nano".into(),
            vcpu: 2.0,
            hourly_usd: 0.0052,
        },
        InstanceType {
            name: "t3.micro".into(),
            vcpu: 2.0,
            hourly_usd: 0.0104,
        },
        InstanceType {
            name: "t3.small".into(),
            vcpu: 2.0,
            hourly_usd: 0.0208,
        },
        InstanceType {
            name: "t3.medium".into(),
            vcpu: 2.0,
            hourly_usd: 0.0416,
        },
        InstanceType {
            name: "t3.large".into(),
            vcpu: 2.0,
            hourly_usd: 0.0832,
        },
        InstanceType {
            name: "c5.large".into(),
            vcpu: 2.0,
            hourly_usd: 0.0850,
        },
        InstanceType {
            name: "m5.large".into(),
            vcpu: 2.0,
            hourly_usd: 0.0960,
        },
        InstanceType {
            name: "r5.large".into(),
            vcpu: 2.0,
            hourly_usd: 0.1260,
        },
        InstanceType {
            name: "t3.xlarge".into(),
            vcpu: 4.0,
            hourly_usd: 0.1664,
        },
        InstanceType {
            name: "c5.xlarge".into(),
            vcpu: 4.0,
            hourly_usd: 0.1700,
        },
        InstanceType {
            name: "m5.xlarge".into(),
            vcpu: 4.0,
            hourly_usd: 0.1920,
        },
        InstanceType {
            name: "r5.xlarge".into(),
            vcpu: 4.0,
            hourly_usd: 0.2520,
        },
        InstanceType {
            name: "t3.2xlarge".into(),
            vcpu: 8.0,
            hourly_usd: 0.3328,
        },
        InstanceType {
            name: "c5.2xlarge".into(),
            vcpu: 8.0,
            hourly_usd: 0.3400,
        },
        InstanceType {
            name: "m5.2xlarge".into(),
            vcpu: 8.0,
            hourly_usd: 0.3840,
        },
        InstanceType {
            name: "r5.2xlarge".into(),
            vcpu: 8.0,
            hourly_usd: 0.5040,
        },
        InstanceType {
            name: "c5.4xlarge".into(),
            vcpu: 16.0,
            hourly_usd: 0.6800,
        },
        InstanceType {
            name: "m5.4xlarge".into(),
            vcpu: 16.0,
            hourly_usd: 0.7680,
        },
        InstanceType {
            name: "c5.9xlarge".into(),
            vcpu: 36.0,
            hourly_usd: 1.5300,
        },
        InstanceType {
            name: "m5.8xlarge".into(),
            vcpu: 32.0,
            hourly_usd: 1.5360,
        },
    ];
    list.sort_by(|a, b| a.hourly_usd.partial_cmp(&b.hourly_usd).unwrap());
    list
}

fn gcp_instances() -> Vec<InstanceType> {
    let mut list = vec![
        InstanceType {
            name: "e2-micro".into(),
            vcpu: 2.0,
            hourly_usd: 0.0084,
        },
        InstanceType {
            name: "e2-small".into(),
            vcpu: 2.0,
            hourly_usd: 0.0168,
        },
        InstanceType {
            name: "e2-medium".into(),
            vcpu: 2.0,
            hourly_usd: 0.0335,
        },
        InstanceType {
            name: "e2-standard-2".into(),
            vcpu: 2.0,
            hourly_usd: 0.0671,
        },
        InstanceType {
            name: "n2-standard-2".into(),
            vcpu: 2.0,
            hourly_usd: 0.0971,
        },
        InstanceType {
            name: "e2-standard-4".into(),
            vcpu: 4.0,
            hourly_usd: 0.1341,
        },
        InstanceType {
            name: "c2-standard-4".into(),
            vcpu: 4.0,
            hourly_usd: 0.2088,
        },
        InstanceType {
            name: "n2-standard-4".into(),
            vcpu: 4.0,
            hourly_usd: 0.1942,
        },
        InstanceType {
            name: "e2-standard-8".into(),
            vcpu: 8.0,
            hourly_usd: 0.2683,
        },
        InstanceType {
            name: "n2-standard-8".into(),
            vcpu: 8.0,
            hourly_usd: 0.3883,
        },
        InstanceType {
            name: "c2-standard-8".into(),
            vcpu: 8.0,
            hourly_usd: 0.4176,
        },
        InstanceType {
            name: "e2-standard-16".into(),
            vcpu: 16.0,
            hourly_usd: 0.5366,
        },
        InstanceType {
            name: "n2-standard-16".into(),
            vcpu: 16.0,
            hourly_usd: 0.7766,
        },
        InstanceType {
            name: "c2-standard-16".into(),
            vcpu: 16.0,
            hourly_usd: 0.8352,
        },
        InstanceType {
            name: "e2-standard-32".into(),
            vcpu: 32.0,
            hourly_usd: 1.0732,
        },
        InstanceType {
            name: "c2-standard-30".into(),
            vcpu: 30.0,
            hourly_usd: 1.5660,
        },
    ];
    list.sort_by(|a, b| a.hourly_usd.partial_cmp(&b.hourly_usd).unwrap());
    list
}

fn azure_instances() -> Vec<InstanceType> {
    let mut list = vec![
        InstanceType {
            name: "B1ms".into(),
            vcpu: 1.0,
            hourly_usd: 0.0207,
        },
        InstanceType {
            name: "B2s".into(),
            vcpu: 2.0,
            hourly_usd: 0.0416,
        },
        InstanceType {
            name: "B2ms".into(),
            vcpu: 2.0,
            hourly_usd: 0.0832,
        },
        InstanceType {
            name: "F2s_v2".into(),
            vcpu: 2.0,
            hourly_usd: 0.0850,
        },
        InstanceType {
            name: "D2s_v5".into(),
            vcpu: 2.0,
            hourly_usd: 0.0960,
        },
        InstanceType {
            name: "B4ms".into(),
            vcpu: 4.0,
            hourly_usd: 0.1664,
        },
        InstanceType {
            name: "F4s_v2".into(),
            vcpu: 4.0,
            hourly_usd: 0.1700,
        },
        InstanceType {
            name: "D4s_v5".into(),
            vcpu: 4.0,
            hourly_usd: 0.1920,
        },
        InstanceType {
            name: "B8ms".into(),
            vcpu: 8.0,
            hourly_usd: 0.3328,
        },
        InstanceType {
            name: "F8s_v2".into(),
            vcpu: 8.0,
            hourly_usd: 0.3400,
        },
        InstanceType {
            name: "D8s_v5".into(),
            vcpu: 8.0,
            hourly_usd: 0.3840,
        },
        InstanceType {
            name: "B16ms".into(),
            vcpu: 16.0,
            hourly_usd: 0.6656,
        },
        InstanceType {
            name: "F16s_v2".into(),
            vcpu: 16.0,
            hourly_usd: 0.6800,
        },
        InstanceType {
            name: "D16s_v5".into(),
            vcpu: 16.0,
            hourly_usd: 0.7680,
        },
        InstanceType {
            name: "F32s_v2".into(),
            vcpu: 32.0,
            hourly_usd: 1.3600,
        },
        InstanceType {
            name: "D32s_v5".into(),
            vcpu: 32.0,
            hourly_usd: 1.5360,
        },
    ];
    list.sort_by(|a, b| a.hourly_usd.partial_cmp(&b.hourly_usd).unwrap());
    list
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn all_providers_return_non_empty_sorted_lists() {
        for provider in &["AWS", "GCP", "AZURE"] {
            let instances = get_instances(provider);
            assert!(!instances.is_empty(), "{provider} list is empty");
            for i in 1..instances.len() {
                assert!(
                    instances[i].hourly_usd >= instances[i - 1].hourly_usd,
                    "{provider} instances not sorted by hourly_usd"
                );
            }
        }
    }

    #[test]
    fn unknown_provider_defaults_to_aws() {
        let instances = get_instances("UNKNOWN");
        assert!(!instances.is_empty());
        assert!(instances.iter().any(|i| i.name == "t3.nano"));
    }
}
