## GET http://localhost:36499/health → 200 OK

### Request Headers
```
X-Service: billing
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Sun, 27 Sep 2026 12:53:43 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:36499/health' \
  -H 'X-Service: billing'
```
