## GET http://localhost:38515/health → 200 OK

### Request Headers
```
X-Service: billing
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Thu, 05 Mar 2026 23:36:01 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:38515/health' \
  -H 'X-Service: billing'
```
