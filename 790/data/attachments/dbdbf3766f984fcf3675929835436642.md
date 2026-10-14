## GET http://localhost:33121/health → 200 OK

### Request Headers
```
X-Service: billing
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Wed, 14 Oct 2026 15:43:13 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:33121/health' \
  -H 'X-Service: billing'
```
