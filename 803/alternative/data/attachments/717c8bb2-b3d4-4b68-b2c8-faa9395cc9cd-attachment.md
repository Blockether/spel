## GET http://localhost:42277/health → 200 OK

### Request Headers
```
X-Service: users
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Thu, 29 Oct 2026 12:49:01 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:42277/health' \
  -H 'X-Service: users'
```
