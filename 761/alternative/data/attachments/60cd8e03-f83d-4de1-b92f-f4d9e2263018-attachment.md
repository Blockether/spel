## GET http://localhost:46079/health → 200 OK

### Request Headers
```
X-Service: users
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Wed, 23 Sep 2026 12:25:55 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:46079/health' \
  -H 'X-Service: users'
```
