## GET http://localhost:43121/health → 200 OK

### Request Headers
```
X-Service: users
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Tue, 07 Jul 2026 10:38:25 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:43121/health' \
  -H 'X-Service: users'
```
