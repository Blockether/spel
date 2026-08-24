## GET http://localhost:43483/health → 200 OK

### Request Headers
```
X-Service: users
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Mon, 24 Aug 2026 08:25:00 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:43483/health' \
  -H 'X-Service: users'
```
