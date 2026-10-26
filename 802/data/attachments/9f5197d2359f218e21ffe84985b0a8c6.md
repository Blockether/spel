## GET http://localhost:43223/health → 200 OK

### Request Headers
```
X-Service: users
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Mon, 26 Oct 2026 14:21:39 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:43223/health' \
  -H 'X-Service: users'
```
