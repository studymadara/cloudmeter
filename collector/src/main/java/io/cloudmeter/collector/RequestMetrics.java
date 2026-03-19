package io.cloudmeter.collector;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable snapshot of a single completed HTTP request.
 * Java 8 compatible — no records.
 */
public final class RequestMetrics {

    private final String  routeTemplate;    // "GET /api/users/{id}"
    private final String  actualPath;       // "/api/users/8823" — outlier drill-down only
    private final int     httpStatusCode;
    private final String  httpMethod;
    private final long    durationMs;
    private final double  cpuCoreSeconds;
    private final long    peakMemoryBytes;
    private final long    egressBytes;
    private final double  threadWaitRatio;  // 0.0–1.0
    private final Instant timestamp;
    private final boolean warmup;           // true = inside warmup window; excluded from projections

    private RequestMetrics(Builder b) {
        this.routeTemplate   = Objects.requireNonNull(b.routeTemplate,  "routeTemplate");
        this.actualPath      = Objects.requireNonNull(b.actualPath,     "actualPath");
        this.httpStatusCode  = b.httpStatusCode;
        this.httpMethod      = Objects.requireNonNull(b.httpMethod,     "httpMethod");
        this.durationMs      = b.durationMs;
        this.cpuCoreSeconds  = b.cpuCoreSeconds;
        this.peakMemoryBytes = b.peakMemoryBytes;
        this.egressBytes     = b.egressBytes;
        this.threadWaitRatio = b.threadWaitRatio;
        this.timestamp       = Objects.requireNonNull(b.timestamp,      "timestamp");
        this.warmup          = b.warmup;
    }

    // ── Getters ──────────────────────────────────────────────────────────────

    public String  getRouteTemplate()   { return routeTemplate; }
    public String  getActualPath()      { return actualPath; }
    public int     getHttpStatusCode()  { return httpStatusCode; }
    public String  getHttpMethod()      { return httpMethod; }
    public long    getDurationMs()      { return durationMs; }
    public double  getCpuCoreSeconds()  { return cpuCoreSeconds; }
    public long    getPeakMemoryBytes() { return peakMemoryBytes; }
    public long    getEgressBytes()     { return egressBytes; }
    public double  getThreadWaitRatio() { return threadWaitRatio; }
    public Instant getTimestamp()       { return timestamp; }
    public boolean isWarmup()           { return warmup; }

    // ── equals / hashCode / toString ─────────────────────────────────────────

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RequestMetrics)) return false;
        RequestMetrics that = (RequestMetrics) o;
        return httpStatusCode  == that.httpStatusCode
            && durationMs      == that.durationMs
            && peakMemoryBytes == that.peakMemoryBytes
            && egressBytes     == that.egressBytes
            && warmup          == that.warmup
            && Double.compare(that.cpuCoreSeconds,  cpuCoreSeconds)  == 0
            && Double.compare(that.threadWaitRatio, threadWaitRatio) == 0
            && routeTemplate.equals(that.routeTemplate)
            && actualPath.equals(that.actualPath)
            && httpMethod.equals(that.httpMethod)
            && timestamp.equals(that.timestamp);
    }

    @Override
    public int hashCode() {
        return Objects.hash(routeTemplate, actualPath, httpStatusCode, httpMethod,
                durationMs, cpuCoreSeconds, peakMemoryBytes, egressBytes,
                threadWaitRatio, timestamp, warmup);
    }

    @Override
    public String toString() {
        return "RequestMetrics{"
            + "route='"      + routeTemplate   + '\''
            + ", path='"     + actualPath       + '\''
            + ", status="    + httpStatusCode
            + ", method='"   + httpMethod       + '\''
            + ", durationMs=" + durationMs
            + ", cpuCoreSeconds=" + cpuCoreSeconds
            + ", warmup="    + warmup
            + '}';
    }

    // ── Builder ───────────────────────────────────────────────────────────────

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String  routeTemplate;
        private String  actualPath;
        private int     httpStatusCode;
        private String  httpMethod;
        private long    durationMs;
        private double  cpuCoreSeconds;
        private long    peakMemoryBytes;
        private long    egressBytes;
        private double  threadWaitRatio;
        private Instant timestamp = Instant.now();
        private boolean warmup;

        private Builder() {}

        public Builder routeTemplate(String v)    { this.routeTemplate   = v; return this; }
        public Builder actualPath(String v)        { this.actualPath      = v; return this; }
        public Builder httpStatusCode(int v)       { this.httpStatusCode  = v; return this; }
        public Builder httpMethod(String v)        { this.httpMethod      = v; return this; }
        public Builder durationMs(long v)          { this.durationMs      = v; return this; }
        public Builder cpuCoreSeconds(double v)    { this.cpuCoreSeconds  = v; return this; }
        public Builder peakMemoryBytes(long v)     { this.peakMemoryBytes = v; return this; }
        public Builder egressBytes(long v)         { this.egressBytes     = v; return this; }
        public Builder threadWaitRatio(double v)   { this.threadWaitRatio = v; return this; }
        public Builder timestamp(Instant v)        { this.timestamp       = v; return this; }
        public Builder warmup(boolean v)           { this.warmup          = v; return this; }

        public RequestMetrics build() {
            return new RequestMetrics(this);
        }
    }
}
