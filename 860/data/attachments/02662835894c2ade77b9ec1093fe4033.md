## GET http://localhost:39301/health → 200 OK

### Request Headers
```
X-Service: billing
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Sun, 22 Nov 2026 20:21:03 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:39301/health' \
  -H 'X-Service: billing'
```
