## GET http://localhost:36549/health → 200 OK

### Request Headers
```
X-Service: users
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Fri, 13 Mar 2026 07:26:49 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:36549/health' \
  -H 'X-Service: users'
```
