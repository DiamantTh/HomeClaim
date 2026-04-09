# HomeClaim REST API

This module provides a RESTful API for managing Minecraft plots, zones, and player permissions.

## Quick Start

### Configuration (config.yml)

```yaml
rest:
  enabled: true
  port: 8080
  auth:
    token: "your-secure-token-here"  # Required
    # Or use environment variable:
    # token-env: "HOMECLAIM_API_TOKEN"
    # Or use file:
    # token-file: "/path/to/token.txt"
  rate-limit: 60  # requests per minute
  allowed-hosts: []  # empty = all hosts allowed
  allow-localhost: true
  cors:
    enabled: false  # Enable for external web clients
```

### Konfiguration (nicht API-spezifisch)

- Crypto Profiles: [../config/CRYPTO_PROFILES.md](../config/CRYPTO_PROFILES.md)
- Crypto Settings (Tuning): [../config/CRYPTO_SETTINGS.md](../config/CRYPTO_SETTINGS.md)

### Authentication

All API requests require the `X-Admin-Token` header:

```bash
curl -H "X-Admin-Token: your-token" http://localhost:8080/api/v1/health
```

## API Documentation

### Interactive Documentation

When the server is running, access the Swagger UI:
- **Swagger UI**: `http://localhost:8080/api/docs`
- **OpenAPI Spec**: `http://localhost:8080/api/openapi.json`

### Available Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/health` | Health check |
| GET | `/info` | API information |
| **Plots** | | |
| GET | `/plots` | List plots (with filters) |
| GET | `/plots/at?world=&x=&z=` | Get plot at coordinates |
| GET | `/plots/{id}` | Get plot by ID |
| POST | `/plots/{id}/buy` | Buy a plot |
| POST | `/plots/{id}/sell` | List plot for sale |
| POST | `/plots/{id}/flags` | Update plot flag |
| POST | `/plots/{id}/limits` | Update plot limit |
| POST | `/plots/{id}/trust` | Trust a player |
| POST | `/plots/{id}/untrust` | Untrust a player |
| GET | `/plots/{id}/components` | List plot components |
| POST | `/plots/{id}/applyProfile` | Apply flag profile |
| **Players** | | |
| GET | `/players/{uuid}/plots` | List player's plots |
| GET | `/players/{uuid}/stats` | Player statistics |
| **Zones** | | |
| GET | `/zones?world=` | List zones in world |
| **Profiles** | | |
| GET | `/profiles` | List flag profiles |
| POST | `/profiles` | Create/update profile |
| **Admin** | | |
| GET | `/admin/stats` | Server statistics |

## Examples

### List all plots for sale in a world

```bash
curl -H "X-Admin-Token: your-token" \
  "http://localhost:8080/api/v1/plots?world=plots&available=true"
```

### Get plot at coordinates

```bash
curl -H "X-Admin-Token: your-token" \
  "http://localhost:8080/api/v1/plots/at?world=plots&x=100&z=200"
```

### Buy a plot

```bash
curl -X POST -H "X-Admin-Token: your-token" \
  -H "Content-Type: application/json" \
  -d '{"buyerId": "550e8400-e29b-41d4-a716-446655440000"}' \
  "http://localhost:8080/api/v1/plots/123e4567-e89b-12d3-a456-426614174000/buy"
```

### Set a flag on a plot

```bash
curl -X POST -H "X-Admin-Token: your-token" \
  -H "Content-Type: application/json" \
  -d '{"key": "pvp", "value": false}' \
  "http://localhost:8080/api/v1/plots/123e4567-e89b-12d3-a456-426614174000/flags"
```

### Create a flag profile

```bash
curl -X POST -H "X-Admin-Token: your-token" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "survival-friendly",
    "flags": {"pvp": false, "mob-spawn": true, "fire-spread": false},
    "limits": {"entity-cap": 50}
  }' \
  "http://localhost:8080/api/v1/profiles"
```

### Get player statistics

```bash
curl -H "X-Admin-Token: your-token" \
  "http://localhost:8080/api/v1/players/550e8400-e29b-41d4-a716-446655440000/stats"
```

## Error Responses

All errors return a JSON object with `error` and `message` fields:

```json
{
  "error": "not_found",
  "message": "Plot not found"
}
```

### Common Error Codes

| HTTP Status | Error Code | Description |
|-------------|------------|-------------|
| 400 | `bad_request` | Invalid request parameters |
| 401 | `unauthorized` | Invalid or missing token |
| 403 | `forbidden` | Host not allowed |
| 404 | `not_found` | Resource not found |
| 429 | `rate_limited` | Too many requests |
| 500 | `internal_error` | Server error |

## Rate Limiting

The API implements per-token rate limiting. The default limit is 60 requests per minute.

When rate limited, the API returns HTTP 429 with:

```json
{
  "error": "rate_limited",
  "message": "Too many requests"
}
```

## Security Recommendations

1. **Use strong tokens**: Generate a secure random token (e.g., 32+ characters)
2. **HTTPS**: Use a reverse proxy (nginx, Caddy) with TLS in production
3. **Firewall**: Restrict access to the API port if not needed publicly
4. **Environment variables**: Store tokens in environment variables, not config files
5. **CORS**: Only enable CORS if needed for web clients

## Integration Examples

### JavaScript/TypeScript (Node.js/Browser)

```typescript
const API_URL = 'http://localhost:8080/api/v1';
const TOKEN = process.env.HOMECLAIM_TOKEN;

async function getPlayerPlots(uuid: string) {
  const response = await fetch(`${API_URL}/players/${uuid}/plots`, {
    headers: { 'X-Admin-Token': TOKEN }
  });
  return response.json();
}
```

### Python

```python
import requests

API_URL = 'http://localhost:8080/api/v1'
TOKEN = os.environ['HOMECLAIM_TOKEN']
HEADERS = {'X-Admin-Token': TOKEN}

def get_plot_at(world, x, z):
    r = requests.get(f'{API_URL}/plots/at', 
                     params={'world': world, 'x': x, 'z': z},
                     headers=HEADERS)
    return r.json()
```

### Java

```java
HttpClient client = HttpClient.newHttpClient();
HttpRequest request = HttpRequest.newBuilder()
    .uri(URI.create("http://localhost:8080/api/v1/health"))
    .header("X-Admin-Token", token)
    .GET()
    .build();
HttpResponse<String> response = client.send(request, 
    HttpResponse.BodyHandlers.ofString());
```
