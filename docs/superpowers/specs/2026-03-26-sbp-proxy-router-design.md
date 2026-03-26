# SBP Proxy Router — Design Spec

## Overview

Reactive HTTP proxy service (`sbp-router`) that sits between the SBP Platform and downstream systems (InfoSRV, future Verification/Connector modules). Receives GCSvc XML requests, extracts configurable fields via StAX parsing, makes routing decisions based on request type, terminal ownership, and feature flags, then proxies the original XML to the chosen upstream.

## Context

Part of the "Pre-check Service for C2B and B2C SBP" system (TKBPAY-5770). The full system comprises four modules: Router, Verification, Connector, and CFT Adapter. This spec covers **only the Router module** — a proxy that can later be extended to route TKB PAY terminal traffic to internal modules.

## Tech Stack

- **Java 17+, Spring Boot 3.x, Spring WebFlux**
- **StAX** for XML parsing (streaming, single-pass)
- **WebClient** for reactive upstream proxying
- **Micrometer + Prometheus** for metrics
- **SLF4J + Logback** for structured JSON logging
- **No database** — all configuration in `application.yml`, in-memory

## Architecture

```
Platform
    │
    ▼
┌──────────────────────────────────┐
│         sbp-router               │
│                                  │
│  HTTP Endpoint (POST /api/gcsvc) │
│           │                      │
│           ▼                      │
│  XML Field Extractor             │
│  (StAX, rules from config)      │
│           │                      │
│           ▼                      │
│  Routing Decision Engine         │
│  (request type + terminal +      │
│   feature-flag)                  │
│           │                      │
│     ┌─────┴──────┐               │
│     ▼            ▼               │
│  InfoSRV     Stub Modules        │
│  (proxy)     (flag=ON, TKB PAY)  │
└──────────────────────────────────┘
```

### Components

1. **HTTP Endpoint** — single `POST /api/gcsvc` accepting GCSvc XML from Platform
2. **XML Field Extractor** — configurable StAX parser, extraction rules defined per request type in `application.yml`
3. **Routing Decision Engine** — determines upstream based on extracted fields and feature-flag
4. **Proxy Client** — `WebClient` forwarding original XML as-is to upstream, returning response to Platform
5. **Stub Modules** — built-in stubs for Verification and Connector (used when feature-flag is ON for TKB PAY terminals)

## Configurable XML Field Extraction

Extraction rules are defined in `application.yml` per request type. Two location strategies:

- **parent + key** — for fields inside named blocks (`PayProfile`/`AdditionInfo`): finds `PNameID`=key, takes `PValue`
- **path** — for fields at fixed XML positions (e.g., `State`, `Funds/Amount`)

```yaml
sbp-router:
  extraction-rules:
    ReqAuthPay:
      routing-fields:
        - name: terminalName
          parent: PayProfile
          key: Tran.TermName
        - name: rcvTspId          # C2B terminal detection
          parent: PayProfile
          key: RcvTSPId
        - name: sbpOperation
          parent: PayProfile
          key: SbpOperation
        - name: sbpOperType
          parent: PayProfile
          key: SbpOperType
      extra-fields:
        - name: senderAccount
          parent: PayProfile
          key: SndAccountNum
        - name: amount
          path: /Document/GCSvc/Payment/ReqAuthPay/Funds/Amount

    ReqNoticePay:
      routing-fields:
        - name: terminalName
          parent: AdditionInfo
          key: Tran.TermName
        - name: rcvTspId          # C2B terminal detection
          parent: AdditionInfo
          key: RcvTSPId
        - name: sbpOperation
          parent: AdditionInfo
          key: SbpOperation
        - name: state
          path: /Document/GCSvc/Payment/ReqNoticePay/State
      extra-fields:
        - name: bankOperId
          path: /Document/GCSvc/Payment/ReqNoticePay/BankOperId
```

The StAX parser makes a single pass through the XML, collecting all fields described in rules. The result is a `Map<String, String>` passed to the Routing Decision Engine.

Adding a new field for extraction is a single line in config — no code changes required.

## Terminal Ownership Detection

Terminal ownership is determined differently for C2B and B2C operations:

The operation type is determined from the extracted `sbpOperType` field (C2B or B2C). Terminal ownership logic differs per operation type.

### C2B

The terminal is identified by the extracted `rcvTspId` field (`RcvTSPId` from `PayProfile` or `AdditionInfo` depending on request type), looked up in a configured list:

```yaml
sbp-router:
  terminals:
    c2b-terminal:
      field-name: rcvTspId          # refers to extracted field name
    tkb-pay-list:
      - MB0000700543
      - MB0000004185
```

Match in `tkb-pay-list` → `TKB_PAY`. No match → `EXTERNAL`.

### B2C

The terminal is identified by a configurable extracted field. If the field value starts with a configurable prefix, it is a TKB PAY terminal:

```yaml
sbp-router:
  terminals:
    b2c-terminal:
      field-name: terminalName      # refers to extracted field name
      tkb-pay-prefix: "Pay"
```

Both the field to use and the prefix to compare are configurable. If the field or prefix changes, only the yml needs updating.

### Detection Flow

