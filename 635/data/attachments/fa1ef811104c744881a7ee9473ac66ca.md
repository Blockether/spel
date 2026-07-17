## GET http://localhost:33107/health → 200 OK

### Request Headers
```
X-Service: users
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Fri, 17 Jul 2026 19:39:19 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:33107/health' \
  -H 'X-Service: users'
```
