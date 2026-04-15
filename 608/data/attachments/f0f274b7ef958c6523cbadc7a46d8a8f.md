## GET http://localhost:39799/health → 200 OK

### Request Headers
```
X-Service: users
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Wed, 15 Apr 2026 12:25:34 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:39799/health' \
  -H 'X-Service: users'
```
