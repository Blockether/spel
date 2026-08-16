## GET http://localhost:41969/health → 200 OK

### Request Headers
```
X-Service: users
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Sun, 16 Aug 2026 02:40:18 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:41969/health' \
  -H 'X-Service: users'
```
