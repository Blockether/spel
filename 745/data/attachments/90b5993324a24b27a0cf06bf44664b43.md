## GET http://localhost:44785/health → 200 OK

### Request Headers
```
X-Service: users
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Fri, 11 Sep 2026 11:38:09 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:44785/health' \
  -H 'X-Service: users'
```
