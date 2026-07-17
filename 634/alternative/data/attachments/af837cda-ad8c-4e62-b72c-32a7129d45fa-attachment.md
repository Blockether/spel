## GET http://localhost:37053/health → 200 OK

### Request Headers
```
X-Service: users
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Fri, 17 Jul 2026 15:42:47 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:37053/health' \
  -H 'X-Service: users'
```
