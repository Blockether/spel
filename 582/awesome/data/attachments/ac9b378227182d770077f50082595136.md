## GET http://localhost:38585/health → 200 OK

### Request Headers
```
X-Service: billing
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Sun, 12 Apr 2026 09:39:30 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:38585/health' \
  -H 'X-Service: billing'
```
