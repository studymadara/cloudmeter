/// Tower middleware Layer + Service implementation.
///
/// # Route templates
///
/// For accurate route templates (e.g. `/api/users/:id` instead of `/api/users/42`),
/// apply the layer using `Router::route_layer()`. With `Router::layer()` the
/// `MatchedPath` extension is not populated yet and the raw URI path is used instead.
///
/// ```rust,no_run
/// use axum::{Router, routing::get};
/// use cloudmeter::{CloudMeter, CloudMeterOptions};
///
/// #[tokio::main]
/// async fn main() {
///     let cm = CloudMeter::new(CloudMeterOptions::default());
///     let app = Router::new()
///         .route("/api/users/:id", get(|| async { "ok" }))
///         .route_layer(cm.layer());
///     let listener = tokio::net::TcpListener::bind("0.0.0.0:3000").await.unwrap();
///     axum::serve(listener, app).await.unwrap();
/// }
/// ```
use crate::model::RequestMetrics;
use crate::store::MetricsStore;
use axum::extract::MatchedPath;
use http::Request;
use std::future::Future;
use std::pin::Pin;
use std::sync::Arc;
use std::task::{Context, Poll};
use std::time::Instant;
use tower::Layer;

/// Layer that instruments every request with CloudMeter cost tracking.
#[derive(Clone)]
pub struct CloudMeterLayer {
    pub(crate) store: Arc<MetricsStore>,
}

impl CloudMeterLayer {
    pub(crate) fn from_store(store: Arc<MetricsStore>) -> Self {
        Self { store }
    }
}

impl<S> Layer<S> for CloudMeterLayer {
    type Service = CloudMeterService<S>;

    fn layer(&self, inner: S) -> Self::Service {
        CloudMeterService {
            inner,
            store: Arc::clone(&self.store),
        }
    }
}

/// Tower service that wraps the inner Axum service and records per-request metrics.
#[derive(Clone)]
pub struct CloudMeterService<S> {
    inner: S,
    store: Arc<MetricsStore>,
}

impl<S, B> tower::Service<Request<B>> for CloudMeterService<S>
where
    S: tower::Service<Request<B>, Response = axum::response::Response> + Clone + Send + 'static,
    S::Future: Send + 'static,
    B: Send + 'static,
{
    type Response = axum::response::Response;
    type Error = S::Error;
    type Future = Pin<Box<dyn Future<Output = Result<Self::Response, Self::Error>> + Send>>;

    fn poll_ready(&mut self, cx: &mut Context<'_>) -> Poll<Result<(), Self::Error>> {
        self.inner.poll_ready(cx)
    }

    fn call(&mut self, req: Request<B>) -> Self::Future {
        let start = Instant::now();
        let method = req.method().to_string();

        // MatchedPath is available when the middleware is applied with route_layer().
        // Falls back to raw URI path when applied with layer() (route not matched yet).
        let route = req
            .extensions()
            .get::<MatchedPath>()
            .map(|p| p.as_str().to_owned())
            .unwrap_or_else(|| req.uri().path().to_owned());

        let store = Arc::clone(&self.store);
        let fut = self.inner.call(req);

        Box::pin(async move {
            let response = fut.await?;

            let duration_ms = start.elapsed().as_millis() as u64;
            let status = response.status().as_u16();
            let egress: u64 = response
                .headers()
                .get("content-length")
                .and_then(|v| v.to_str().ok())
                .and_then(|s| s.parse().ok())
                .unwrap_or(0);

            store.add(RequestMetrics {
                route_template: format!("{method} {route}"),
                http_method: method,
                http_status: status,
                duration_ms,
                cpu_core_seconds: duration_ms as f64 / 1000.0,
                egress_bytes: egress,
                warmup: false, // overwritten by store.add()
            });

            Ok(response)
        })
    }
}
