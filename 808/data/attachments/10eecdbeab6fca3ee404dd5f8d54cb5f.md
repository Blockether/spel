## GET http://localhost:40343/health → 200 OK

### Request Headers
```
X-Service: billing
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Fri, 30 Oct 2026 15:33:18 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:40343/health' \
  -H 'X-Service: billing'
```
