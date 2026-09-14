## GET http://localhost:38873/health → 200 OK

### Request Headers
```
X-Service: users
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Mon, 14 Sep 2026 20:36:18 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:38873/health' \
  -H 'X-Service: users'
```
