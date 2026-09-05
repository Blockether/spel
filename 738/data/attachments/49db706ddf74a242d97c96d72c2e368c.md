## GET http://localhost:45529/health → 200 OK

### Request Headers
```
X-Service: users
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Sat, 05 Sep 2026 18:46:27 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:45529/health' \
  -H 'X-Service: users'
```
