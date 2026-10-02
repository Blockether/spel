## GET http://localhost:37487/health → 200 OK

### Request Headers
```
X-Service: billing
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Fri, 02 Oct 2026 15:50:52 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:37487/health' \
  -H 'X-Service: billing'
```
