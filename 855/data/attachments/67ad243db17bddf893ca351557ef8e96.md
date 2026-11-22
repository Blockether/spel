## GET http://localhost:34741/health → 200 OK

### Request Headers
```
X-Service: billing
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Sun, 22 Nov 2026 18:15:04 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:34741/health' \
  -H 'X-Service: billing'
```
