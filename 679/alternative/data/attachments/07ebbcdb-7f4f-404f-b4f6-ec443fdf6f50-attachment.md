## GET http://localhost:43801/health → 200 OK

### Request Headers
```
X-Service: users
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Mon, 03 Aug 2026 17:07:48 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:43801/health' \
  -H 'X-Service: users'
```
