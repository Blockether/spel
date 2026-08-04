## GET http://localhost:38161/health → 200 OK

### Request Headers
```
X-Service: billing
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Tue, 04 Aug 2026 15:04:32 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:38161/health' \
  -H 'X-Service: billing'
```
