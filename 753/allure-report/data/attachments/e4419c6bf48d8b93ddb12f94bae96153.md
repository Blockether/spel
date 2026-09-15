## GET http://localhost:46043/health → 200 OK

### Request Headers
```
X-Service: users
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Tue, 15 Sep 2026 11:16:51 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:46043/health' \
  -H 'X-Service: users'
```
