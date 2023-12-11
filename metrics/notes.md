# Micrometer Notes

## Meter types:

- Timer
- Counter
- Gauge
- DistributionSummary
- LongTaskTimer
- FunctionCounter
- FunctionTimer
- TimerGauge

## Naming

foo.bar.baz to micrometer is renamed for the underlying impl, i.e., foo_bar_baz from Prometheus.

Tags:
- extra "map" (string->string) to qualify the meter
- allows "drill down" from a composite (i.e., # of HTTP requests overall) to a specific (i.e. where)
  the "uri" is "/api/users")
- Registry can have common tags for all metrics, e.g., "region"->"us-east-1"


