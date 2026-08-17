## GET http://localhost:39747/health → 200 OK

### Request Headers
```
X-Service: users
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Mon, 17 Aug 2026 19:38:52 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:39747/health' \
  -H 'X-Service: users'
```
