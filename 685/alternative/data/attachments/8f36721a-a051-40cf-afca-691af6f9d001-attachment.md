## GET http://localhost:40355/health → 200 OK

### Request Headers
```
X-Service: users
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Tue, 04 Aug 2026 20:33:51 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:40355/health' \
  -H 'X-Service: users'
```
