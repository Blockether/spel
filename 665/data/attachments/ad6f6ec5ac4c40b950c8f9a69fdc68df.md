## GET http://localhost:35917/health → 200 OK

### Request Headers
```
X-Service: users
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Mon, 27 Jul 2026 15:29:54 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:35917/health' \
  -H 'X-Service: users'
```
