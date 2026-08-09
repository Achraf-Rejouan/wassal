rootProject.name = "wassal"

// One module per deployable service, plus contracts (the ONLY shared module) and proof.
// See docs/project-structure.md — a shared `common-domain` module is deliberately absent.
include(
    "contracts",
    "gateway",
    "order-service",
    "dispatch-service",
    "tracking-service",
    "simulator",
    "reconciliation",
    "proof:concurrency",
    "proof:chaos",
)
