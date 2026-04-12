## GET http://localhost:35213/health → 200 OK

### Request Headers
```
X-Service: users
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Sun, 12 Apr 2026 15:49:02 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:35213/health' \
  -H 'X-Service: users'
```
