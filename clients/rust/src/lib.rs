//! CloudMeter — per-endpoint cloud cost monitoring for Axum web apps.
//!
//! # Quick start
//!
//! ```rust,no_run
//! use axum::{Router, routing::get};
//! use cloudmeter::{CloudMeter, CloudMeterOptions};
//!
//! #[tokio::main]
//! async fn main() {
//!     let cm = CloudMeter::new(CloudMeterOptions {
//!         provider: "AWS".into(),
//!         target_users: 1000,
//!         ..Default::default()
//!     });
//!
//!     let app = Router::new()
//!         .route("/api/users/:id", get(|| async { "ok" }))
//!         .route_layer(cm.layer());
//!
//!     // Dashboard → http://localhost:7777
//!     let listener = tokio::net::TcpListener::bind("0.0.0.0:3000").await.unwrap();
//!     axum::serve(listener, app).await.unwrap();
//! }
//! ```

pub mod config;
pub(crate) mod dashboard;
pub mod layer;
pub(crate) mod model;
pub(crate) mod pricing;
pub(crate) mod projector;
pub mod store;

pub use config::CloudMeterOptions;
pub use layer::CloudMeterLayer;

use std::sync::Arc;
use store::{MetricsStore, DEFAULT_CAPACITY};

/// Entry point — creates the shared metric store, starts the dashboard server,
/// and exposes the [`CloudMeterLayer`] to add to your Axum router.
pub struct CloudMeter {
    store: Arc<MetricsStore>,
}

impl CloudMeter {
    /// Create a new CloudMeter instance.
    ///
    /// - Starts recording immediately (metrics buffered in-process).
    /// - Launches the dashboard server in a background thread on `opts.port`.
    pub fn new(opts: CloudMeterOptions) -> Self {
        let store = MetricsStore::new(DEFAULT_CAPACITY);
        store.start_recording();
        dashboard::start(Arc::clone(&store), &opts);
        Self { store }
    }

    /// Returns the Tower [`CloudMeterLayer`] to add to your Axum router.
    ///
    /// Use `.route_layer(cm.layer())` for accurate route templates.
    pub fn layer(&self) -> CloudMeterLayer {
        CloudMeterLayer::from_store(Arc::clone(&self.store))
    }

    /// Raw access to the metric store — useful for tests or custom diagnostics.
    pub fn store(&self) -> Arc<MetricsStore> {
        Arc::clone(&self.store)
    }
}
