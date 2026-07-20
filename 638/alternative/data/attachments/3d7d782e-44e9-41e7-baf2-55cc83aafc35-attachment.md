## GET http://localhost:46673/health → 200 OK

### Request Headers
```
X-Service: users
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Mon, 20 Jul 2026 09:30:50 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:46673/health' \
  -H 'X-Service: users'
```
