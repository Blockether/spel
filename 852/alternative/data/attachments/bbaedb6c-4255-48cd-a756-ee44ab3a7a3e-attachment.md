## GET http://localhost:46153/health → 200 OK

### Request Headers
```
X-Service: users
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Sun, 22 Nov 2026 16:20:39 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:46153/health' \
  -H 'X-Service: users'
```
