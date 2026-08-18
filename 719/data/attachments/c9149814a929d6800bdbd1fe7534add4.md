## GET http://localhost:37099/health → 200 OK

### Request Headers
```
X-Service: users
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Tue, 18 Aug 2026 09:35:13 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:37099/health' \
  -H 'X-Service: users'
```
