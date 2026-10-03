## GET http://localhost:35111/health → 200 OK

### Request Headers
```
X-Service: billing
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Sat, 03 Oct 2026 14:55:55 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:35111/health' \
  -H 'X-Service: billing'
```
