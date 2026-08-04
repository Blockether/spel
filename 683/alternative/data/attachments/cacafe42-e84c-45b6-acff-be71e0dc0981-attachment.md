## GET http://localhost:38161/health → 200 OK

### Request Headers
```
X-Service: users
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Tue, 04 Aug 2026 15:04:31 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:38161/health' \
  -H 'X-Service: users'
```
