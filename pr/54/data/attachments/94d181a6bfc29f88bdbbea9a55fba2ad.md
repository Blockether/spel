## GET http://localhost:36827/health → 200 OK

### Request Headers
```
X-Service: users
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Sat, 28 Feb 2026 12:12:13 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:36827/health' \
  -H 'X-Service: users'
```
