## GET http://localhost:34293/health → 200 OK

### Request Headers
```
X-Service: users
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Tue, 03 Nov 2026 22:25:42 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:34293/health' \
  -H 'X-Service: users'
```
