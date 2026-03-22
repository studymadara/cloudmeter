use crate::config::Config;
use crate::model::{
    EndpointCostProjection, InstanceType, RequestMetrics, ScalePoint, WarmupSummary,
};
use crate::pricing::{get_egress_rate_per_gib, get_instances, GIB_IN_BYTES, HOURS_PER_MONTH};
use std::collections::HashMap;

const SCALE_USERS: &[u32] = &[
    100, 200, 500, 1_000, 2_000, 5_000, 10_000, 20_000, 50_000, 100_000, 500_000, 1_000_000,
];

const WARMUP_SECONDS: u32 = 30;

pub fn project(metrics: &[RequestMetrics], config: &Config) -> Vec<EndpointCostProjection> {
    let live: Vec<&RequestMetrics> = metrics.iter().filter(|m| !m.warmup).collect();
    if live.is_empty() {
        return vec![];
    }

    let recording_duration = 300.0_f64; // 5-minute rolling window
    let total_observed_rps = live.len() as f64 / recording_duration;
    let safe_observed_rps = if total_observed_rps > 0.0 {
        total_observed_rps
    } else {
        1.0
    };

    let target_total_rps = config.target_users as f64 * config.requests_per_user_per_second;
    let instances = get_instances(&config.provider);
    let egress_per_gib = get_egress_rate_per_gib(&config.provider);

    // Group by route
    let mut by_route: HashMap<&str, Vec<&RequestMetrics>> = HashMap::new();
    for m in &live {
        by_route
            .entry(m.route_template.as_str())
            .or_default()
            .push(m);
    }

    let mut results: Vec<EndpointCostProjection> = by_route
        .into_iter()
        .map(|(route, entries)| {
            project_route(
                route,
                &entries,
                safe_observed_rps,
                target_total_rps,
                config,
                &instances,
                egress_per_gib,
            )
        })
        .collect();

    results.sort_by(|a, b| {
        b.projected_monthly_cost_usd
            .partial_cmp(&a.projected_monthly_cost_usd)
            .unwrap()
    });
    results
}

pub fn compute_warmup_summary(metrics: &[RequestMetrics], config: &Config) -> WarmupSummary {
    let warmup: Vec<&RequestMetrics> = metrics.iter().filter(|m| m.warmup).collect();
    if warmup.is_empty() {
        return WarmupSummary {
            has_data: false,
            request_count: 0,
            warmup_window_seconds: WARMUP_SECONDS,
            total_cpu_core_seconds: 0.0,
            total_egress_bytes: 0,
            estimated_cold_start_cost_usd: 0.0,
        };
    }

    let total_cpu: f64 = warmup.iter().map(|m| m.cpu_core_seconds).sum();
    let total_egress: u64 = warmup.iter().map(|m| m.egress_bytes).sum();

    let instances = get_instances(&config.provider);
    let egress_per_gib = get_egress_rate_per_gib(&config.provider);

    let avg_cores = total_cpu / WARMUP_SECONDS as f64;
    let inst = select_instance(avg_cores, &instances);
    let warmup_hours = WARMUP_SECONDS as f64 / 3600.0;
    let compute_cost = inst.hourly_usd * warmup_hours;
    let egress_cost = (total_egress as f64 / GIB_IN_BYTES) * egress_per_gib;

    WarmupSummary {
        has_data: true,
        request_count: warmup.len(),
        warmup_window_seconds: WARMUP_SECONDS,
        total_cpu_core_seconds: total_cpu,
        total_egress_bytes: total_egress,
        estimated_cold_start_cost_usd: compute_cost + egress_cost,
    }
}

