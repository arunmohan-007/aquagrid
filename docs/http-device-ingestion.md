# HTTP device ingestion (production)

Canonical endpoint:

```http
POST /api/v1/receiver/http
```

TLS terminates at nginx; the Spring Boot API listens on the internal network only.

## Authentication

Present **one** of:

| Header | Purpose |
|---|---|
| `X-API-Key: <gateway-key>` | Shared gateway / modem credential. Server stores only SHA-256. |
| `X-Device-Token: <token>` | Per-device token issued at provisioning. |
| `Authorization: Bearer <token>` | Same as device bearer credential. |
| `X-Signature` + `X-Nonce` + `X-Timestamp` | HMAC over the body (device `hmacKey`). |

Do not put credentials in the URL.

## Device identity

Register the device with a communication profile that has an identity field (e.g. NB-IoT `imei`, LoRaWAN `devEui`, TCP `unitId`). That value becomes `network_address`.

The HTTP body (or headers such as `X-Imei` / `X-Device-Eui`) must carry the same identifier. The server **never** trusts `organizationId` / `tenantId` from the body — tenant comes from the resolved device row.

Example body:

```json
{
  "imei": "356938035643809",
  "observedAt": "2026-08-11T09:30:00Z",
  "volume": 128450.5,
  "flowRate": 12.4,
  "battery": 3.61,
  "fCnt": 42
}
```

Success: `202` with `{ "status": "ACCEPTED"|"DUPLICATE", "packetId": "...", "deviceId": "..." }`.

## Required production environment

| Variable | Notes |
|---|---|
| `AQUAGRID_MASTER_KEY` | AES-GCM master key (base64). |
| `JWT_PRIVATE_KEY` / `JWT_PUBLIC_KEY` / `JWT_ISSUER` | RS256 material — no ephemeral generation. |
| `APP_BASE_URL` | HTTPS public URL. |
| `CORS_ORIGINS` | Browser origins only; not a device-auth control. |
| `POSTGRES_PASSWORD` | Database. |
| `RECEIVER_GATEWAY_API_KEY_SHA256` | Hex SHA-256 of the gateway API key (unless every device uses token/HMAC). |
| `IOT_SIMULATOR_ENABLED=false` | Enforced by boot assertion. |
| `IOT_LEGACY_HTTP_INGEST_ENABLED=false` | Enforced by boot assertion. |
| `RECEIVER_REQUIRE_AUTH=true` | Default; required unless `RECEIVER_IP_ALLOW_LIST` is set. |

Optional: `RECEIVER_IP_ALLOW_LIST`, `RECEIVER_MAX_PACKET_BYTES`, `RECEIVER_PPM_DEVICE`, `RECEIVER_PPM_SOURCE`, `RECEIVER_GATEWAY_PRINCIPAL`, `TRUSTED_PROXIES`.

## Legacy endpoint

`POST /api/v1/ingest/http` is **disabled**. It bypassed receiver authentication. Do not re-enable outside a development profile.

## Postman

Import [`postman/AquaGrid-HTTP-Device-Ingestion.postman_collection.json`](postman/AquaGrid-HTTP-Device-Ingestion.postman_collection.json).
