## GET http://localhost:40427/echo → 200 OK

### Request Headers
```
GET http://localhost:40427/echo
```

### Response Headers
```
content-length: 73
content-type: application/json
date: Sun, 01 Mar 2026 16:31:40 GMT
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
curl 'http://localhost:40427/echo'
```
