## GET http://localhost:34717/health → 200 OK

### Request Headers
```
X-Service: users
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Wed, 04 Nov 2026 12:09:35 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:34717/health' \
  -H 'X-Service: users'
```
