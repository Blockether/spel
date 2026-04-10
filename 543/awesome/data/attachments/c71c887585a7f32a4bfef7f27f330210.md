## GET http://localhost:45749/health → 200 OK

### Request Headers
```
X-Service: users
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Fri, 10 Apr 2026 03:58:38 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:45749/health' \
  -H 'X-Service: users'
```
