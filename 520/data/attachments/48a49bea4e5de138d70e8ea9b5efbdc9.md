## GET http://localhost:43743/health → 200 OK

### Request Headers
```
X-Service: billing
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Wed, 01 Apr 2026 15:35:07 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:43743/health' \
  -H 'X-Service: billing'
```
