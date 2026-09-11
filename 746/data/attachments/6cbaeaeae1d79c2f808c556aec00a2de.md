## GET http://localhost:38053/health → 200 OK

### Request Headers
```
X-Service: users
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Fri, 11 Sep 2026 14:58:57 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:38053/health' \
  -H 'X-Service: users'
```
