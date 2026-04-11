## GET http://localhost:44503/health → 200 OK

### Request Headers
```
X-Service: users
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Sat, 11 Apr 2026 09:37:15 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:44503/health' \
  -H 'X-Service: users'
```
