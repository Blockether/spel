## GET http://localhost:38557/health → 200 OK

### Request Headers
```
X-Service: users
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Thu, 24 Sep 2026 23:50:52 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:38557/health' \
  -H 'X-Service: users'
```
