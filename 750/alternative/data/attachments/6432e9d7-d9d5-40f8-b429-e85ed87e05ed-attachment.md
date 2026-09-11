## GET http://localhost:43767/echo?page=1&limit=10 → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 57
content-type: application/json
date: Fri, 11 Sep 2026 20:38:28 GMT
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
curl 'http://localhost:43767/echo?page=1&limit=10' \
  -H 'Authorization: Bearer test-token'
```
