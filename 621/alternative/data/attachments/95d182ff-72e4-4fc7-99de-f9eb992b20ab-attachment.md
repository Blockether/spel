## GET http://localhost:35265/health → 200 OK

### Request Headers
```
X-Service: users
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Wed, 22 Apr 2026 17:51:27 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:35265/health' \
  -H 'X-Service: users'
```
