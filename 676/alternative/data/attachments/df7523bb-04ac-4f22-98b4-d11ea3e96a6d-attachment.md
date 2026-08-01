## GET http://localhost:44783/health → 200 OK

### Request Headers
```
X-Service: users
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Sat, 01 Aug 2026 16:26:58 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:44783/health' \
  -H 'X-Service: users'
```
