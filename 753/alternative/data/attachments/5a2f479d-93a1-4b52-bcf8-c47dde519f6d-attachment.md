## GET http://localhost:41599/echo?page=1&limit=10 → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 57
content-type: application/json
date: Tue, 15 Sep 2026 10:30:42 GMT
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
curl 'http://localhost:41599/echo?page=1&limit=10' \
  -H 'Authorization: Bearer test-token'
```
