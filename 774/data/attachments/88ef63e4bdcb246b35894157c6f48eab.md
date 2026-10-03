## GET http://localhost:40707/health → 200 OK

### Request Headers
```
X-Service: billing
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Sat, 03 Oct 2026 13:29:04 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:40707/health' \
  -H 'X-Service: billing'
```
