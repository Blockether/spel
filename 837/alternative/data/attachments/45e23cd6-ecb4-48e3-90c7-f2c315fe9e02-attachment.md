## GET http://localhost:39025/health → 200 OK

### Request Headers
```
X-Service: billing
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Sat, 21 Nov 2026 18:25:28 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:39025/health' \
  -H 'X-Service: billing'
```
