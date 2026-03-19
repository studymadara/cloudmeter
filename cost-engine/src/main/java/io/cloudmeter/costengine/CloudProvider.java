package io.cloudmeter.costengine;

/**
 * Supported cloud providers for cost projection.
 * Static pricing tables for each provider live in {@link PricingCatalog}.
 */
public enum CloudProvider {
    /** Amazon Web Services */
    AWS,
    /** Google Cloud Platform */
    GCP,
    /** Microsoft Azure */
    AZURE
}
