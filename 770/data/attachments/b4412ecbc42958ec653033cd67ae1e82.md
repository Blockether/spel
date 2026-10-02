## GET http://localhost:42429/echo?page=1&limit=10 → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 57
content-type: application/json
date: Fri, 02 Oct 2026 20:24:06 GMT
```

### Response Body
```json
{
  "method": "GET",
  "path": "/echo",
  "query": "page=1&limit=10"
}
```

### cURL
```bash
curl 'http://localhost:42429/echo?page=1&limit=10' \
  -H 'Authorization: Bearer test-token'
```