1. Extract `sbpOperType` from XML (already part of routing-fields)
2. If C2B → look up `rcvTspId` in `tkb-pay-list`
3. If B2C → check if `terminalName` starts with `tkb-pay-prefix`
4. If `sbpOperType` is missing or unknown → treat as `EXTERNAL`

## Routing Logic and Feature-Flag

### Feature-Flag

```yaml
sbp-router:
  routing:
    tkb-pay-enabled: false  # false = everything to InfoSRV; true = TKB PAY to stubs
```

### Routing Table

| Request Type          | Terminal | Flag OFF | Flag ON           |
|-----------------------|----------|----------|-------------------|
| ReqAuthPay            | EXTERNAL | InfoSRV  | InfoSRV           |
| ReqAuthPay            | TKB_PAY  | InfoSRV  | Stub: Verification|
| ReqNoticePay          | EXTERNAL | InfoSRV  | InfoSRV           |
| ReqNoticePay, State=0 | TKB_PAY  | InfoSRV  | Stub: Connector   |
| ReqNoticePay, State!=0| TKB_PAY  | InfoSRV  | Stub: Connector (cancel) |
| Unknown request type  | Any      | InfoSRV  | InfoSRV           |

### Stub Responses

- **Stub Verification:** returns `AnsAuthPay` with `Status/Code=0`
- **Stub Connector:** returns `AnsNoticePay` with `BankOperId=STUB-{timestamp}`

## Proxying and Response Handling

### Request Flow

```
HTTP Request (XML body)
  │
  ├─ 1. Buffer body (for re-reading)
  ├─ 2. StAX parse → Map<String, String> extracted fields
  ├─ 3. Routing Decision → upstream URL
  ├─ 4. WebClient.post() → upstream with original XML as-is
  ├─ 5. Receive response from upstream
  └─ 6. Return response to Platform as-is
```

The router **never modifies** the XML body. Extracted fields are used only for routing decisions.

### Extra Fields Forwarding

Extracted extra-fields are forwarded to upstream as HTTP headers with `X-Sbp-` prefix:

```
X-Sbp-SndAccountNum: 40702810820100004437
X-Sbp-Amount: 15787
```

This allows future downstream modules (Verification, Connector) to use the data without re-parsing XML.

### Upstream Configuration

```yaml
sbp-router:
  upstreams:
    infosrv:
      url: http://infosrv.bank.local/api/gcsvc
      timeout: 30s
      retry:
        max-attempts: 2
        backoff: 500ms
    stub-verification:
      url: http://localhost:${server.port}/stub/verification
    stub-connector:
      url: http://localhost:${server.port}/stub/connector
```

### Error Handling

| Situation                  | Behavior                                                        |
|----------------------------|-----------------------------------------------------------------|
| Upstream unavailable       | Return `AnsAuthPay`/`AnsNoticePay` with `Code=-1`, `LogMsg` with description |
| Timeout                    | Same, with timeout description                                   |
| Invalid XML from Platform  | HTTP 400, log error                                              |
| Unknown request type       | Proxy to InfoSRV without analysis                                |

Error responses are well-formed GCSvc XML matching the expected response type for the incoming request.

## Observability

### Structured Logging (SLF4J + Logback, JSON format)

Every request is logged with context:

```json
{
  "timestamp": "2026-03-26T10:15:30.123Z",
  "level": "INFO",
  "correlationId": "53aa47e4-9241-4cc5-bed4-b942713b4284",
  "requestType": "ReqAuthPay",
  "terminal": "SB01133",
  "terminalOwner": "TKB_PAY",
  "sbpOperType": "B2C",
  "routeDecision": "INFOSRV",
  "upstreamResponseTime": 245,
  "upstreamStatusCode": 200
}
```

`correlationId` is taken from the `stan` attribute of the `<Document>` element — unique per operation.

### Metrics (Micrometer + Prometheus)

| Metric                                  | Type    | Tags                                      |
|-----------------------------------------|---------|-------------------------------------------|
| `sbp_router_requests_total`             | Counter | requestType, terminalOwner, routeDecision |
| `sbp_router_request_duration_seconds`   | Timer   | requestType, routeDecision                |
| `sbp_router_upstream_errors_total`      | Counter | requestType, upstream, errorType          |
| `sbp_router_active_requests`            | Gauge   | —                                         |

### Endpoints

- `POST /api/gcsvc` — main endpoint for Platform requests
- `GET /actuator/health` — health-check
- `GET /actuator/prometheus` — metrics

### Graceful Shutdown

On `SIGTERM` the service stops accepting new requests, waits for in-flight requests to complete (configurable timeout), then shuts down.

## Testing Strategy

- **Unit tests:** XML Field Extractor (various XML structures), Routing Decision Engine (all combinations from routing table), Error response generation
- **Integration tests:** Full request flow with WireMock standing in for InfoSRV
- **Configuration tests:** Verify custom extraction rules are loaded and applied correctly

## Future Extension Points

When Verification and Connector modules are implemented:
1. Replace stub URLs with real module URLs in upstream config
2. Set `tkb-pay-enabled: true`
3. Extra-fields forwarded via `X-Sbp-*` headers are already available to downstream modules

No code changes required for this transition — configuration only.
