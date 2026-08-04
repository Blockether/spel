## GET http://localhost:33645/health → 200 OK

### Request Headers
```
X-Service: billing
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Tue, 04 Aug 2026 05:22:28 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:33645/health' \
  -H 'X-Service: billing'
```
