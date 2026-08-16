## GET http://localhost:39305/health → 200 OK

### Request Headers
```
X-Service: users
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Sun, 16 Aug 2026 20:09:37 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:39305/health' \
  -H 'X-Service: users'
```