fn project_route(
    route: &str,
    entries: &[&RequestMetrics],
    total_observed_rps: f64,
    target_total_rps: f64,
    config: &Config,
    instances: &[InstanceType],
    egress_rate_per_gib: f64,
) -> EndpointCostProjection {
    let recording_duration = 300.0_f64;
    let observed_rps = entries.len() as f64 / recording_duration;
    let scale_factor = target_total_rps / total_observed_rps;
    let projected_rps = observed_rps * scale_factor;

    let median_cpu = median(
        &mut entries
            .iter()
            .map(|m| m.cpu_core_seconds)
            .collect::<Vec<_>>(),
    );
    let median_egress = median(
        &mut entries
            .iter()
            .map(|m| m.egress_bytes as f64)
            .collect::<Vec<_>>(),
    );
    let median_duration = median(
        &mut entries
            .iter()
            .map(|m| m.duration_ms as f64)
            .collect::<Vec<_>>(),
    );

    let projected_monthly_cost = compute_monthly_cost(
        projected_rps,
        median_cpu,
        median_egress,
        instances,
        egress_rate_per_gib,
    );
    let cost_per_user = projected_monthly_cost / config.target_users as f64;
    let recommended = select_instance(projected_rps * median_cpu, instances);

    let cost_curve: Vec<ScalePoint> = SCALE_USERS
        .iter()
        .map(|&scale_users| {
            let scale_target_rps = scale_users as f64 * config.requests_per_user_per_second;
            let scaled_rps = observed_rps * (scale_target_rps / total_observed_rps);
            let cost = compute_monthly_cost(
                scaled_rps,
                median_cpu,
                median_egress,
                instances,
                egress_rate_per_gib,
            );
            ScalePoint {
                users: scale_users,
                monthly_cost_usd: (cost * 100.0).round() / 100.0,
            }
        })
        .collect();

    let exceeds_budget = config.budget_usd > 0.0 && projected_monthly_cost > config.budget_usd;

    EndpointCostProjection {
        route: route.to_string(),
        observed_rps: (observed_rps * 10000.0).round() / 10000.0,
        projected_rps: (projected_rps * 10.0).round() / 10.0,
        projected_monthly_cost_usd: (projected_monthly_cost * 100.0).round() / 100.0,
        projected_cost_per_user_usd: (cost_per_user * 1_000_000.0).round() / 1_000_000.0,
        recommended_instance: recommended.name.clone(),
        median_duration_ms: (median_duration * 100.0).round() / 100.0,
        median_cpu_ms: (median_cpu * 1000.0 * 100.0).round() / 100.0,
        exceeds_budget,
        cost_curve,
    }
}

fn compute_monthly_cost(
    projected_rps: f64,
    median_cpu_core_seconds: f64,
    median_egress_bytes: f64,
    instances: &[InstanceType],
    egress_rate_per_gib: f64,
) -> f64 {
    let required_cores = projected_rps * median_cpu_core_seconds;
    let instance = select_instance(required_cores, instances);
    let seconds_per_month = HOURS_PER_MONTH * 3600.0;
    let egress_gib_per_month =
        projected_rps * median_egress_bytes * seconds_per_month / GIB_IN_BYTES;
    instance.hourly_usd * HOURS_PER_MONTH + egress_gib_per_month * egress_rate_per_gib
}

fn select_instance(required_cores: f64, instances: &[InstanceType]) -> &InstanceType {
    instances
        .iter()
        .find(|i| i.vcpu >= required_cores)
        .unwrap_or_else(|| instances.last().unwrap())
}

