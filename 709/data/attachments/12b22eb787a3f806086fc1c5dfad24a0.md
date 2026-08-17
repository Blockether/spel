## GET http://localhost:38453/health → 200 OK

### Request Headers
```
X-Service: users
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Mon, 17 Aug 2026 07:15:09 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:38453/health' \
  -H 'X-Service: users'
```
