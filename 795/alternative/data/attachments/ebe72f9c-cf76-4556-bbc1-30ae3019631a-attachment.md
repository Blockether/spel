## GET http://localhost:46529/health → 200 OK

### Request Headers
```
X-Service: users
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Wed, 21 Oct 2026 17:51:06 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:46529/health' \
  -H 'X-Service: users'
```
