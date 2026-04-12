## GET http://localhost:39813/health → 200 OK

### Request Headers
```
X-Service: users
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Sun, 12 Apr 2026 08:34:08 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:39813/health' \
  -H 'X-Service: users'
```
