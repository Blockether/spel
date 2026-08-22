## GET http://localhost:36843/health → 200 OK

### Request Headers
```
X-Service: users
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Sat, 22 Aug 2026 11:38:34 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:36843/health' \
  -H 'X-Service: users'
```
