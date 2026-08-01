## GET http://localhost:42217/health → 200 OK

### Request Headers
```
X-Service: users
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Sat, 01 Aug 2026 16:02:41 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:42217/health' \
  -H 'X-Service: users'
```