fn median(values: &mut [f64]) -> f64 {
    if values.is_empty() {
        return 0.0;
    }
    values.sort_by(|a, b| a.partial_cmp(b).unwrap());
    let mid = values.len() / 2;
    if values.len().is_multiple_of(2) {
        (values[mid - 1] + values[mid]) / 2.0
    } else {
        values[mid]
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::pricing::get_instances;

    #[test]
    fn median_empty() {
        assert_eq!(0.0, median(&mut []));
    }

    #[test]
    fn median_single() {
        assert_eq!(5.0, median(&mut [5.0]));
    }

    #[test]
    fn median_odd() {
        assert_eq!(3.0, median(&mut [1.0, 3.0, 5.0]));
    }

    #[test]
    fn median_even() {
        assert_eq!(2.5, median(&mut [1.0, 2.0, 3.0, 4.0]));
    }

    #[test]
    fn select_instance_picks_cheapest_fitting() {
        let instances = get_instances("AWS");
        let inst = select_instance(0.5, &instances);
        assert_eq!("t3.nano", inst.name);
    }

    #[test]
    fn select_instance_falls_back_to_largest() {
        let instances = get_instances("AWS");
        let inst = select_instance(9999.0, &instances);
        // Should be the most expensive / largest
        assert!(inst.hourly_usd > 1.0);
    }

    #[test]
    fn project_empty_returns_empty() {
        let config = Config {
            provider: "AWS".into(),
            region: "us-east-1".into(),
            target_users: 1000,
            requests_per_user_per_second: 1.0,
            budget_usd: 0.0,
            dashboard_port: 7777,
            ingest_port: 7778,
        };
        assert!(project(&[], &config).is_empty());
    }

    #[test]
    fn project_single_route_returns_projection() {
        let config = Config {
            provider: "AWS".into(),
            region: "us-east-1".into(),
            target_users: 1000,
            requests_per_user_per_second: 1.0,
            budget_usd: 0.0,
            dashboard_port: 7777,
            ingest_port: 7778,
        };
        let metrics: Vec<RequestMetrics> = (0..10)
            .map(|_| RequestMetrics {
                route_template: "GET /api/test".into(),
                http_method: "GET".into(),
                http_status: 200,
                duration_ms: 50,
                cpu_core_seconds: 0.01,
                egress_bytes: 1024,
                warmup: false,
            })
            .collect();
        let projections = project(&metrics, &config);
        assert_eq!(1, projections.len());
        assert_eq!("GET /api/test", projections[0].route);
        assert!(projections[0].projected_monthly_cost_usd >= 0.0);
        assert_eq!(12, projections[0].cost_curve.len());
    }

    #[test]
    fn warmup_summary_no_warmup_metrics() {
        let config = Config {
            provider: "AWS".into(),
            region: "us-east-1".into(),
            target_users: 1000,
            requests_per_user_per_second: 1.0,
            budget_usd: 0.0,
            dashboard_port: 7777,
            ingest_port: 7778,
        };
        let metrics = vec![RequestMetrics {
            route_template: "GET /ok".into(),
            http_method: "GET".into(),
            http_status: 200,
            duration_ms: 10,
            cpu_core_seconds: 0.01,
            egress_bytes: 100,
            warmup: false,
        }];
        let summary = compute_warmup_summary(&metrics, &config);
        assert!(!summary.has_data);
    }

    #[test]
    fn pricing_all_providers_sorted() {
        for provider in &["AWS", "GCP", "AZURE"] {
            let instances = get_instances(provider);
            assert!(!instances.is_empty());
            for i in 1..instances.len() {
                assert!(
                    instances[i].hourly_usd >= instances[i - 1].hourly_usd,
                    "{} instances not sorted",
                    provider
                );
            }
        }
    }

    #[test]
    fn budget_exceeded_flag() {
        let config = Config {
            provider: "AWS".into(),
            region: "us-east-1".into(),
            target_users: 1_000_000, // high users to drive up cost
            requests_per_user_per_second: 10.0,
            budget_usd: 1.0, // very low budget
            dashboard_port: 7777,
            ingest_port: 7778,
        };
        let metrics: Vec<RequestMetrics> = (0..100)
            .map(|_| RequestMetrics {
                route_template: "GET /heavy".into(),
                http_method: "GET".into(),
                http_status: 200,
                duration_ms: 500,
                cpu_core_seconds: 0.5,
                egress_bytes: 1_000_000,
                warmup: false,
            })
            .collect();
        let projections = project(&metrics, &config);
        assert_eq!(1, projections.len());
        assert!(
            projections[0].exceeds_budget,
            "expected exceeds_budget=true"
        );
    }

    #[test]
    fn warmup_summary_with_warmup_metrics() {
        let config = Config {
            provider: "AWS".into(),
            region: "us-east-1".into(),
            target_users: 1000,
            requests_per_user_per_second: 1.0,
            budget_usd: 0.0,
            dashboard_port: 7777,
            ingest_port: 7778,
        };
        let metrics = vec![RequestMetrics {
            route_template: "GET /ok".into(),
            http_method: "GET".into(),
            http_status: 200,
            duration_ms: 10,
            cpu_core_seconds: 0.05,
            egress_bytes: 512,
            warmup: true,
        }];
        let summary = compute_warmup_summary(&metrics, &config);
        assert!(summary.has_data);
        assert_eq!(1, summary.request_count);
        assert!(summary.estimated_cold_start_cost_usd >= 0.0);
    }
}
