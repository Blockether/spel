## GET http://localhost:38183/health → 200 OK

### Request Headers
```
X-Service: billing
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Sat, 03 Oct 2026 16:06:23 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:38183/health' \
  -H 'X-Service: billing'
```
