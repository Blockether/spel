## GET http://localhost:42765/health → 200 OK

### Request Headers
```
X-Service: billing
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Wed, 15 Apr 2026 10:32:54 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:42765/health' \
  -H 'X-Service: billing'
```
