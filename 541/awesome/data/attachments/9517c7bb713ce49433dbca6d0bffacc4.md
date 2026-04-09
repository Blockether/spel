## GET http://localhost:37651/health → 200 OK

### Request Headers
```
X-Service: users
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Thu, 09 Apr 2026 19:57:15 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:37651/health' \
  -H 'X-Service: users'
```
