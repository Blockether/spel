## GET http://localhost:41953/health → 200 OK

### Request Headers
```
X-Service: users
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Sun, 12 Apr 2026 19:19:47 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:41953/health' \
  -H 'X-Service: users'
```
