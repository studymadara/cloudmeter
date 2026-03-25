use crate::config::CloudMeterOptions;
use crate::model::{EndpointCostProjection, InstanceType, RequestMetrics, RouteStats, ScalePoint};
use crate::pricing::{get_egress_rate_per_gib, get_instances, GIB_IN_BYTES, HOURS_PER_MONTH};
use std::collections::HashMap;

const SCALE_USERS: &[u32] = &[
    100, 200, 500, 1_000, 2_000, 5_000, 10_000, 20_000, 50_000, 100_000, 500_000, 1_000_000,
];

const RECORDING_WINDOW_S: f64 = 300.0; // 5-minute rolling window

pub fn project(
    metrics: &[RequestMetrics],
    opts: &CloudMeterOptions,
) -> Vec<EndpointCostProjection> {
    let live: Vec<&RequestMetrics> = metrics.iter().filter(|m| !m.warmup).collect();
    if live.is_empty() {
        return vec![];
    }

    let total_observed_rps = live.len() as f64 / RECORDING_WINDOW_S;
    let safe_rps = if total_observed_rps > 0.0 {
        total_observed_rps
    } else {
        1.0
    };
    let target_total_rps = opts.target_users as f64 * opts.requests_per_user_per_second;

    let instances = get_instances(&opts.provider);
    let egress_rate = get_egress_rate_per_gib(&opts.provider);

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
                safe_rps,
                target_total_rps,
                opts,
                &instances,
                egress_rate,
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

pub fn compute_stats(
    metrics: &[RequestMetrics],
    projections: &[EndpointCostProjection],
    opts: &CloudMeterOptions,
) -> Vec<RouteStats> {
    let egress_rate = get_egress_rate_per_gib(&opts.provider);
    let instances = get_instances(&opts.provider);

    // Build hourly_usd lookup from projections
    let hourly_by_route: HashMap<&str, f64> = projections
        .iter()
        .map(|p| {
            let hourly = instances
                .iter()
                .find(|i| i.name == p.recommended_instance)
                .map(|i| i.hourly_usd)
                .unwrap_or(0.096); // fallback m5.large
            (p.route.as_str(), hourly)
        })
        .collect();

    let live: Vec<&RequestMetrics> = metrics.iter().filter(|m| !m.warmup).collect();

    let mut by_route: HashMap<&str, Vec<&RequestMetrics>> = HashMap::new();
    for m in &live {
        by_route
            .entry(m.route_template.as_str())
            .or_default()
            .push(m);
    }

    let mut stats: Vec<RouteStats> = by_route
        .into_iter()
        .map(|(route, entries)| {
            let hourly_usd = *hourly_by_route.get(route).unwrap_or(&0.096);
            let mut costs: Vec<f64> = entries
                .iter()
                .map(|m| {
                    (m.duration_ms as f64 / 1000.0 / 3600.0) * hourly_usd
                        + (m.egress_bytes as f64 / GIB_IN_BYTES) * egress_rate
                })
                .collect();
            costs.sort_by(|a, b| a.partial_cmp(b).unwrap());

            let p50 = percentile(&costs, 50);
            let p95 = percentile(&costs, 95);
            let p99 = percentile(&costs, 99);
            let ratio = if p50 > 0.0 { p95 / p50 } else { 0.0 };

            RouteStats {
                route: route.to_string(),
                requests: entries.len(),
                p50_cost_usd: round6(p50),
                p95_cost_usd: round6(p95),
                p99_cost_usd: round6(p99),
                variance_ratio: round2(ratio),
                variance_warning: ratio > 1.5,
            }
        })
        .collect();

    stats.sort_by(|a, b| b.p95_cost_usd.partial_cmp(&a.p95_cost_usd).unwrap());
    stats
}

// ── internal ──────────────────────────────────────────────────────────────────

fn project_route(
    route: &str,
    entries: &[&RequestMetrics],
    total_observed_rps: f64,
    target_total_rps: f64,
    opts: &CloudMeterOptions,
    instances: &[InstanceType],
    egress_rate: f64,
) -> EndpointCostProjection {
    let observed_rps = entries.len() as f64 / RECORDING_WINDOW_S;
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

    let monthly_cost = compute_monthly_cost(
        projected_rps,
        median_cpu,
        median_egress,
        instances,
        egress_rate,
    );
    let cost_per_user = monthly_cost / opts.target_users as f64;
    let recommended = select_instance(projected_rps * median_cpu, instances);

    let cost_curve: Vec<ScalePoint> = SCALE_USERS
        .iter()
        .map(|&scale_users| {
            let scale_rps = scale_users as f64 * opts.requests_per_user_per_second;
            let scaled_rps = observed_rps * (scale_rps / total_observed_rps);
            ScalePoint {
                users: scale_users,
                monthly_cost_usd: round2(compute_monthly_cost(
                    scaled_rps,
                    median_cpu,
                    median_egress,
                    instances,
                    egress_rate,
                )),
            }
        })
        .collect();

    let exceeds_budget = opts.budget_usd > 0.0 && monthly_cost > opts.budget_usd;

    EndpointCostProjection {
        route: route.to_string(),
        observed_rps: round4(observed_rps),
        projected_rps: round1(projected_rps),
        projected_monthly_cost_usd: round2(monthly_cost),
        projected_cost_per_user_usd: round6(cost_per_user),
        recommended_instance: recommended.name.clone(),
        median_duration_ms: round2(median_duration),
        median_cpu_ms: round2(median_cpu * 1000.0),
        exceeds_budget,
        cost_curve,
    }
}

fn compute_monthly_cost(
    projected_rps: f64,
    median_cpu: f64,
    median_egress: f64,
    instances: &[InstanceType],
    egress_rate: f64,
) -> f64 {
    let required_cores = projected_rps * median_cpu;
    let inst = select_instance(required_cores, instances);
    let seconds_per_month = HOURS_PER_MONTH * 3600.0;
    let egress_gib = projected_rps * median_egress * seconds_per_month / GIB_IN_BYTES;
    inst.hourly_usd * HOURS_PER_MONTH + egress_gib * egress_rate
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

fn percentile(sorted: &[f64], p: usize) -> f64 {
    if sorted.is_empty() {
        return 0.0;
    }
    let idx = (p * sorted.len() / 100).min(sorted.len() - 1);
    sorted[idx]
}

fn round1(v: f64) -> f64 {
    (v * 10.0).round() / 10.0
}
fn round2(v: f64) -> f64 {
    (v * 100.0).round() / 100.0
}
fn round4(v: f64) -> f64 {
    (v * 10_000.0).round() / 10_000.0
}
fn round6(v: f64) -> f64 {
    (v * 1_000_000.0).round() / 1_000_000.0
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::pricing::get_instances;

    fn opts() -> CloudMeterOptions {
        CloudMeterOptions {
            provider: "AWS".into(),
            region: "us-east-1".into(),
            target_users: 1000,
            requests_per_user_per_second: 1.0,
            budget_usd: 0.0,
            port: 7777,
        }
    }

    fn make_metric(route: &str, duration_ms: u64) -> RequestMetrics {
        RequestMetrics {
            route_template: route.to_string(),
            http_method: "GET".into(),
            http_status: 200,
            duration_ms,
            cpu_core_seconds: duration_ms as f64 / 1000.0,
            egress_bytes: 512,
            warmup: false,
        }
    }

    #[test]
    fn empty_returns_empty() {
        assert!(project(&[], &opts()).is_empty());
    }

    #[test]
    fn warmup_excluded() {
        let mut m = make_metric("GET /test", 50);
        m.warmup = true;
        assert!(project(&[m], &opts()).is_empty());
    }

    #[test]
    fn single_route_projection() {
        let metrics: Vec<_> = (0..10).map(|_| make_metric("GET /api/test", 50)).collect();
        let projs = project(&metrics, &opts());
        assert_eq!(1, projs.len());
        assert_eq!("GET /api/test", projs[0].route);
        assert!(projs[0].projected_monthly_cost_usd >= 0.0);
        assert_eq!(12, projs[0].cost_curve.len());
    }

    #[test]
    fn sorted_by_cost_descending() {
        let cheap: Vec<_> = (0..10).map(|_| make_metric("GET /cheap", 10)).collect();
        let expensive: Vec<_> = (0..10)
            .map(|_| make_metric("GET /expensive", 5000))
            .collect();
        let projs = project(&[cheap, expensive].concat(), &opts());
        assert_eq!(2, projs.len());
        assert!(projs[0].projected_monthly_cost_usd >= projs[1].projected_monthly_cost_usd);
        assert_eq!("GET /expensive", projs[0].route);
    }

    #[test]
    fn exceeds_budget_when_cost_above_threshold() {
        let opts = CloudMeterOptions {
            target_users: 1_000_000,
            budget_usd: 0.01,
            ..opts()
        };
        let metrics: Vec<_> = (0..100).map(|_| make_metric("GET /heavy", 1000)).collect();
        let projs = project(&metrics, &opts);
        assert!(projs.iter().any(|p| p.exceeds_budget));
    }

    #[test]
    fn select_instance_picks_cheapest_fitting() {
        let instances = get_instances("AWS");
        let inst = select_instance(0.5, &instances);
        assert_eq!("t3.nano", inst.name);
    }

    #[test]
    fn median_values() {
        assert_eq!(0.0, median(&mut []));
        assert_eq!(5.0, median(&mut [5.0]));
        assert_eq!(3.0, median(&mut [1.0, 3.0, 5.0]));
        assert_eq!(2.5, median(&mut [1.0, 2.0, 3.0, 4.0]));
    }

    #[test]
    fn compute_stats_p50_le_p95_le_p99() {
        let cheap: Vec<_> = (0..50).map(|_| make_metric("GET /skew", 10)).collect();
        let expensive: Vec<_> = (0..10).map(|_| make_metric("GET /skew", 5000)).collect();
        let metrics = [cheap, expensive].concat();
        let projs = project(&metrics, &opts());
        let stats = compute_stats(&metrics, &projs, &opts());
        assert_eq!(1, stats.len());
        assert!(stats[0].p50_cost_usd <= stats[0].p95_cost_usd);
        assert!(stats[0].p95_cost_usd <= stats[0].p99_cost_usd);
    }

    #[test]
    fn variance_warning_on_high_spread() {
        let uniform: Vec<_> = (0..80).map(|_| make_metric("GET /v", 10)).collect();
        let outliers: Vec<_> = (0..20).map(|_| make_metric("GET /v", 10_000)).collect();
        let metrics = [uniform, outliers].concat();
        let projs = project(&metrics, &opts());
        let stats = compute_stats(&metrics, &projs, &opts());
        assert!(stats[0].variance_warning);
    }
}
