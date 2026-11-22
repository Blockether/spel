## GET http://localhost:41557/health → 200 OK

### Request Headers
```
X-Service: users
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Sun, 22 Nov 2026 01:23:05 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:41557/health' \
  -H 'X-Service: users'
```
