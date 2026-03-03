## GET http://localhost:46457/echo → 200 OK

### Request Headers
```
GET http://localhost:46457/echo
```

### Response Headers
```
content-length: 73
content-type: application/json
date: Tue, 03 Mar 2026 08:19:11 GMT
```

### Response Body
```json
{
  "method": "GET",
  "path": "/echo",
  "query": "user=alice&action=view&limit=50"
}
```

### cURL
```bash
curl 'http://localhost:46457/echo'
```
